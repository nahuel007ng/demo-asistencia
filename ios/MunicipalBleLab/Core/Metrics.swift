import Foundation

enum Percentiles {
    /// Nearest-rank. La posición es ceil(p/100*N), contando desde 1.
    /// Se resta una tolerancia mínima para que un producto binario apenas mayor que un entero no suba de rango.
    static func nearestRank(_ values: [Int64], percentile: Double) -> Int64? {
        if values.isEmpty { return nil }
        precondition((0.0...100.0).contains(percentile))
        let sorted = values.sorted()
        let raw = ceil(percentile / 100.0 * Double(sorted.count) - 1e-9)
        let rank = min(max(Int(raw), 1), sorted.count)
        return sorted[rank - 1]
    }

    static func mean(_ values: [Int64]) -> Double? {
        if values.isEmpty { return nil }
        let total = values.reduce(Int64(0), +)
        return Double(total) / Double(values.count)
    }
}

enum WindowGap {
    static func estimatedMissed(seen: Set<Int64>, firstIndex: Int64, lastIndex: Int64) -> Int64? {
        if lastIndex < firstIndex { return 0 }
        let span = lastIndex - firstIndex + 1
        if span > 20_000 { return nil }
        var missed: Int64 = 0
        var window = firstIndex
        while window <= lastIndex {
            if !seen.contains(window & 0xFFFFFFFF) {
                missed += 1
            }
            window += 1
        }
        return missed
    }
}

enum CapturePhase: String {
    case idle
    case running
    case gap
    case done
}

struct CaptureOutcome: Equatable {
    let attempt: Int
    let success: Bool
    let latencyMs: Int64?
    let code: Int64?
    let reason: String
    let seriesComplete: Bool
}

/// Serie de capturas de duración fija. Hay que consultar `onFrame` antes que `onClock` en el mismo instante.
final class CaptureRunner {
    let repeats: Int
    let durationMs: Int64
    let gapMs: Int64
    private(set) var phase: CapturePhase = .idle
    private(set) var attempt = 0
    private(set) var attemptStartedAt: Int64 = 0
    private var lastCode: Int64?

    init(repeats: Int, durationMs: Int64 = LabConstants.captureDurationMs, gapMs: Int64 = LabConstants.captureGapMs) {
        precondition(repeats > 0)
        precondition(durationMs > 0)
        precondition(gapMs >= 0)
        self.repeats = repeats
        self.durationMs = durationMs
        self.gapMs = gapMs
    }

    func start(nowMs: Int64) {
        attempt = 1
        phase = .running
        attemptStartedAt = nowMs
        lastCode = nil
    }

    func stop() {
        phase = .done
    }

    func onFrame(nowMs: Int64, code: Int64, accepted: Bool) -> CaptureOutcome? {
        if phase != .running { return nil }
        lastCode = code
        if !accepted { return nil }
        return finish(nowMs: nowMs, success: true, reason: "ok")
    }

    func onClock(nowMs: Int64) -> CaptureOutcome? {
        switch phase {
        case .running:
            if nowMs - attemptStartedAt >= durationMs {
                let reason = lastCode == nil ? "no_signal" : "not_current"
                return finish(nowMs: nowMs, success: false, reason: reason)
            }
        case .gap:
            if nowMs - attemptStartedAt >= gapMs {
                attempt += 1
                phase = .running
                attemptStartedAt = nowMs
                lastCode = nil
            }
        case .idle, .done:
            break
        }
        return nil
    }

    private func finish(nowMs: Int64, success: Bool, reason: String) -> CaptureOutcome {
        let complete = attempt >= repeats
        let outcome = CaptureOutcome(
            attempt: attempt,
            success: success,
            latencyMs: success ? nowMs - attemptStartedAt : nil,
            code: lastCode,
            reason: reason,
            seriesComplete: complete
        )
        if complete {
            phase = .done
        } else {
            phase = .gap
            attemptStartedAt = nowMs
            lastCode = nil
        }
        return outcome
    }
}
