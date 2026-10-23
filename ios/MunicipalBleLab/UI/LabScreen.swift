import SwiftUI
import UIKit

struct LabScreen: View {
    @ObservedObject var lab: LabController
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = 0
    @State private var shareURLs: [URL] = []
    @State private var showShare = false

    var body: some View {
        VStack(spacing: 0) {
            Picker("Sección", selection: $tab) {
                Text("Receptor").tag(0)
                Text("Registro").tag(1)
            }
            .pickerStyle(.segmented)
            .padding()
            ScrollView {
                if tab == 0 {
                    receiver
                } else {
                    log
                }
            }
        }
        .onChange(of: scenePhase, perform: { phase in
            lab.sceneIsActive(phase == .active)
        })
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = lab.state.receiverDesired
        }
        .onChange(of: lab.state.receiverDesired, perform: { desired in
            UIApplication.shared.isIdleTimerDisabled = desired
        })
        .sheet(isPresented: $showShare) {
            ShareSheet(urls: shareURLs)
        }
    }

    private var receiver: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Receptor").font(.title2)
            Text(lab.state.status)
            Text("Bluetooth: \(lab.state.bluetoothState) · ubicación: \(lab.state.locationState)")
                .font(.subheadline)
            if lab.state.locationState == "denegada" || lab.state.bluetoothState == "sin permiso" {
                Button("Abrir ajustes") { openSettings() }
            }
            Text("Protocolo")
            choice(options: [("ibeacon", "iBeacon"), ("custom", "BLE propio")], selected: lab.state.receiver.protocolName) { value in
                lab.updateReceiver { $0.protocolName = value }
            }
            Text("Período esperado")
            choice(options: [(10, "10 s"), (15, "15 s"), (30, "30 s")].map { (String($0.0), $0.1) }, selected: String(lab.state.receiver.periodSeconds)) { value in
                lab.updateReceiver { $0.periodSeconds = Int(value) ?? 15 }
            }
            Text("Gracia de borde")
            choice(options: [(0, "0 s"), (1, "1 s"), (2, "2 s")].map { (String($0.0), $0.1) }, selected: String(lab.state.receiver.graceSeconds)) { value in
                lab.updateReceiver { $0.graceSeconds = Int(value) ?? 0 }
            }
            if lab.state.receiver.protocolName == "ibeacon" {
                Text("iBeacon entra por Core Location. El ranging suele llegar cerca de una vez por segundo y puede repetir el major/minor anterior. Esta pantalla mide esa demora; no la oculta.")
                    .font(.footnote)
            } else {
                Toggle("Entregar duplicados", isOn: Binding(
                    get: { lab.state.receiver.allowDuplicates },
                    set: { value in lab.updateReceiver { $0.allowDuplicates = value } }
                ))
                Text("Sin este interruptor, iOS agrupa anuncios iguales. Al bajar el brillo o salir de la app el sistema igual puede frenar el escaneo. El registro lo va a marcar.")
                    .font(.footnote)
            }
            Text("Protocolo, período, gracia, duplicados y código esperado quedan fijos mientras la recepción está activa.")
                .font(.footnote)
            field("Código esperado, vacío = ventana actual", text: lab.state.receiver.expectedText) { text in
                lab.updateReceiver { $0.expectedText = text }
            }
            field("Repeticiones de la captura", text: lab.state.receiver.repeatsText) { text in
                lab.updateReceiver { $0.repeatsText = text }
            }
            Text("Ventana UTC: \(formatCode(lab.state.clockCode)) · cambia en \(lab.state.secondsRemaining) s")
                .font(.system(.body, design: .monospaced))
            reading
            if lab.state.capturePhase == "running" || lab.state.capturePhase == "gap" {
                Text("Captura \(lab.state.captureAttempt)/\(lab.state.captureRepeats) · \(lab.state.capturePhase)")
                Button("Detener captura") { lab.stopCapture() }
            } else if lab.state.receiverDesired {
                Button("Captura de 5 segundos") { lab.startCapture() }
            }
            if lab.state.receiverDesired {
                Button("Detener recepción") { lab.stopReceiver() }
            } else {
                Button("Iniciar recepción") { lab.startReceiver() }
            }
            summary
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .disabled(false)
    }

    private var reading: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Última lectura")
            Text(formatCode(lab.state.liveCode)).font(.system(.title3, design: .monospaced))
            Text("major \(lab.state.liveMajor.map(String.init) ?? "—") · minor \(lab.state.liveMinor.map(String.init) ?? "—")")
            Text(readingDetail)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.15))
        .cornerRadius(8)
    }

    private var readingDetail: String {
        let rssi = lab.state.liveRssi.map { "\($0) dBm" } ?? "—"
        let kind = lab.state.liveClassification ?? "—"
        let grace = lab.state.liveGrace ? " · gracia" : ""
        let period = lab.state.livePacketPeriod.map { " · período del paquete \($0)s" } ?? ""
        let unknown = lab.state.liveRssi == 0 ? " · RSSI 0 en iBeacon significa sin medición" : ""
        return "RSSI \(rssi) · \(kind)\(grace)\(period)\(unknown)"
    }

    private var summary: some View {
        let item = lab.state.summary
        let percent = item.successPercent.map { String(format: "%.1f %%", $0) } ?? "—"
        let missed = item.missedWindowsEstimated ? String(item.missedWindows ?? 0) : "no estimado"
        let goal: String
        if let percent = item.successPercent {
            goal = percent >= 99 ? "la muestra llega a la meta" : "la muestra no llega a la meta"
        } else {
            goal = "todavía no hay capturas"
        }
        return VStack(alignment: .leading, spacing: 4) {
            Text("Resumen").font(.headline)
            Text("Capturas \(item.captureSuccesses)/\(item.captureAttempts) · \(percent) · fallos \(item.captureFailures)")
            Text("Latencia ms · media \(num(item.captureMeanMs)) · P50 \(item.captureP50Ms.map(String.init) ?? "—") · P95 \(item.captureP95Ms.map(String.init) ?? "—") · P99 \(item.captureP99Ms.map(String.init) ?? "—")")
            Text("Al código nuevo · n \(item.transitionCount) · media \(num(item.transitionMeanMs)) ms · P50 \(item.transitionP50Ms.map(String.init) ?? "—") · P95 \(item.transitionP95Ms.map(String.init) ?? "—")")
            Text("Ventanas omitidas estimadas: \(missed). Usan el reloj de este iPhone.")
            Text("current \(item.currentObservations) · previous \(item.previousObservations) · next \(item.nextObservations) · other \(item.otherObservations) · gracia \(item.boundaryGraceObservations)")
            Text("Errores de recepción \(item.receiverErrors) · recuperaciones \(item.recoveries)")
            Text("Meta de laboratorio: 99 % de capturas correctas en 5 s. Ahora \(goal).")
        }
        .font(.subheadline)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(white: 0.15))
        .cornerRadius(8)
    }

    private var log: some View {
        let visible = collapseObservations(lab.state.recentEvents, windowMs: lab.state.receiver.groupWindowMs)
        return VStack(alignment: .leading, spacing: 8) {
            Text("Registro").font(.title2)
            Text(lab.state.status)
            if let directory = lab.state.sessionDirectory {
                Text("Carpeta: \(directory)").font(.footnote)
            }
            Button("Exportar CSV y JSON") {
                shareURLs = lab.exportFiles()
                showShare = !shareURLs.isEmpty
            }
            .disabled(lab.state.sessionDirectory == nil)
            field("Agrupar vista (ms). 0 muestra todo", text: lab.state.receiver.groupWindowText) { text in
                lab.updateReceiver { $0.groupWindowText = text }
            }
            Text("El agrupado solo cambia la lista en pantalla. El archivo guarda cada anuncio y los errores.")
                .font(.footnote)
            Text("Se muestran los últimos eventos en memoria. La exportación lee el archivo completo.")
                .font(.footnote)
            ForEach(Array(visible.enumerated().reversed()), id: \.offset) { _, event in
                Text(eventLine(event))
                    .font(.system(.caption, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func choice(options: [(String, String)], selected: String, onSelect: @escaping (String) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(options, id: \.0) { value, label in
                    Button(label) { onSelect(value) }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(value == selected ? Color.accentColor : Color(white: 0.2))
                        .foregroundColor(.white)
                        .cornerRadius(8)
                        .disabled(lab.state.receiverDesired)
                }
            }
        }
    }

    private func field(_ title: String, text: String, onChange: @escaping (String) -> Void) -> some View {
        VStack(alignment: .leading) {
            Text(title).font(.caption)
            TextField(title, text: Binding(get: { text }, set: onChange))
                .textFieldStyle(.roundedBorder)
                .disabled(lab.state.receiverDesired && title != "Agrupar vista (ms). 0 muestra todo")
        }
    }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let urls: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: urls, applicationActivities: nil)
        if let popover = controller.popoverPresentationController,
           let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let window = scene.windows.first {
            popover.sourceView = window
            popover.sourceRect = CGRect(x: window.bounds.midX, y: window.bounds.maxY - 40, width: 1, height: 1)
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private func collapseObservations(_ events: [SessionEvent], windowMs: Int64) -> [SessionEvent] {
    if windowMs <= 0 { return events }
    var visible: [SessionEvent] = []
    for event in events {
        let previous = visible.last
        let sameBurst = event.eventType == EventTypes.observation &&
            previous?.eventType == EventTypes.observation &&
            previous?.code == event.code &&
            event.monotonicMs - (previous?.monotonicMs ?? 0) < windowMs
        if !sameBurst { visible.append(event) }
    }
    return visible
}

private func eventLine(_ event: SessionEvent) -> String {
    let code = event.code.map { formatCode($0) } ?? ""
    let parts = [
        event.classification,
        event.rssi.map { "\($0) dBm" },
        event.captureLatencyMs.map { "\($0) ms" },
        event.success.map { $0 ? "ok" : "fallo" },
        event.errorCode,
        event.errorMessage,
    ].compactMap { $0 }
    return "\(event.wallTimeUtc) \(event.eventType) \(code) \(parts.joined(separator: " "))"
}

private func formatCode(_ code: Int64?) -> String {
    guard let code else { return "—" }
    let masked = UInt64(code & 0xFFFFFFFF)
    return String(format: "%llu (%08llX)", masked, masked)
}

private func num(_ value: Double?) -> String {
    guard let value else { return "—" }
    return String(format: "%.0f", value)
}

private func openSettings() {
    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
    UIApplication.shared.open(url)
}
