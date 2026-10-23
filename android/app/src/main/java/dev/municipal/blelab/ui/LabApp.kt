package dev.municipal.blelab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.municipal.blelab.core.LabConstants
import dev.municipal.blelab.core.LabPermissions
import dev.municipal.blelab.core.LabSummary
import dev.municipal.blelab.core.SessionEvent
import dev.municipal.blelab.session.LabUiState

@Composable
fun LabApp(
    viewModel: LabViewModel,
    onRequestPermissions: (List<String>) -> Unit,
    onExport: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenLocationSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    listOf("Emisor", "Receptor", "Registro").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            label = { Text(label) },
                            icon = {},
                        )
                    }
                }
            },
        ) { padding ->
            val modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
            when (tab) {
                0 -> EmitterPane(state, viewModel, onRequestPermissions, onOpenBluetoothSettings, modifier)
                1 -> ReceiverPane(state, viewModel, onRequestPermissions, onOpenLocationSettings, modifier)
                else -> LogPane(state, onExport, modifier)
            }
        }
    }
}

@Composable
private fun EmitterPane(
    state: LabUiState,
    viewModel: LabViewModel,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier,
) {
    val config = state.emitter
    val caps = state.capabilities
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Emisor", style = MaterialTheme.typography.headlineSmall)
        Text(state.status)
        CapabilityBlock(state)
        if (!caps.bluetoothEnabled) {
            Button(onClick = onOpenBluetoothSettings) { Text("Abrir ajustes de Bluetooth") }
        }
        Text("Protocolo")
        ChoiceRow(
            options = listOf("ibeacon" to "iBeacon", "custom" to "BLE propio"),
            selected = config.protocol,
            onSelect = { value -> viewModel.editEmitter { it.copy(protocol = value) } },
        )
        Text("Método")
        ChoiceRow(
            options = listOf("restart" to "Reiniciar", "update_set" to "Actualizar"),
            selected = config.method,
            enabled = { value -> value != "update_set" || caps.canUpdateWithoutRestart },
            onSelect = { value -> viewModel.editEmitter { it.copy(method = value) } },
        )
        if (!caps.advertisingSetApi) {
            Text("Actualizar sin reiniciar requiere API 26 y un anunciante BLE. En este equipo está deshabilitado.")
        }
        Text("Período")
        ChoiceRow(
            options = listOf(10, 15, 30).map { it.toString() to "${it} s" },
            selected = config.periodSeconds.toString(),
            onSelect = { value -> viewModel.editEmitter { it.copy(periodSeconds = value.toInt()) } },
        )
        Text("Intervalo")
        ChoiceRow(
            options = LabConstants.advertiseIntervalsMs.map { it.toString() to "${it} ms" },
            selected = config.intervalMs.toString(),
            onSelect = { value -> viewModel.editEmitter { it.copy(intervalMs = value.toInt()) } },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Conectable")
            Switch(checked = config.connectable, onCheckedChange = { checked -> viewModel.editEmitter { it.copy(connectable = checked) } })
        }
        Text("Apagado es el anuncio legacy no conectable. Encendido sirve para comparar el caso en que Android agrega flags.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Identificador fijo")
            Switch(
                checked = config.fixedIdentifier,
                onCheckedChange = { checked -> viewModel.editEmitter { it.copy(fixedIdentifier = checked) } },
            )
        }
        if (config.fixedIdentifier) {
            OutlinedTextField(
                value = config.fixedValueText,
                onValueChange = { text -> viewModel.editEmitter { it.copy(fixedValueText = text) } },
                label = { Text("Identificador fijo") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = config.measuredPowerText,
            onValueChange = { text -> viewModel.editEmitter { it.copy(measuredPowerText = text) } },
            label = { Text("Potencia medida (dBm)") },
            modifier = Modifier.fillMaxWidth(),
        )
        val shownCode = if (state.emitterDesired) state.onAirCode else state.clockCode
        CodeCard(
            title = if (state.emitterDesired) "En el aire" else "Ventana UTC actual, todavía no se emite",
            code = shownCode,
            major = shownCode.highWord(),
            minor = shownCode.lowWord(),
            extra = if (config.fixedIdentifier && state.emitterDesired) {
                "Identificador fijo · no rota"
            } else {
                "Cambia en ${state.secondsRemaining} s · rotaciones ${state.rotationCount}"
            },
        )
        if (state.emitterDesired) {
            Button(onClick = viewModel::stopEmitter, modifier = Modifier.fillMaxWidth()) { Text("Detener emisión") }
        } else {
            Button(
                onClick = { viewModel.startEmitter(onRequestPermissions) },
                enabled = caps.canAdvertise || caps.securityException || !caps.bluetoothSupported,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Iniciar emisión") }
        }
        Text("El código temporal es el índice de la ventana UTC. Los anuncios se repiten durante toda la ventana.")
    }
}

@Composable
private fun ReceiverPane(
    state: LabUiState,
    viewModel: LabViewModel,
    onRequestPermissions: (List<String>) -> Unit,
    onOpenLocationSettings: () -> Unit,
    modifier: Modifier,
) {
    val config = state.receiver
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Receptor", style = MaterialTheme.typography.headlineSmall)
        Text(state.status)
        if (!state.capabilities.locationServiceEnabled) {
            Text("La ubicación del sistema está apagada. En Android 7 a 11 el escaneo no arranca sin ella. En versiones nuevas igual puede hacer falta, porque no se usa neverForLocation.")
            Button(onClick = onOpenLocationSettings) { Text("Abrir ajustes de ubicación") }
        }
        Text("Protocolo")
        ChoiceRow(
            options = listOf("ibeacon" to "iBeacon", "custom" to "BLE propio"),
            selected = config.protocol,
            onSelect = { value -> viewModel.editReceiver { it.copy(protocol = value) } },
        )
        Text("Período esperado")
        ChoiceRow(
            options = listOf(10, 15, 30).map { it.toString() to "${it} s" },
            selected = config.periodSeconds.toString(),
            onSelect = { value -> viewModel.editReceiver { it.copy(periodSeconds = value.toInt()) } },
        )
        Text("Gracia de borde")
        ChoiceRow(
            options = listOf(0, 1, 2).map { it.toString() to "${it} s" },
            selected = config.graceSeconds.toString(),
            onSelect = { value -> viewModel.editReceiver { it.copy(graceSeconds = value.toInt()) } },
        )
        OutlinedTextField(
            value = config.expectedText,
            onValueChange = { text -> viewModel.editReceiver { it.copy(expectedText = text) } },
            label = { Text("Código esperado, vacío = ventana actual") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.repeatsText,
            onValueChange = { text -> viewModel.editReceiver { it.copy(repeatsText = text) } },
            label = { Text("Repeticiones de la captura") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = config.groupWindowText,
            onValueChange = { text -> viewModel.editReceiver { it.copy(groupWindowText = text) } },
            label = { Text("Agrupar vista (ms). 0 muestra todo") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("El agrupado solo cambia la lista en pantalla. El archivo guarda cada anuncio y los errores.")
        Text("Ventana UTC: ${formatCode(state.clockCode)} · cambia en ${state.secondsRemaining} s")
        CodeCard(
            title = "Última lectura",
            code = state.liveCode,
            major = state.liveMajor,
            minor = state.liveMinor,
            extra = "RSSI ${state.liveRssi ?: "—"} dBm · ${state.liveClassification ?: "—"}" +
                (if (state.liveGrace) " · gracia" else "") +
                (state.livePacketPeriod?.let { " · período del paquete ${it}s" } ?: ""),
        )
        if (state.capturePhase == "running" || state.capturePhase == "gap") {
            Text("Captura ${state.captureAttempt}/${state.captureRepeats} · ${state.capturePhase}")
            Button(onClick = viewModel::stopCapture, modifier = Modifier.fillMaxWidth()) { Text("Detener captura") }
        } else if (state.receiverDesired) {
            Button(
                onClick = { viewModel.startCapture(onRequestPermissions) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Captura de 5 segundos") }
        }
        if (state.receiverDesired) {
            Button(onClick = viewModel::stopReceiver, modifier = Modifier.fillMaxWidth()) { Text("Detener recepción") }
        } else {
            Button(
                onClick = { viewModel.startReceiver(onRequestPermissions) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Iniciar recepción") }
        }
        SummaryBlock(state.summary)
    }
}

@Composable
private fun LogPane(state: LabUiState, onExport: () -> Unit, modifier: Modifier) {
    val visible = collapseObservations(state.recentEvents, state.receiver.groupWindowMs)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Registro", style = MaterialTheme.typography.headlineSmall)
        Text(state.status)
        state.sessionDirectory?.let { Text("Carpeta: $it", style = MaterialTheme.typography.bodySmall) }
        Button(onClick = onExport, enabled = state.sessionDirectory != null, modifier = Modifier.fillMaxWidth()) {
            Text("Exportar CSV y JSON")
        }
        Text("Se muestran los últimos eventos en memoria. La exportación lee el archivo completo.")
        visible.asReversed().forEach { event ->
            Text(eventLine(event), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CapabilityBlock(state: LabUiState) {
    val caps = state.capabilities
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Equipo", style = MaterialTheme.typography.titleMedium)
            Text("API ${caps.sdkInt} · Bluetooth ${yes(caps.bluetoothSupported)} · encendido ${yes(caps.bluetoothEnabled)}")
            Text("LE ${yes(caps.leSupported)} · anunciante ${yes(caps.advertiserAvailable)} · advertising múltiple informado ${yes(caps.multipleAdvertisementReported)}")
            Text("AdvertisingSet ${yes(caps.advertisingSetApi)} · largo máximo ${caps.maxAdvertisingDataLength} bytes")
            Text("Extended ${yes(caps.extendedAdvertising)} · 2M ${yes(caps.le2m)} · periódico ${yes(caps.periodicAdvertising)}. El laboratorio no los usa.")
            Text("Ubicación del sistema ${yes(caps.locationServiceEnabled)}")
            if (!caps.multipleAdvertisementReported && caps.advertiserAvailable) {
                Text("El chip informa que no admite advertising múltiple. La emisión de un anuncio igual puede funcionar; si falla, el registro va a decir FEATURE_UNSUPPORTED.")
            }
            if (caps.securityException) {
                Text("Falta permiso para leer el adaptador.")
            }
        }
    }
}

@Composable
private fun CodeCard(title: String, code: Long?, major: Int?, minor: Int?, extra: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title)
            Text(formatCode(code), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            Text("major ${major ?: "—"} · minor ${minor ?: "—"}")
            Text(extra)
        }
    }
}

@Composable
private fun SummaryBlock(summary: LabSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Resumen", style = MaterialTheme.typography.titleMedium)
            val percent = summary.successPercent?.let { "%.1f %%".format(it) } ?: "—"
            Text("Capturas ${summary.captureSuccesses}/${summary.captureAttempts} · $percent · fallos ${summary.captureFailures}")
            Text("Latencia ms · media ${num(summary.captureMeanMs)} · P50 ${summary.captureP50Ms ?: "—"} · P95 ${summary.captureP95Ms ?: "—"} · P99 ${summary.captureP99Ms ?: "—"}")
            Text("Al código nuevo · n ${summary.transitionCount} · media ${num(summary.transitionMeanMs)} ms · P50 ${summary.transitionP50Ms ?: "—"} · P95 ${summary.transitionP95Ms ?: "—"}")
            val missed = if (summary.missedWindowsEstimated) summary.missedWindows.toString() else "no estimado"
            Text("Ventanas omitidas estimadas: $missed. Usan el reloj de este equipo.")
            Text("current ${summary.currentObservations} · previous ${summary.previousObservations} · next ${summary.nextObservations} · other ${summary.otherObservations} · gracia ${summary.boundaryGraceObservations}")
            Text("Errores de emisión ${summary.emitterErrors} · errores de recepción ${summary.receiverErrors} · recuperaciones ${summary.recoveries}")
            val goal = summary.successPercent?.let { if (it >= 99.0) "la muestra llega a la meta" else "la muestra no llega a la meta" } ?: "todavía no hay capturas"
            Text("Meta de laboratorio: 99 % de capturas correctas en 5 s. Ahora $goal.")
        }
    }
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: (String) -> Boolean = { true },
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = enabled(value),
                label = { Text(label) },
            )
        }
    }
}

private fun collapseObservations(events: List<SessionEvent>, windowMs: Long): List<SessionEvent> {
    if (windowMs <= 0L) return events
    val visible = mutableListOf<SessionEvent>()
    for (event in events) {
        val previous = visible.lastOrNull()
        val sameBurst = event.eventType == "observation" &&
            previous?.eventType == "observation" &&
            previous.code == event.code &&
            event.monotonicMs - previous.monotonicMs < windowMs
        if (!sameBurst) visible.add(event)
    }
    return visible
}

private fun eventLine(event: SessionEvent): String {
    val code = event.code?.let { formatCode(it) } ?: ""
    val detail = listOfNotNull(
        event.classification,
        event.rssi?.let { "${it} dBm" },
        event.captureLatencyMs?.let { "${it} ms" },
        event.success?.let { if (it) "ok" else "fallo" },
        event.errorCode,
        event.errorMessage,
    ).joinToString(" ")
    return "${event.wallTimeUtc} ${event.eventType} $code $detail".trim()
}

private fun Long?.highWord(): Int? = this?.let { ((it shr 16) and 0xFFFF).toInt() }

private fun Long?.lowWord(): Int? = this?.let { (it and 0xFFFF).toInt() }

private fun formatCode(code: Long?): String {
    if (code == null) return "—"
    val masked = code and 0xFFFFFFFFL
    return "$masked (%08X)".format(masked)
}

private fun yes(value: Boolean): String = if (value) "sí" else "no"

private fun num(value: Double?): String = if (value == null) "—" else "%.0f".format(value)

fun permissionLabel(name: String): String = when (name) {
    LabPermissions.FINE_LOCATION -> "Ubicación precisa"
    LabPermissions.SCAN -> "Dispositivos cercanos, escaneo"
    LabPermissions.ADVERTISE -> "Dispositivos cercanos, emisión"
    LabPermissions.CONNECT -> "Dispositivos cercanos, conexión"
    LabPermissions.NOTIFICATIONS -> "Notificaciones"
    else -> name
}
