import CoreBluetooth
import CoreLocation
import Darwin
import Foundation

struct ReceiverConfig: Equatable {
    var protocolName: String = "ibeacon"
    var periodSeconds: Int = LabConstants.defaultPeriodSeconds
    var graceSeconds: Int = 0
    var expectedText: String = ""
    var repeatsText: String = "20"
    var groupWindowText: String = "0"
    var allowDuplicates: Bool = true

    var expectedCode: Int64? {
        let trimmed = expectedText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let value = Int64(trimmed) else { return nil }
        return value & 0xFFFFFFFF
    }

    var expectedInvalid: Bool {
        let trimmed = expectedText.trimmingCharacters(in: .whitespaces)
        return !trimmed.isEmpty && Int64(trimmed) == nil
    }

    var repeats: Int {
        min(max(Int(repeatsText.trimmingCharacters(in: .whitespaces)) ?? 20, 1), 500)
    }

    var groupWindowMs: Int64 {
        max(Int64(groupWindowText.trimmingCharacters(in: .whitespaces)) ?? 0, 0)
    }
}

struct LabUiState: Equatable {
    var receiver = ReceiverConfig()
    var receiverDesired = false
    var receiverScanning = false
    var capturePhase = CapturePhase.idle.rawValue
    var captureAttempt = 0
    var captureRepeats = 0
    var liveCode: Int64?
    var liveMajor: Int?
    var liveMinor: Int?
    var liveRssi: Int?
    var liveClassification: String?
    var liveGrace = false
    var livePacketPeriod: Int?
    var secondsRemaining = 0
    var clockCode: Int64?
    var summary = LabSummary()
    var recentEvents: [SessionEvent] = []
    var status = "Listo"
    var sessionDirectory: String?
    var bluetoothState = "desconocido"
    var locationState = "sin consultar"
}

final class LabController: ObservableObject {
    @Published private(set) var state = LabUiState()

    private let device = DeviceSnapshot()
    private var store: EventStore?
    private var stats = RunningStats()
    private var recent: [SessionEvent] = []
    private var ranger: BeaconRanger?
    private var scanner: CustomScanner?
    private var capture: CaptureRunner?
    private var timer: Timer?
    private var startPending = false
    private var suspendedByBluetooth = false
    private var resumeAfterBluetooth = false
    private var leftForeground = false
    private var loggedPacketPeriod: Int?
    private var loggedVersions = Set<Int>()
    private var lastCallbackDelayLogMs: Int64 = 0
    private var summaryTick = 0
    private var reportedWriteFailure = false

