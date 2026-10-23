package dev.municipal.blelab.session

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.municipal.blelab.ble.BleCapabilities
import dev.municipal.blelab.ble.LabAdvertiser
import dev.municipal.blelab.ble.LabScanner
import dev.municipal.blelab.ble.ParsedFrame
import dev.municipal.blelab.ble.RestartAdvertiser
import dev.municipal.blelab.ble.UpdateSetAdvertiser
import dev.municipal.blelab.ble.buildAdvertisement
import dev.municipal.blelab.ble.locationServiceEnabled
import dev.municipal.blelab.ble.parseScanResult
import dev.municipal.blelab.ble.probeCapabilities
import dev.municipal.blelab.core.CapturePhase
import dev.municipal.blelab.core.CaptureRunner
import dev.municipal.blelab.core.CodeClassifier
import dev.municipal.blelab.core.EventTypes
import dev.municipal.blelab.core.IdentifierSchedule
import dev.municipal.blelab.core.LabPermissions
import dev.municipal.blelab.core.RunningStats
import dev.municipal.blelab.core.SessionEvent
import dev.municipal.blelab.core.formatUtc
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LabController(
    private val context: Context,
    private val handler: Handler,
    private val onStatus: (String) -> Unit,
) {
    private val uiState = MutableStateFlow(LabUiState())
    val ui: StateFlow<LabUiState> = uiState.asStateFlow()

    private val device = deviceSnapshot(context)
    private var store: EventStore? = null
    private var stats = RunningStats()
    private val recent = ArrayDeque<SessionEvent>()
    private var advertiser: LabAdvertiser? = null
    private var scanner: LabScanner? = null
    private var capture: CaptureRunner? = null
    private var rotationToken = 0
    private var alive = true
    private var resumeEmitter = false
    private var resumeReceiver = false
    private var suspendedByBluetooth = false
    private var loggedPacketPeriod: Int? = null
    private val loggedVersions = mutableSetOf<Int>()
    private var lastCallbackDelayLogMs = 0L
    private var summaryTick = 0

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            handler.post { if (alive) onBluetoothState(state) }
        }
    }

    fun start() {
        onThread {
            registerBluetooth()
            refreshCapabilities()
            refreshClock()
            scheduleTick()
        }
    }

    fun shutdown() {
        onThread {
            alive = false
            rotationToken++
            stopEmitterRadio(log = true)
            stopReceiverRadio(log = true)
            uiState.update { it.copy(emitterDesired = false, receiverDesired = false) }
            store?.writeSummary(stats.summary())
            store?.close()
            store = null
            try {
                context.unregisterReceiver(bluetoothReceiver)
            } catch (_: IllegalArgumentException) {
                // No estaba registrado.
            }
        }
    }

    fun updateEmitter(transform: (EmitterConfig) -> EmitterConfig) {
        onThread {
            uiState.update { it.copy(emitter = transform(it.emitter)) }
        }
    }

    fun updateReceiver(transform: (ReceiverConfig) -> ReceiverConfig) {
        onThread {
            uiState.update { it.copy(receiver = transform(it.receiver)) }
        }
    }

    fun missingPermissions(emitter: Boolean): List<String> {
        val names = if (emitter) LabPermissions.forEmitter(Build.VERSION.SDK_INT) else LabPermissions.forReceiver(Build.VERSION.SDK_INT)
        return names.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun logPermissionMissing(role: String, names: List<String>) {
        onThread {
            reject(
                role = role,
                type = EventTypes.PERMISSION_MISSING,
                errorCode = "PERMISSION",
                errorMessage = names.joinToString(","),
                status = "Faltan permisos: ${names.joinToString(", ")}",
            )
        }
    }

    fun startEmitter() {
        onThread { startEmitterOnThread() }
    }

    fun stopEmitter() {
        onThread {
            uiState.update { it.copy(emitterDesired = false) }
            rotationToken++
            stopEmitterRadio(log = true)
            record(EventTypes.SESSION_STOP, role = "emitter")
            closeStoreIfIdle()
            publish(status = "Emisión detenida")
        }
    }

    fun startReceiver() {
        onThread { startReceiverOnThread() }
    }

    fun stopReceiver() {
        onThread {
            capture?.stop()
            capture = null
            uiState.update { it.copy(receiverDesired = false, capturePhase = "idle") }
            stopReceiverRadio(log = true)
            record(EventTypes.SESSION_STOP, role = "receiver")
            closeStoreIfIdle()
            publish(status = "Recepción detenida")
        }
    }

    fun startCapture() {
        onThread {
            val state = uiState.value
            if (!state.receiverDesired) {
                publish(status = "Iniciá la recepción antes de la captura")
                return@onThread
            }
            if (state.receiver.expectedInvalid) {
                publish(status = "El código esperado no es un número")
                return@onThread
            }
            capture = CaptureRunner(repeats = state.receiver.repeats).also { it.start(SystemClock.elapsedRealtime()) }
            publish(status = "Captura 1/${state.receiver.repeats}")
        }
    }

    fun stopCapture() {
        onThread {
            capture?.stop()
            capture = null
            publish(status = "Captura detenida")
        }
    }

    fun exportFiles(): List<File> {
        if (Looper.myLooper() == handler.looper) return exportOnThread()
        val ready = CountDownLatch(1)
        var files = emptyList<File>()
        handler.post {
            files = exportOnThread()
            ready.countDown()
        }
        ready.await(5, TimeUnit.SECONDS)
        return files
    }

    private fun exportOnThread(): List<File> {
        val directory = uiState.value.sessionDirectory?.let(::File) ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        store?.writeSummary(stats.summary())
        val json = writeExportJson(directory, stats.summary())
        val csv = File(directory, "events.csv")
        return listOf(csv, json).filter { it.isFile }
    }

    private fun startEmitterOnThread() {
        val config = uiState.value.emitter
        val caps = probeCapabilities(context)
        refreshCapabilities(caps)
        val missing = missingPermissions(emitter = true)
        if (missing.isNotEmpty()) {
            reject("emitter", EventTypes.PERMISSION_MISSING, "PERMISSION", missing.joinToString(","), "Faltan permisos de emisión")
            return
        }
        if (!caps.bluetoothEnabled) {
            reject("emitter", EventTypes.ERROR, "BLUETOOTH_OFF", "Bluetooth apagado", "Bluetooth está apagado")
            return
        }
        if (!caps.advertiserAvailable) {
            reject("emitter", EventTypes.ADVERTISE_START_FAILED, "NO_ADVERTISER", "El equipo no ofrece BluetoothLeAdvertiser", "Este equipo no puede emitir BLE")
            return
        }
        if (config.method == "update_set" && !caps.advertisingSetApi) {
            reject(
                "emitter",
                EventTypes.ADVERTISE_START_FAILED,
                "FEATURE_UNSUPPORTED",
                "AdvertisingSet requiere API 26 y hardware compatible",
                "Actualizar sin reiniciar no está disponible",
            )
            return
        }
        if (config.fixedIdentifier && config.fixedValue == null) {
            reject("emitter", EventTypes.ERROR, "BAD_IDENTIFIER", "El identificador fijo no es un número", "El identificador fijo no es un número")
            return
        }
        if (config.measuredPower == null) {
            reject("emitter", EventTypes.ERROR, "BAD_POWER", "La potencia medida tiene que estar entre -128 y 127", "La potencia medida tiene que estar entre -128 y 127")
            return
        }
        ensureStore("emitter")
        uiState.update { it.copy(emitterDesired = true, activeRole = "emitter", rotationCount = 0) }
        record(EventTypes.SESSION_START, "emitter")
        startEmitterRadio()
    }

    private fun startEmitterRadio() {
        val config = uiState.value.emitter
        val built = buildAdvertisement(config, System.currentTimeMillis() / 1000)
        val engine: LabAdvertiser = if (config.method == "update_set") {
            UpdateSetAdvertiser(context, handler)
        } else {
            RestartAdvertiser(context, handler)
        }
        advertiser = engine
        engine.start(built.data, config.connectable, config.intervalMs, advertiserListener)
        uiState.update {
            it.copy(
                onAirCode = built.identifier.identifier,
                onAirMajor = built.identifier.major,
                onAirMinor = built.identifier.minor,
            )
        }
    }

    private val advertiserListener = object : LabAdvertiser.Listener {
        override fun onStarted() {
            onThread {
                uiState.update { it.copy(emitterOnAir = true) }
                record(EventTypes.ADVERTISE_STARTED, "emitter", code = uiState.value.onAirCode)
                if (!uiState.value.emitter.fixedIdentifier) armRotation()
                if (resumeEmitter) {
                    record(EventTypes.RECOVERY, "emitter", errorMessage = "emisión reanudada")
                    resumeEmitter = false
                }
                publish(status = "Emitiendo")
            }
        }

        override fun onStartFailed(code: Int, name: String) {
            onThread {
                uiState.update { it.copy(emitterOnAir = false) }
                record(EventTypes.ADVERTISE_START_FAILED, "emitter", errorCode = name, errorMessage = "código $code")
                publish(status = "Fallo de emisión: $name")
            }
        }

        override fun onDataUpdated() {
            onThread {
                val count = uiState.value.rotationCount + 1
                uiState.update { it.copy(rotationCount = count, emitterOnAir = true) }
                record(EventTypes.ROTATION_APPLIED, "emitter", code = uiState.value.onAirCode)
                if (resumeEmitter) {
                    record(EventTypes.RECOVERY, "emitter", errorMessage = "emisión reanudada")
                    resumeEmitter = false
                }
            }
        }

        override fun onDataFailed(code: Int, name: String) {
            onThread {
                record(EventTypes.ADVERTISE_DATA_FAILED, "emitter", errorCode = name, errorMessage = "código $code")
                publish(status = "No se pudo actualizar el anuncio: $name")
            }
        }

        override fun onStopped() {
            onThread {
                uiState.update { it.copy(emitterOnAir = false) }
            }
        }
    }

    private fun armRotation() {
        val token = ++rotationToken
        val config = uiState.value.emitter
        val delay = IdentifierSchedule.millisUntilNextChange(System.currentTimeMillis(), config.periodSeconds)
        val scheduledWall = System.currentTimeMillis() + delay
        handler.postDelayed({
            if (!alive || token != rotationToken || !uiState.value.emitterDesired) return@postDelayed
            rotate(scheduledWall)
            armRotation()
        }, delay)
    }

    private fun rotate(scheduledWall: Long) {
        val config = uiState.value.emitter
        val built = buildAdvertisement(config, System.currentTimeMillis() / 1000)
        uiState.update {
            it.copy(
                onAirCode = built.identifier.identifier,
                onAirMajor = built.identifier.major,
                onAirMinor = built.identifier.minor,
            )
        }
        record(
            type = EventTypes.ROTATION_SCHEDULED,
            role = "emitter",
            code = built.identifier.identifier,
            major = built.identifier.major,
            minor = built.identifier.minor,
            wall = scheduledWall,
        )
        advertiser?.update(built.data)
    }

    private fun stopEmitterRadio(log: Boolean) {
        val engine = advertiser
        advertiser = null
        engine?.stop()
        if (log && uiState.value.emitterOnAir) {
            record(EventTypes.ADVERTISE_STOPPED, "emitter")
        }
        uiState.update { it.copy(emitterOnAir = false) }
    }

    private fun startReceiverOnThread() {
        val config = uiState.value.receiver
        val caps = probeCapabilities(context)
        refreshCapabilities(caps)
        val missing = missingPermissions(emitter = false)
        if (missing.isNotEmpty()) {
            reject("receiver", EventTypes.PERMISSION_MISSING, "PERMISSION", missing.joinToString(","), "Faltan permisos de recepción")
            return
        }
        if (config.expectedInvalid) {
            reject("receiver", EventTypes.ERROR, "BAD_EXPECTED", "El código esperado no es un número", "El código esperado no es un número")
            return
        }
        if (!caps.bluetoothEnabled) {
            reject("receiver", EventTypes.ERROR, "BLUETOOTH_OFF", "Bluetooth apagado", "Bluetooth está apagado")
            return
        }
        if (Build.VERSION.SDK_INT <= 30 && !locationServiceEnabled(context)) {
            reject(
                "receiver",
                EventTypes.ERROR,
                "LOCATION_OFF",
                "El servicio de ubicación está apagado",
                "En Android 7–11 el escaneo BLE exige la ubicación del sistema encendida",
            )
            return
        }
        ensureStore("receiver")
        loggedPacketPeriod = null
        uiState.update { it.copy(receiverDesired = true, activeRole = "receiver") }
        record(EventTypes.SESSION_START, "receiver")
        startReceiverRadio()
    }

    private fun startReceiverRadio() {
        val scan = LabScanner(context, handler)
        scanner = scan
        val protocol = uiState.value.receiver.protocol
        scan.start(protocol, ::onScan, ::onScanFailed)
        uiState.update { it.copy(receiverScanning = true) }
        record(EventTypes.SCAN_STARTED, "receiver")
        if (resumeReceiver) {
            record(EventTypes.RECOVERY, "receiver", errorMessage = "recepción reanudada")
            resumeReceiver = false
        }
        publish(status = "Recibiendo")
    }

    private fun onScanFailed(code: Int, name: String) {
        uiState.update { it.copy(receiverScanning = false) }
        record(EventTypes.SCAN_FAILED, "receiver", errorCode = name, errorMessage = "código $code")
        publish(status = "Fallo de escaneo: $name")
    }

    private fun onScan(result: android.bluetooth.le.ScanResult, stackElapsedMs: Long, callbackElapsedMs: Long) {
        if (!uiState.value.receiverDesired) return
        val protocol = uiState.value.receiver.protocol
        when (val parsed = parseScanResult(result, protocol)) {
            null -> Unit
            is ParsedFrame.Unsupported -> if (loggedVersions.add(parsed.version)) {
                record(
                    type = EventTypes.UNSUPPORTED_VERSION,
                    role = "receiver",
                    monotonic = stackElapsedMs,
                    errorCode = "VERSION",
                    errorMessage = "versión ${parsed.version}",
                )
            }
            is ParsedFrame.Observation -> onObservation(parsed, result.rssi, stackElapsedMs, callbackElapsedMs)
        }
    }

    private fun onObservation(frame: ParsedFrame.Observation, rssi: Int, stackElapsedMs: Long, callbackElapsedMs: Long) {
        val config = uiState.value.receiver
        val wall = System.currentTimeMillis()
        val classification = CodeClassifier.classify(
            code = frame.code,
            unixEpochSeconds = wall / 1000,
            periodSeconds = config.periodSeconds,
            graceSeconds = config.graceSeconds,
        )
        if (frame.packetPeriod != null && frame.packetPeriod != config.periodSeconds && frame.packetPeriod != loggedPacketPeriod) {
            loggedPacketPeriod = frame.packetPeriod
            record(
                type = EventTypes.ERROR,
                role = "receiver",
                errorCode = "PERIOD_MISMATCH",
                errorMessage = "paquete=${frame.packetPeriod} pantalla=${config.periodSeconds}",
            )
        }
        record(
            type = EventTypes.OBSERVATION,
            role = "receiver",
            monotonic = stackElapsedMs,
            wall = wall,
            code = frame.code,
            major = frame.major,
            minor = frame.minor,
            rssi = rssi,
            classification = classification.base.wire,
            boundaryGrace = classification.boundaryGrace,
        )
        uiState.update {
            it.copy(
                liveCode = frame.code,
                liveMajor = frame.major,
                liveMinor = frame.minor,
                liveRssi = rssi,
                liveClassification = classification.base.wire,
                liveGrace = classification.boundaryGrace,
                livePacketPeriod = frame.packetPeriod,
            )
        }
        val runner = capture ?: return
        val accepted = CodeClassifier.captureAccepted(frame.code, classification, config.expectedCode)
        val now = maxOf(stackElapsedMs, runner.attemptStartedAt)
        runner.onFrame(now, frame.code, accepted)?.let { outcome ->
            recordCapture(outcome.attempt, outcome.success, outcome.latencyMs, outcome.code, outcome.reason)
            if (outcome.seriesComplete) {
                capture = null
                publish(status = "Captura terminada")
            } else {
                publish(status = "Pausa antes del intento ${outcome.attempt + 1}")
            }
        }
        val callbackDelay = callbackElapsedMs - stackElapsedMs
        if (callbackDelay > 500 && stackElapsedMs - lastCallbackDelayLogMs > 5_000) {
            lastCallbackDelayLogMs = stackElapsedMs
            record(
                type = EventTypes.ERROR,
                role = "receiver",
                errorCode = "CALLBACK_DELAY",
                errorMessage = "demora_callback_ms=$callbackDelay",
            )
        }
    }

    private fun stopReceiverRadio(log: Boolean) {
        scanner?.stop()
        scanner = null
        if (log && uiState.value.receiverScanning) {
            record(EventTypes.SCAN_STOPPED, "receiver")
        }
        uiState.update { it.copy(receiverScanning = false) }
    }

    private fun onBluetoothState(state: Int) {
        refreshCapabilities()
        when (state) {
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                val emitter = uiState.value.emitterDesired
                val receiver = uiState.value.receiverDesired
                if (!emitter && !receiver) return
                if (emitter) record(EventTypes.BLUETOOTH_OFF, "emitter")
                if (receiver) record(EventTypes.BLUETOOTH_OFF, "receiver")
                rotationToken++
                stopEmitterRadio(log = false)
                stopReceiverRadio(log = false)
                suspendedByBluetooth = true
                resumeEmitter = emitter
                resumeReceiver = receiver
                publish(status = "Bluetooth apagado. La sesión sigue abierta.")
            }
            BluetoothAdapter.STATE_ON -> {
                if (!suspendedByBluetooth) return
                if (uiState.value.emitterDesired) {
                    record(EventTypes.BLUETOOTH_ON, "emitter")
                    startEmitterRadio()
                }
                if (uiState.value.receiverDesired) {
                    record(EventTypes.BLUETOOTH_ON, "receiver")
                    startReceiverRadio()
                }
                suspendedByBluetooth = false
            }
        }
    }

    private fun scheduleTick() {
        handler.postDelayed({
            if (!alive) return@postDelayed
            refreshClock()
            tickCapture()
            tickPermissions()
            summaryTick++
            if (summaryTick % 25 == 0) store?.writeSummary(stats.summary())
            scheduleTick()
        }, 200)
    }

    private fun refreshClock() {
        val period = if (uiState.value.emitterDesired) uiState.value.emitter.periodSeconds else uiState.value.receiver.periodSeconds
        val now = System.currentTimeMillis() / 1000
        val id = IdentifierSchedule.ofEpoch(now, period)
        uiState.update {
            it.copy(
                secondsRemaining = IdentifierSchedule.secondsRemaining(now, period),
                clockCode = id.identifier,
                summary = stats.summary(),
                capturePhase = capture?.phase?.wire() ?: "idle",
                captureAttempt = capture?.attempt ?: 0,
                captureRepeats = capture?.repeats ?: 0,
            )
        }
    }

    private fun tickCapture() {
        val runner = capture ?: return
        val outcome = runner.onClock(SystemClock.elapsedRealtime()) ?: return
        recordCapture(outcome.attempt, outcome.success, outcome.latencyMs, outcome.code, outcome.reason)
        if (outcome.seriesComplete) {
            capture = null
            publish(status = "Captura terminada")
        } else if (runner.phase == CapturePhase.RUNNING) {
            publish(status = "Captura ${runner.attempt}/${runner.repeats}")
        } else {
            publish(status = "Pausa antes del intento ${runner.attempt + 1}")
        }
    }

    private fun tickPermissions() {
        if (uiState.value.emitterDesired) {
            val missing = missingPermissions(true)
            if (missing.isNotEmpty()) {
                record(EventTypes.PERMISSION_REVOKED, "emitter", errorCode = "PERMISSION", errorMessage = missing.joinToString(","))
                rotationToken++
                uiState.update { it.copy(emitterDesired = false) }
                stopEmitterRadio(log = true)
                closeStoreIfIdle()
                publish(status = "Se revocó un permiso de emisión. Hay que reanudar a mano.")
            }
        }
        if (uiState.value.receiverDesired) {
            val missing = missingPermissions(false)
            if (missing.isNotEmpty()) {
                record(EventTypes.PERMISSION_REVOKED, "receiver", errorCode = "PERMISSION", errorMessage = missing.joinToString(","))
                capture?.stop()
                capture = null
                uiState.update { it.copy(receiverDesired = false) }
                stopReceiverRadio(log = true)
                closeStoreIfIdle()
                publish(status = "Se revocó un permiso de recepción. Hay que reanudar a mano.")
            }
        }
    }

    private fun recordCapture(attempt: Int, success: Boolean, latency: Long?, code: Long?, reason: String) {
        record(
            type = EventTypes.CAPTURE_ATTEMPT,
            role = "receiver",
            code = code,
            captureLatencyMs = latency,
            success = success,
            errorCode = if (success) null else reason,
            errorMessage = "intento $attempt",
        )
        store?.writeSummary(stats.summary())
    }

    private fun ensureStore(role: String) {
        if (store != null) {
            uiState.update { it.copy(activeRole = role) }
            return
        }
        openStore(role)
    }

    /**
     * Un rechazo aislado abre y cierra su propia sesión, así el fallo queda en disco
     * y no se suma al resumen de una sesión ya terminada. Si el otro rol sigue activo,
     * el fallo se anexa a esa sesión.
     */
    private fun reject(role: String, type: String, errorCode: String, errorMessage: String, status: String) {
        val otherActive = if (role == "emitter") uiState.value.receiverDesired else uiState.value.emitterDesired
        if (otherActive && store != null) {
            record(type, role, errorCode = errorCode, errorMessage = errorMessage)
            publish(status = status)
            return
        }
        openStore(role)
        record(type, role, errorCode = errorCode, errorMessage = errorMessage)
        publish(status = status)
        finishStore()
    }

    private fun closeStoreIfIdle() {
        if (!uiState.value.emitterDesired && !uiState.value.receiverDesired) finishStore()
    }

    private fun openStore(role: String) {
        store?.writeSummary(stats.summary())
        store?.close()
        stats = RunningStats()
        recent.clear()
        loggedPacketPeriod = null
        loggedVersions.clear()
        lastCallbackDelayLogMs = 0L
        val stamp = formatUtc(System.currentTimeMillis()).replace("-", "").replace(":", "").replace(".", "")
        val directory = File(sessionsRoot(), "$stamp-$role")
        store = EventStore(directory)
        uiState.update {
            it.copy(sessionDirectory = directory.absolutePath, summary = stats.summary(), recentEvents = emptyList())
        }
    }

    private fun finishStore() {
        store?.writeSummary(stats.summary())
        store?.close()
        store = null
    }

    private fun sessionsRoot(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(base, "sessions")
    }

    private fun record(
        type: String,
        role: String,
        monotonic: Long = SystemClock.elapsedRealtime(),
        wall: Long = System.currentTimeMillis(),
        code: Long? = null,
        major: Int? = null,
        minor: Int? = null,
        rssi: Int? = null,
        classification: String? = null,
        boundaryGrace: Boolean? = null,
        captureLatencyMs: Long? = null,
        success: Boolean? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ) {
        val state = uiState.value
        val configPeriod = if (role == "emitter") state.emitter.periodSeconds else state.receiver.periodSeconds
        val interval = if (role == "emitter") state.emitter.intervalMs else 0
        val method = if (role == "emitter") state.emitter.method else ""
        val protocol = if (role == "emitter") state.emitter.protocol else state.receiver.protocol
        val connectable = role == "emitter" && state.emitter.connectable
        val resolvedMajor = major ?: code?.let { ((it shr 16) and 0xFFFF).toInt() }
        val resolvedMinor = minor ?: code?.let { (it and 0xFFFF).toInt() }
        val event = SessionEvent(
            sessionId = state.sessionDirectory?.let { File(it).name } ?: "sin-sesion",
            role = role,
            manufacturer = device.manufacturer,
            model = device.model,
            osVersion = device.osVersion,
            appVersion = device.appVersion,
            protocol = protocol,
            advertiseMethod = method,
            periodSeconds = configPeriod,
            intervalMs = interval,
            connectable = connectable,
            eventType = type,
            monotonicMs = monotonic,
            wallTimeUtc = formatUtc(wall),
            wallEpochMs = wall,
            code = code,
            major = resolvedMajor,
            minor = resolvedMinor,
            rssi = rssi,
            classification = classification,
            boundaryGrace = boundaryGrace,
            captureLatencyMs = captureLatencyMs,
            success = success,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )
        stats.absorb(event)
        store?.append(event)
        recent.addLast(event)
        while (recent.size > 120) recent.removeFirst()
        uiState.update { it.copy(recentEvents = recent.toList(), summary = stats.summary()) }
    }

    private fun refreshCapabilities(caps: BleCapabilities = probeCapabilities(context)) {
        uiState.update { it.copy(capabilities = caps) }
    }

    private fun publish(status: String) {
        uiState.update { it.copy(status = status, summary = stats.summary(), recentEvents = recent.toList()) }
        onStatus(status)
    }

    private fun registerBluetooth() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun onThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }
}

private fun CapturePhase.wire(): String = when (this) {
    CapturePhase.IDLE -> "idle"
    CapturePhase.RUNNING -> "running"
    CapturePhase.GAP -> "gap"
    CapturePhase.DONE -> "done"
}

private data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val appVersion: String,
)

private fun deviceSnapshot(context: Context): DeviceSnapshot {
    val version = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName ?: "0.1.0"
    } catch (_: Exception) {
        "0.1.0"
    }
    return DeviceSnapshot(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        appVersion = version,
    )
}
