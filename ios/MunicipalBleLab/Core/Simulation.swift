import CryptoKit
import Foundation

struct KioskFrame: Equatable {
    let step: Int64
    let token: String
    let qrText: String
    let bleIdentifier: Int64
    let serviceData: [UInt8]
}

struct QrFields: Equatable {
    let version: String
    let kioskId: String
    let token: String
    let step: Int64
}

struct CheckInPayload: Equatable {
    let kind: String
    let kioskId: String
    let token: String
    let step: Int64
    let bleIdentifier: Int64
    let qrText: String
}

enum CheckInPhase: Equatable {
    case listening
    case accepted
    case retry
    case assisted
}

struct CheckInView: Equatable {
    let phase: CheckInPhase
    let message: String
    let payload: CheckInPayload?
    let consecutiveFailures: Int
}

enum CheckInCopy {
    static let success = "Ingreso correcto"
    static let retry = "No se pudo marcar. Intentá de nuevo."
    static let assisted = "Si sigue sin poder, pedile al encargado una fichada asistida. Podés intentar otra vez."
    static let listening = "Escuchando el QR y el BLE"
}

enum Simulation {
    static let secret = "lab-secreto-mock"
    static let kioskId = "lab-kiosco-01"
    static let label = "asis-ble-v1"
    static let periodSeconds = 15
    static let qrVersion = "v2"
    static let payloadKind = "lab-checkin"
    static let checkInLimitMs: Int64 = 15_000

    static func step(_ unixEpochSeconds: Int64) -> Int64 {
        unixEpochSeconds / Int64(periodSeconds)
    }

    static func counterBytes(_ step: Int64) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: 8)
        var value = step
        for index in stride(from: 7, through: 0, by: -1) {
            bytes[index] = UInt8(truncatingIfNeeded: value)
            value >>= 8
        }
        return bytes
    }

    static func token(step: Int64, secret: String = secret) -> String {
        let key = SymmetricKey(data: Data(secret.utf8))
        let mac = Array(HMAC<Insecure.SHA1>.authenticationCode(for: Data(counterBytes(step)), using: key))
        let offset = Int(mac[mac.count - 1] & 0x0F)
        let binary = (Int(mac[offset] & 0x7F) << 24) |
            (Int(mac[offset + 1]) << 16) |
            (Int(mac[offset + 2]) << 8) |
            Int(mac[offset + 3])
        return String(format: "%06d", binary % 1_000_000)
    }

    static func bleIdentifier(step: Int64, secret: String = secret, label: String = label) -> Int64 {
        let derived = Data(HMAC<SHA256>.authenticationCode(
            for: Data(label.utf8),
            using: SymmetricKey(data: Data(secret.utf8))
        ))
        let mac = Array(HMAC<SHA256>.authenticationCode(for: Data(counterBytes(step)), using: SymmetricKey(data: derived)))
        let value = (Int64(mac[0]) << 24) | (Int64(mac[1]) << 16) | (Int64(mac[2]) << 8) | Int64(mac[3])
        return value & 0xFFFFFFFF
    }

    static func qrText(kioskId: String = Simulation.kioskId, token: String, step: Int64) -> String {
        "\(qrVersion)|\(kioskId)|\(token)|\(step)"
    }

    static func kioskFrame(
        unixEpochSeconds: Int64,
        measuredPower: Int = LabConstants.defaultMeasuredPower
    ) -> KioskFrame {
        let current = step(unixEpochSeconds)
        let currentToken = token(step: current)
        let ble = bleIdentifier(step: current)
        return KioskFrame(
            step: current,
            token: currentToken,
            qrText: qrText(token: currentToken, step: current),
            bleIdentifier: ble,
            serviceData: CustomCodec.encode(
                identifier: ble,
                periodSeconds: periodSeconds,
                measuredPower: measuredPower,
                timeDerived: true
            )
        )
    }

    static func parseQr(_ text: String) -> QrFields? {
        let parts = text.components(separatedBy: "|")
        guard parts.count == 4, parts[0] == qrVersion, !parts[1].isEmpty else { return nil }
        guard parts[2].range(of: #"^\d{6}$"#, options: .regularExpression) != nil else { return nil }
        guard let parsedStep = Int64(parts[3]) else { return nil }
        return QrFields(version: parts[0], kioskId: parts[1], token: parts[2], step: parsedStep)
    }

    static func pairValid(qrText: String, observedBle: Int64, phoneStep: Int64) -> Bool {
        guard let qr = parseQr(qrText), qr.kioskId == kioskId, qr.token == token(step: qr.step) else { return false }
        let expected = [
            bleIdentifier(step: qr.step),
            bleIdentifier(step: qr.step - 1),
            bleIdentifier(step: qr.step + 1),
        ]
        guard expected.contains(observedBle) else { return false }
        return abs(qr.step - phoneStep) <= 1
    }

    static func payload(qrText: String, observedBle: Int64) -> CheckInPayload? {
        guard let qr = parseQr(qrText) else { return nil }
        return CheckInPayload(
            kind: payloadKind,
            kioskId: qr.kioskId,
            token: qr.token,
            step: qr.step,
            bleIdentifier: observedBle,
            qrText: qrText
        )
    }

    /// Mock en el proceso. No llama a la red y no es el contrato de la API.
    static func mockAccept(_ payload: CheckInPayload, phoneStep: Int64) -> Bool {
        guard payload.kind == payloadKind, let qr = parseQr(payload.qrText) else { return false }
        if payload.kioskId != qr.kioskId || payload.token != qr.token || payload.step != qr.step { return false }
        if payload.qrText.contains(secret) || payload.token.contains(secret) { return false }
        return pairValid(qrText: payload.qrText, observedBle: payload.bleIdentifier, phoneStep: phoneStep)
    }

    static func resolveCheckIn(
        qrText: String?,
        observedBle: Int64?,
        phoneUnixSeconds: Int64,
        elapsedMs: Int64,
        consecutiveFailures: Int
    ) -> CheckInView {
        let phoneStep = step(phoneUnixSeconds)
        if let qrText, let observedBle, elapsedMs <= checkInLimitMs,
           let body = payload(qrText: qrText, observedBle: observedBle),
           pairValid(qrText: qrText, observedBle: observedBle, phoneStep: phoneStep) {
            if mockAccept(body, phoneStep: phoneStep) {
                return CheckInView(phase: .accepted, message: CheckInCopy.success, payload: body, consecutiveFailures: 0)
            }
            return failure(consecutiveFailures)
        }
        if elapsedMs >= checkInLimitMs { return failure(consecutiveFailures) }
        return CheckInView(phase: .listening, message: CheckInCopy.listening, payload: nil, consecutiveFailures: consecutiveFailures)
    }

    private static func failure(_ consecutiveFailures: Int) -> CheckInView {
        let failures = consecutiveFailures + 1
        let assisted = failures >= 2
        return CheckInView(
            phase: assisted ? .assisted : .retry,
            message: assisted ? CheckInCopy.assisted : CheckInCopy.retry,
            payload: nil,
            consecutiveFailures: failures
        )
    }
}