    init() {
        timer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            self?.tick()
        }
        refreshClock()
    }

    deinit {
        timer?.invalidate()
        ranger?.stop()
        scanner?.stop()
        store?.writeSummary(stats.summary())
        store?.close()
    }

    func updateReceiver(_ transform: (inout ReceiverConfig) -> Void) {
        transform(&state.receiver)
    }

    func startReceiver() {
        if state.receiver.expectedInvalid {
            reject(
                type: EventTypes.error,
                errorCode: "BAD_EXPECTED",
                errorMessage: "El código esperado no es un número",
                status: "El código esperado no es un número"
            )
            return
        }
        startPending = true
        ensureRadios()
        continueStart()
    }

    func stopReceiver() {
        capture?.stop()
        capture = nil
        state.receiverDesired = false
        state.capturePhase = CapturePhase.idle.rawValue
        startPending = false
        suspendedByBluetooth = false
        resumeAfterBluetooth = false
        stopRadio(log: true)
        record(type: EventTypes.sessionStop)
        finishStore()
        publish(status: "Recepción detenida")
    }

    func startCapture() {
        guard state.receiverDesired else {
            publish(status: "Iniciá la recepción antes de la captura")
            return
        }
        if state.receiver.expectedInvalid {
            publish(status: "El código esperado no es un número")
            return
        }
        let runner = CaptureRunner(repeats: state.receiver.repeats)
        runner.start(nowMs: monotonicMs())
        capture = runner
        publish(status: "Captura 1/\(state.receiver.repeats)")
    }

    func stopCapture() {
        capture?.stop()
        capture = nil
        state.capturePhase = CapturePhase.idle.rawValue
        publish(status: "Captura detenida")
    }

    func sceneIsActive(_ active: Bool) {
        guard state.receiverDesired else {
            leftForeground = false
            return
        }
        if !active {
            if !leftForeground {
                leftForeground = true
                record(
                    type: EventTypes.error,
                    errorCode: "APP_INACTIVE",
                    errorMessage: "La app dejó el primer plano. iOS puede pausar el escaneo hasta que vuelva."
                )
                publish(status: "La app no está activa. El escaneo puede estar pausado.")
            }
        } else if leftForeground {
            leftForeground = false
            record(type: EventTypes.recovery, errorMessage: "la app volvió al primer plano")
            publish(status: "Recibiendo")
        }
    }

    func exportFiles() -> [URL] {
        guard let path = state.sessionDirectory else { return [] }
        let directory = URL(fileURLWithPath: path, isDirectory: true)
        store?.writeSummary(stats.summary())
        guard let json = writeExportJson(directory: directory, summary: stats.summary()) else { return [] }
        let csv = directory.appendingPathComponent("events.csv")
        return [csv, json].filter { FileManager.default.fileExists(atPath: $0.path) }
    }

    private func ensureRadios() {
        if ranger == nil {
            let created = BeaconRanger()
            created.onBeacons = { [weak self] beacons in self?.onBeacons(beacons) }
            created.onFailure = { [weak self] message in self?.onRangingFailed(message) }
            created.onAuthorization = { [weak self] status in self?.onAuthorization(status) }
            ranger = created
            state.locationState = locationLabel(created.authorization)
        }
        if scanner == nil {
            let created = CustomScanner()
            created.onState = { [weak self] managerState in self?.onBluetooth(managerState) }
            created.onPacket = { [weak self] data, rssi in self?.onCustom(data, rssi: rssi) }
            scanner = created
            state.bluetoothState = bluetoothLabel(created.managerState)
        }
    }

    private func continueStart() {
        guard startPending else { return }
        if state.receiver.protocolName == "ibeacon" {
            let status = ranger?.authorization ?? .notDetermined
            state.locationState = locationLabel(status)
            if status == .notDetermined {
                publish(status: "Hay que autorizar la ubicación para iBeacon")
                ranger?.requestWhenInUse()
                return
            }
            if status == .denied || status == .restricted {
                startPending = false
                reject(
                    type: EventTypes.permissionMissing,
                    errorCode: "PERMISSION",
                    errorMessage: "ubicación When In Use denegada",
                    status: "Falta el permiso de ubicación"
                )
                return
            }
        }
        switch scanner?.managerState ?? .unknown {
        case .unknown, .resetting:
            publish(status: "Esperando el estado de Bluetooth")
            return
        case .unauthorized:
            startPending = false
            reject(
                type: EventTypes.permissionMissing,
                errorCode: "PERMISSION",
                errorMessage: "permiso de Bluetooth denegado",
                status: "Falta el permiso de Bluetooth"
            )
            return
        case .poweredOff:
            startPending = false
            reject(
                type: EventTypes.error,
                errorCode: "BLUETOOTH_OFF",
                errorMessage: "Bluetooth apagado",
                status: "Bluetooth está apagado"
            )
            return
        case .unsupported:
            startPending = false
            reject(
                type: EventTypes.error,
                errorCode: "NO_BLUETOOTH",
                errorMessage: "Este iPhone no ofrece Bluetooth Low Energy",
                status: "Este iPhone no ofrece Bluetooth Low Energy"
            )
            return
        case .poweredOn:
            break
        @unknown default:
            publish(status: "Estado de Bluetooth no reconocido")
            return
        }
        startPending = false
        guard ensureStore() else { return }
        loggedPacketPeriod = nil
        state.receiverDesired = true
        record(type: EventTypes.sessionStart)
        startRadio()
    }

    private func startRadio() {
        if state.receiver.protocolName == "ibeacon" {
            scanner?.stop()
            ranger?.start()
        } else {
            ranger?.stop()
            scanner?.allowDuplicates = state.receiver.allowDuplicates
            scanner?.start()
        }
        state.receiverScanning = true
        record(type: EventTypes.scanStarted)
        if resumeAfterBluetooth {
            record(type: EventTypes.recovery, errorMessage: "recepción reanudada")
            resumeAfterBluetooth = false
        }
        publish(status: "Recibiendo")
    }

    private func stopRadio(log: Bool) {
        let wasScanning = state.receiverScanning
        ranger?.stop()
        scanner?.stop()
        if log && wasScanning {
            record(type: EventTypes.scanStopped)
        }
        state.receiverScanning = false
    }

    private func onBluetooth(_ managerState: CBManagerState) {
        state.bluetoothState = bluetoothLabel(managerState)
        switch managerState {
        case .poweredOff:
            guard state.receiverDesired else {
                if startPending { continueStart() }
                return
            }
            record(type: EventTypes.bluetoothOff, errorCode: "BLUETOOTH_OFF", errorMessage: "Bluetooth apagado")
            suspendedByBluetooth = true
            resumeAfterBluetooth = true
            stopRadio(log: false)
            publish(status: "Bluetooth apagado. La sesión sigue abierta.")
        case .poweredOn:
            if startPending {
                continueStart()
                return
            }
            if suspendedByBluetooth && state.receiverDesired {
                record(type: EventTypes.bluetoothOn, errorMessage: "Bluetooth encendido")
                suspendedByBluetooth = false
                startRadio()
            }
        case .unauthorized:
            if state.receiverDesired {
                revoke(message: "permiso de Bluetooth")
            } else if startPending {
                continueStart()
            }
        default:
            if startPending { continueStart() }
        }
    }

    private func onAuthorization(_ status: CLAuthorizationStatus) {
        state.locationState = locationLabel(status)
        if state.receiverDesired && state.receiver.protocolName == "ibeacon" &&
            (status == .denied || status == .restricted) {
            revoke(message: "permiso de ubicación")
            return
        }
        if startPending { continueStart() }
    }

    private func revoke(message: String) {
        record(type: EventTypes.permissionRevoked, errorCode: "PERMISSION", errorMessage: message)
        capture?.stop()
        capture = nil
        state.receiverDesired = false
        startPending = false
        suspendedByBluetooth = false
        resumeAfterBluetooth = false
        stopRadio(log: true)
        finishStore()
        publish(status: "Se revocó un permiso. Hay que reanudar a mano.")
    }

    private func onRangingFailed(_ message: String) {
        guard state.receiverDesired else { return }
        state.receiverScanning = false
        record(type: EventTypes.scanFailed, errorCode: "RANGING", errorMessage: message)
        publish(status: "Falló el ranging: \(message)")
    }

    private func onBeacons(_ beacons: [CLBeacon]) {
        guard state.receiverDesired, state.receiver.protocolName == "ibeacon" else { return }
        let nowWall = wallEpochMs()
        for beacon in beacons {
            let major = beacon.major.intValue
            let minor = beacon.minor.intValue
            let code = (Int64(major) << 16) | Int64(minor)
            let beaconWall = Int64((beacon.timestamp.timeIntervalSince1970 * 1000.0).rounded())
            onObservation(
                code: code,
                major: major,
                minor: minor,
                rssi: beacon.rssi,
                packetPeriod: nil,
                sourceDelayMs: nowWall - beaconWall
            )
        }
    }

    private func onCustom(_ data: Data, rssi: Int) {
        guard state.receiverDesired, state.receiver.protocolName == "custom" else { return }
        switch CustomCodec.decode([UInt8](data)) {
        case .frame(let frame):
            onObservation(
                code: frame.identifier,
                major: frame.major,
                minor: frame.minor,
                rssi: rssi,
                packetPeriod: frame.periodSeconds,
                sourceDelayMs: nil
            )
        case .unsupportedVersion(let version):
            if loggedVersions.insert(version).inserted {
                record(
                    type: EventTypes.unsupportedVersion,
                    errorCode: "VERSION",
                    errorMessage: "versión \(version)"
                )
            }
        case .malformed:
            break
        }
    }

    private func onObservation(
        code: Int64,
        major: Int,
        minor: Int,
        rssi: Int,
        packetPeriod: Int?,
        sourceDelayMs: Int64?
    ) {
        let config = state.receiver
        let wall = wallEpochMs()
        let monotonic = monotonicMs()
        let classification = CodeClassifier.classify(
            code: code,
            unixEpochSeconds: wall / 1000,
            periodSeconds: config.periodSeconds,
            graceSeconds: config.graceSeconds
        )
        if let packetPeriod, packetPeriod != config.periodSeconds, packetPeriod != loggedPacketPeriod {
            loggedPacketPeriod = packetPeriod
            record(
                type: EventTypes.error,
                errorCode: "PERIOD_MISMATCH",
                errorMessage: "paquete=\(packetPeriod) pantalla=\(config.periodSeconds)"
            )
        }
        record(
            type: EventTypes.observation,
            monotonic: monotonic,
            wall: wall,
            code: code,
            major: major,
            minor: minor,
            rssi: rssi,
            classification: classification.base.rawValue,
            boundaryGrace: classification.boundaryGrace
        )
        state.liveCode = code
        state.liveMajor = major
        state.liveMinor = minor
        state.liveRssi = rssi
        state.liveClassification = classification.base.rawValue
        state.liveGrace = classification.boundaryGrace
        state.livePacketPeriod = packetPeriod
        if let runner = capture {
            let accepted = CodeClassifier.captureAccepted(code: code, classification: classification, expectedCode: config.expectedCode)
            let now = max(monotonic, runner.attemptStartedAt)
            if let outcome = runner.onFrame(nowMs: now, code: code, accepted: accepted) {
                recordCapture(outcome)
                if outcome.seriesComplete {
                    capture = nil
                    publish(status: "Captura terminada")
                } else {
                    publish(status: "Pausa antes del intento \(outcome.attempt + 1)")
                }
            }
        }
        if let sourceDelayMs, sourceDelayMs > 500, monotonic - lastCallbackDelayLogMs > 5_000 {
            lastCallbackDelayLogMs = monotonic
            record(
                type: EventTypes.error,
                errorCode: "CALLBACK_DELAY",
                errorMessage: "demora_callback_ms=\(sourceDelayMs)"
            )
        }
    }

    private func tick() {
        refreshClock()
        tickCapture()
        summaryTick += 1
        if summaryTick % 25 == 0 {
            store?.writeSummary(stats.summary())
        }
    }

    private func refreshClock() {
        let now = wallEpochMs() / 1000
        let period = state.receiver.periodSeconds
        let identifier = IdentifierSchedule.ofEpoch(now, periodSeconds: period)
        state.secondsRemaining = IdentifierSchedule.secondsRemaining(now, periodSeconds: period)
        state.clockCode = identifier.identifier
        state.summary = stats.summary()
        state.capturePhase = capture?.phase.rawValue ?? CapturePhase.idle.rawValue
        state.captureAttempt = capture?.attempt ?? 0
        state.captureRepeats = capture?.repeats ?? 0
    }

    private func tickCapture() {
        guard let runner = capture else { return }
        guard let outcome = runner.onClock(nowMs: monotonicMs()) else { return }
        recordCapture(outcome)
        if outcome.seriesComplete {
            capture = nil
            publish(status: "Captura terminada")
        } else if runner.phase == .running {
            publish(status: "Captura \(runner.attempt)/\(runner.repeats)")
        } else {
            publish(status: "Pausa antes del intento \(runner.attempt + 1)")
        }
    }

    private func recordCapture(_ outcome: CaptureOutcome) {
        record(
            type: EventTypes.captureAttempt,
            code: outcome.code,
            captureLatencyMs: outcome.latencyMs,
            success: outcome.success,
            errorCode: outcome.success ? nil : outcome.reason,
            errorMessage: "intento \(outcome.attempt)"
        )
        store?.writeSummary(stats.summary())
    }

    private func ensureStore() -> Bool {
        if store != nil { return true }
        return openStore()
    }

    private func reject(type: String, errorCode: String, errorMessage: String, status: String) {
        guard openStore() else { return }
        record(type: type, errorCode: errorCode, errorMessage: errorMessage)
        publish(status: status)
        finishStore()
    }

    private func openStore() -> Bool {
        store?.writeSummary(stats.summary())
        store?.close()
        stats = RunningStats()
        recent.removeAll()
        loggedPacketPeriod = nil
        loggedVersions.removeAll()
        lastCallbackDelayLogMs = 0
        reportedWriteFailure = false
        let stamp = formatUtc(wallEpochMs())
            .replacingOccurrences(of: "-", with: "")
            .replacingOccurrences(of: ":", with: "")
            .replacingOccurrences(of: ".", with: "")
        let root = sessionsRoot().appendingPathComponent("\(stamp)-receiver", isDirectory: true)
        do {
            let opened = try EventStore(directory: root)
            opened.onWriteFailure = { [weak self] message in
                self?.noteWriteFailure(message)
            }
            store = opened
            state.sessionDirectory = root.path
            state.summary = stats.summary()
            state.recentEvents = []
            return true
        } catch {
            store = nil
            state.status = "No se pudo abrir la carpeta de la sesión: \(error.localizedDescription)"
            return false
        }
    }

    private func finishStore() {
        store?.writeSummary(stats.summary())
        store?.close()
        store = nil
    }

    private func noteWriteFailure(_ message: String) {
        if reportedWriteFailure { return }
        reportedWriteFailure = true
        state.status = "No se pudo escribir el registro: \(message)"
    }

    private func sessionsRoot() -> URL {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return documents.appendingPathComponent("sessions", isDirectory: true)
    }

    private func record(
        type: String,
        monotonic: Int64 = monotonicMs(),
        wall: Int64 = wallEpochMs(),
        code: Int64? = nil,
        major: Int? = nil,
        minor: Int? = nil,
        rssi: Int? = nil,
        classification: String? = nil,
        boundaryGrace: Bool? = nil,
        captureLatencyMs: Int64? = nil,
        success: Bool? = nil,
        errorCode: String? = nil,
        errorMessage: String? = nil
    ) {
        let config = state.receiver
        let sessionId = state.sessionDirectory.map { URL(fileURLWithPath: $0).lastPathComponent } ?? "sin-sesion"
        let event = SessionEvent(
            sessionId: sessionId,
            role: "receiver",
            manufacturer: device.manufacturer,
            model: device.model,
            osVersion: device.osVersion,
            appVersion: device.appVersion,
            protocolName: config.protocolName,
            advertiseMethod: "",
            periodSeconds: config.periodSeconds,
            intervalMs: 0,
            connectable: false,
            eventType: type,
            monotonicMs: monotonic,
            wallTimeUtc: formatUtc(wall),
            wallEpochMs: wall,
            code: code,
            major: major ?? code.map { Int(($0 >> 16) & 0xFFFF) },
            minor: minor ?? code.map { Int($0 & 0xFFFF) },
            rssi: rssi,
            classification: classification,
            boundaryGrace: boundaryGrace,
            captureLatencyMs: captureLatencyMs,
            success: success,
            errorCode: errorCode,
            errorMessage: errorMessage
        )
        stats.absorb(event)
        store?.append(event)
        recent.append(event)
        if recent.count > 120 { recent.removeFirst(recent.count - 120) }
        state.recentEvents = recent
        state.summary = stats.summary()
    }

    private func publish(status: String) {
        state.status = status
        state.summary = stats.summary()
        state.recentEvents = recent
    }
}

