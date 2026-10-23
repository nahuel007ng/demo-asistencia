import Foundation

struct RotatingIdentifier: Equatable {
    let windowIndex: Int64
    let identifier: Int64
    let major: Int
    let minor: Int
}

enum IdentifierSchedule {
    static func ofEpoch(_ unixEpochSeconds: Int64, periodSeconds: Int) -> RotatingIdentifier {
        precondition(periodSeconds > 0)
        return fromWindowIndex(floorDiv(unixEpochSeconds, Int64(periodSeconds)))
    }

    static func fromRaw(_ identifier: Int64) -> RotatingIdentifier {
        fromWindowIndex(identifier & 0xFFFFFFFF)
    }

    static func fromWindowIndex(_ index: Int64) -> RotatingIdentifier {
        let masked = index & 0xFFFFFFFF
        let major = Int((masked >> 16) & 0xFFFF)
        let minor = Int(masked & 0xFFFF)
        return RotatingIdentifier(windowIndex: index, identifier: masked, major: major, minor: minor)
    }

    static func windowIndex(_ unixEpochSeconds: Int64, periodSeconds: Int) -> Int64 {
        precondition(periodSeconds > 0)
        return floorDiv(unixEpochSeconds, Int64(periodSeconds))
    }

    /// En el límite exacto el remanente es el período completo: la ventana acaba de empezar.
    static func secondsRemaining(_ unixEpochSeconds: Int64, periodSeconds: Int) -> Int {
        precondition(periodSeconds > 0)
        let into = floorMod(unixEpochSeconds, Int64(periodSeconds))
        return periodSeconds - Int(into)
    }

    static func millisUntilNextChange(_ wallEpochMillis: Int64, periodSeconds: Int) -> Int64 {
        precondition(periodSeconds > 0)
        let periodMs = Int64(periodSeconds) * 1000
        let remainder = floorMod(wallEpochMillis, periodMs)
        return remainder == 0 ? periodMs : periodMs - remainder
    }
}
