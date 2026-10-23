import Foundation

func toHex(_ bytes: [UInt8]) -> String {
    bytes.map { String(format: "%02X", $0) }.joined()
}

func hexToBytes(_ hex: String) -> [UInt8] {
    precondition(hex.count % 2 == 0, "Hex de longitud impar")
    var out = [UInt8]()
    out.reserveCapacity(hex.count / 2)
    var index = hex.startIndex
    while index < hex.endIndex {
        let next = hex.index(index, offsetBy: 2)
        let byte = UInt8(String(hex[index..<next]), radix: 16)
        precondition(byte != nil, "Hex inválido")
        out.append(byte!)
        index = next
    }
    return out
}

func parseUuidBytes(_ uuid: String) -> [UInt8] {
    let hex = uuid.replacingOccurrences(of: "-", with: "")
    precondition(hex.count == 32, "UUID inválido: \(uuid)")
    return hexToBytes(hex)
}

func formatUuid(_ bytes: [UInt8]) -> String {
    precondition(bytes.count == 16)
    let hex = toHex(bytes)
    let chars = Array(hex)
    func slice(_ from: Int, _ to: Int) -> String {
        String(chars[from..<to])
    }
    return "\(slice(0, 8))-\(slice(8, 12))-\(slice(12, 16))-\(slice(16, 20))-\(slice(20, 32))"
}

struct IBeaconFrame: Equatable {
    let uuid: String
    let major: Int
    let minor: Int
    let measuredPower: Int
    let identifier: Int64
}

struct ManufacturerFilter {
    let companyId: Int
    let data: [UInt8]
    let mask: [UInt8]
}

enum IBeaconCodec {
    static let payloadBytes = 23

    static func encodePayload(uuid: String, major: Int, minor: Int, measuredPower: Int) -> [UInt8] {
        precondition((0...0xFFFF).contains(major) && (0...0xFFFF).contains(minor))
        var out = [UInt8](repeating: 0, count: payloadBytes)
        out[0] = 0x02
        out[1] = 0x15
        let uuidBytes = parseUuidBytes(uuid)
        for index in 0..<16 {
            out[2 + index] = uuidBytes[index]
        }
        out[18] = UInt8((major >> 8) & 0xFF)
        out[19] = UInt8(major & 0xFF)
        out[20] = UInt8((minor >> 8) & 0xFF)
        out[21] = UInt8(minor & 0xFF)
        out[22] = UInt8(truncatingIfNeeded: measuredPower)
        return out
    }

    static func decodePayload(_ payload: [UInt8]) -> IBeaconFrame? {
        if payload.count < payloadBytes { return nil }
        if payload[0] != 0x02 || payload[1] != 0x15 { return nil }
        let uuid = formatUuid(Array(payload[2..<18]))
        let major = (Int(payload[18]) << 8) | Int(payload[19])
        let minor = (Int(payload[20]) << 8) | Int(payload[21])
        let power = Int(Int8(bitPattern: payload[22]))
        let identifier = (Int64(major) << 16) | Int64(minor)
        return IBeaconFrame(uuid: uuid, major: major, minor: minor, measuredPower: power, identifier: identifier)
    }

    /// Major, minor y potencia quedan fuera de la máscara. iOS no usa este filtro: Core Bluetooth no ve iBeacon.
    static func manufacturerFilter(uuid: String) -> ManufacturerFilter {
        let data = encodePayload(uuid: uuid, major: 0, minor: 0, measuredPower: 0)
        var mask = [UInt8](repeating: 0, count: data.count)
        for index in 0..<18 {
            mask[index] = 0xFF
        }
        return ManufacturerFilter(companyId: LabConstants.appleCompanyId, data: data, mask: mask)
    }

    static func advertisingBytes(includeFlags: Bool) -> Int {
        27 + (includeFlags ? 3 : 0)
    }
}

struct CustomFrame: Equatable {
    static let flagTimeDerived = 0x01

    let version: Int
    let flags: Int
    let identifier: Int64
    let periodSeconds: Int
    let measuredPower: Int

    var timeDerived: Bool { flags & CustomFrame.flagTimeDerived != 0 }
    var major: Int { Int((identifier >> 16) & 0xFFFF) }
    var minor: Int { Int(identifier & 0xFFFF) }
}

enum CustomDecode: Equatable {
    case frame(CustomFrame)
    case unsupportedVersion(Int)
    case malformed
}

enum CustomCodec {
    static let payloadBytes = 9

    static func encode(
        identifier: Int64,
        periodSeconds: Int,
        measuredPower: Int,
        timeDerived: Bool,
        version: Int = LabConstants.protocolVersion
    ) -> [UInt8] {
        let masked = identifier & 0xFFFFFFFF
        return [
            UInt8(truncatingIfNeeded: version),
            UInt8(timeDerived ? CustomFrame.flagTimeDerived : 0),
            UInt8((masked >> 24) & 0xFF),
            UInt8((masked >> 16) & 0xFF),
            UInt8((masked >> 8) & 0xFF),
            UInt8(masked & 0xFF),
            UInt8((periodSeconds >> 8) & 0xFF),
            UInt8(periodSeconds & 0xFF),
            UInt8(truncatingIfNeeded: measuredPower),
        ]
    }

    static func decode(_ payload: [UInt8]) -> CustomDecode {
        if payload.count < payloadBytes { return .malformed }
        let version = Int(payload[0])
        if version != LabConstants.protocolVersion { return .unsupportedVersion(version) }
        let flags = Int(payload[1])
        let identifier = (Int64(payload[2]) << 24) |
            (Int64(payload[3]) << 16) |
            (Int64(payload[4]) << 8) |
            Int64(payload[5])
        let period = (Int(payload[6]) << 8) | Int(payload[7])
        let power = Int(Int8(bitPattern: payload[8]))
        return .frame(CustomFrame(
            version: version,
            flags: flags,
            identifier: identifier,
            periodSeconds: period,
            measuredPower: power
        ))
    }

    static func advertisingBytes(includeFlags: Bool) -> Int {
        27 + (includeFlags ? 3 : 0)
    }
}
