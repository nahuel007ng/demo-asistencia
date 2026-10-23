import Foundation

enum CodeClass: String {
    case current
    case previous
    case next
    case other
}

struct Classification: Equatable {
    let base: CodeClass
    let boundaryGrace: Bool
}

enum CodeClassifier {
    static func classify(code: Int64, unixEpochSeconds: Int64, periodSeconds: Int, graceSeconds: Int) -> Classification {
        precondition(periodSeconds > 0)
        let observed = code & 0xFFFFFFFF
        let current = IdentifierSchedule.ofEpoch(unixEpochSeconds, periodSeconds: periodSeconds).identifier
        let previous = IdentifierSchedule.ofEpoch(unixEpochSeconds - Int64(periodSeconds), periodSeconds: periodSeconds).identifier
        let next = IdentifierSchedule.ofEpoch(unixEpochSeconds + Int64(periodSeconds), periodSeconds: periodSeconds).identifier
        let base: CodeClass
        if observed == current {
            base = .current
        } else if observed == previous {
            base = .previous
        } else if observed == next {
            base = .next
        } else {
            base = .other
        }
        let into = floorMod(unixEpochSeconds, Int64(periodSeconds))
        let grace = base == .previous && graceSeconds > 0 && into < Int64(graceSeconds)
        return Classification(base: base, boundaryGrace: grace)
    }

    /// Con código esperado, la captura es correcta solo si coincide.
    /// Sin él, hace falta la ventana actual o un previous dentro de la gracia configurada.
    static func captureAccepted(code: Int64, classification: Classification, expectedCode: Int64?) -> Bool {
        if let expectedCode {
            return (code & 0xFFFFFFFF) == (expectedCode & 0xFFFFFFFF)
        }
        return classification.base == .current || classification.boundaryGrace
    }
}