private struct DeviceSnapshot {
    let manufacturer: String
    let model: String
    let osVersion: String
    let appVersion: String

    init() {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        manufacturer = "Apple"
        model = machineModel()
        osVersion = "iOS \(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
        appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    }
}

private func machineModel() -> String {
    var system = utsname()
    uname(&system)
    return withUnsafeBytes(of: &system.machine) { buffer in
        guard let base = buffer.bindMemory(to: CChar.self).baseAddress else { return "iPhone" }
        return String(cString: base)
    }
}

func monotonicMs() -> Int64 {
    Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
}

func wallEpochMs() -> Int64 {
    Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
}

private func bluetoothLabel(_ state: CBManagerState) -> String {
    switch state {
    case .unknown: return "desconocido"
    case .resetting: return "reiniciando"
    case .unsupported: return "no disponible"
    case .unauthorized: return "sin permiso"
    case .poweredOff: return "apagado"
    case .poweredOn: return "encendido"
    @unknown default: return "no reconocido"
    }
}

private func locationLabel(_ status: CLAuthorizationStatus) -> String {
    switch status {
    case .notDetermined: return "sin consultar"
    case .restricted: return "restringida"
    case .denied: return "denegada"
    case .authorizedAlways: return "siempre"
    case .authorizedWhenInUse: return "mientras se usa"
    @unknown default: return "no reconocida"
    }
}
