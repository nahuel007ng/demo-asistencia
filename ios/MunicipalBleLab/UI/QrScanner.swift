import AVFoundation
import SwiftUI
import Vision

struct QrScanner: UIViewControllerRepresentable {
    var onText: (String) -> Void

    func makeUIViewController(context: Context) -> QrScannerController {
        let controller = QrScannerController()
        controller.onText = onText
        return controller
    }

    func updateUIViewController(_ controller: QrScannerController, context: Context) {
        controller.onText = onText
    }
}

final class QrScannerController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
    var onText: ((String) -> Void)?
    private let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "dev.municipal.blelab.qr")
    private let preview = AVCaptureVideoPreviewLayer()
    private var lastText: String?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        preview.session = session
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        queue.async { [weak self] in self?.session.startRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixel = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let request = VNDetectBarcodesRequest { [weak self] request, _ in
            guard let self else { return }
            let values = request.results as? [VNBarcodeObservation]
            let text = values?.first { $0.symbology == .qr }?.payloadStringValue
            guard let text, text != self.lastText else { return }
            self.lastText = text
            DispatchQueue.main.async { self.onText?(text) }
        }
        request.symbologies = [.qr]
        try? VNImageRequestHandler(cvPixelBuffer: pixel, orientation: .right).perform([request])
    }
}
