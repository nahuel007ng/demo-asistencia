import Foundation

struct SessionEvent: Equatable {
    var sessionId: String
    var role: String
    var manufacturer: String
    var model: String
    var osVersion: String
    var appVersion: String
    var protocolName: String
    var advertiseMethod: String
    var periodSeconds: Int
    var intervalMs: Int
    var connectable: Bool
    var eventType: String
    var monotonicMs: Int64
    var wallTimeUtc: String
    var wallEpochMs: Int64
    var code: Int64?
    var major: Int?
    var minor: Int?
    var rssi: Int?
    var classification: String?
    var boundaryGrace: Bool?
    var captureLatencyMs: Int64?
    var success: Bool?
    var errorCode: String?
    var errorMessage: String?
}

struct LabSummary: Equatable {
    var captureAttempts: Int = 0
    var captureSuccesses: Int = 0
    var captureFailures: Int = 0
    var successPercent: Double?
    var captureMeanMs: Double?
    var captureP50Ms: Int64?
    var captureP95Ms: Int64?
    var captureP99Ms: Int64?
    var transitionCount: Int = 0
    var transitionMeanMs: Double?
    var transitionP50Ms: Int64?
    var transitionP95Ms: Int64?
    var missedWindows: Int64?
    var missedWindowsEstimated: Bool = false
    var currentObservations: Int = 0
    var previousObservations: Int = 0
    var nextObservations: Int = 0
    var otherObservations: Int = 0
    var boundaryGraceObservations: Int = 0
    var emitterErrors: Int = 0
    var receiverErrors: Int = 0
    var recoveries: Int = 0
}

final class RunningStats {
    private var captureLatencies: [Int64] = []
    private var captureAttempts = 0
    private var captureSuccesses = 0
    private var transitionLatencies: [Int64] = []
    private var activeCode: Int64?
    private var activeAt: Int64?
    private var seen = Set<Int64>()
    private var firstWall: Int64?
    private var lastWall: Int64?
    private var periodSeconds = 0
    private var current = 0
    private var previous = 0
    private var next = 0
    private var other = 0
    private var grace = 0
    private var emitterErrors = 0
    private var receiverErrors = 0
    private var recoveries = 0

    func absorb(_ event: SessionEvent) {
        switch event.eventType {
        case EventTypes.observation:
            absorbObservation(event)
        case EventTypes.captureAttempt:
            captureAttempts += 1
            if event.success == true {
                captureSuccesses += 1
                if let latency = event.captureLatencyMs {
                    captureLatencies.append(latency)
                }
            }
        case EventTypes.advertiseStartFailed, EventTypes.advertiseDataFailed,
             EventTypes.scanFailed, EventTypes.permissionMissing, EventTypes.permissionRevoked,
             EventTypes.error, EventTypes.bluetoothOff:
            countRoleError(event)
        case EventTypes.recovery:
            recoveries += 1
        default:
            break
        }
    }

    func summary() -> LabSummary {
        let mean = Percentiles.mean(captureLatencies)
        let transitionsMean = Percentiles.mean(transitionLatencies)
        let percent = captureAttempts == 0 ? nil : Double(captureSuccesses) * 100.0 / Double(captureAttempts)
        let missed = missedWindows()
        return LabSummary(
            captureAttempts: captureAttempts,
            captureSuccesses: captureSuccesses,
            captureFailures: captureAttempts - captureSuccesses,
            successPercent: percent,
            captureMeanMs: mean,
            captureP50Ms: Percentiles.nearestRank(captureLatencies, percentile: 50),
            captureP95Ms: Percentiles.nearestRank(captureLatencies, percentile: 95),
            captureP99Ms: Percentiles.nearestRank(captureLatencies, percentile: 99),
            transitionCount: transitionLatencies.count,
            transitionMeanMs: transitionsMean,
            transitionP50Ms: Percentiles.nearestRank(transitionLatencies, percentile: 50),
            transitionP95Ms: Percentiles.nearestRank(transitionLatencies, percentile: 95),
            missedWindows: missed,
            missedWindowsEstimated: missed != nil,
            currentObservations: current,
            previousObservations: previous,
            nextObservations: next,
            otherObservations: other,
            boundaryGraceObservations: grace,
            emitterErrors: emitterErrors,
            receiverErrors: receiverErrors,
            recoveries: recoveries
        )
    }

    private func absorbObservation(_ event: SessionEvent) {
        guard let code = event.code else { return }
        switch event.classification {
        case CodeClass.current.rawValue: current += 1
        case CodeClass.previous.rawValue: previous += 1
        case CodeClass.next.rawValue: next += 1
        case CodeClass.other.rawValue: other += 1
        default: break
        }
        if event.boundaryGrace == true { grace += 1 }
        seen.insert(code & 0xFFFFFFFF)
        if firstWall == nil || event.wallEpochMs < firstWall! { firstWall = event.wallEpochMs }
        if lastWall == nil || event.wallEpochMs > lastWall! { lastWall = event.wallEpochMs }
        if periodSeconds == 0 { periodSeconds = event.periodSeconds }
        guard let active = activeCode, let at = activeAt else {
            activeCode = code
            activeAt = event.monotonicMs
            return
        }
        if code == active { return }
        if code > active {
            transitionLatencies.append(event.monotonicMs - at)
            activeCode = code
            activeAt = event.monotonicMs
        }
    }

    private func missedWindows() -> Int64? {
        guard let startWall = firstWall, let endWall = lastWall else { return 0 }
        if periodSeconds <= 0 { return nil }
        let first = IdentifierSchedule.windowIndex(startWall / 1000, periodSeconds: periodSeconds)
        let last = IdentifierSchedule.windowIndex(endWall / 1000, periodSeconds: periodSeconds)
        return WindowGap.estimatedMissed(seen: seen, firstIndex: first, lastIndex: last)
    }

    private func countRoleError(_ event: SessionEvent) {
        if event.role == "emitter" {
            emitterErrors += 1
        } else {
            receiverErrors += 1
        }
    }
}

enum SessionExport {
    static let columns = [
        "session_id", "role", "manufacturer", "model", "os_version", "app_version",
        "protocol", "advertise_method", "period_seconds", "interval_ms", "connectable",
        "event_type", "monotonic_ms", "wall_time_utc", "wall_epoch_ms", "code", "major",
        "minor", "rssi", "classification", "boundary_grace", "capture_latency_ms",
        "success", "error_code", "error_message",
    ]

    static var csvHeader: String { columns.joined(separator: ",") }

    static func csv(_ events: [SessionEvent]) -> String {
        var text = csvHeader + "\n"
        for event in events {
            text += event.csvRow() + "\n"
        }
        return text
    }

    static func json(events: [SessionEvent], summary: LabSummary) -> String {
        var text = ""
        writeJson(summary: summary, eventLines: events.map { $0.jsonLine() }, output: &text)
        return text
    }

    static func writeJson<Lines: Sequence>(summary: LabSummary, eventLines: Lines, output: inout String) where Lines.Element == String {
        output += "{\"schema\":\(jsonString(LabConstants.schema)),"
        output += "\"schemaVersion\":\(LabConstants.schemaVersion),"
        output += "\"clockNote\":\(jsonString(LabConstants.clockNote)),"
        output += "\"summary\":\(summary.json()),"
        output += "\"events\":["
        var first = true
        for line in eventLines {
            if line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
            if !first { output += "," }
            first = false
            output += line
        }
        output += "]}"
    }
}

extension SessionEvent {
    func csvRow() -> String {
        let fields = [
            sessionId, role, manufacturer, model, osVersion, appVersion, protocolName,
            advertiseMethod, String(periodSeconds), String(intervalMs), String(connectable),
            eventType, String(monotonicMs), wallTimeUtc, String(wallEpochMs),
            csvOptional(code), csvOptional(major), csvOptional(minor), csvOptional(rssi),
            classification ?? "", csvOptional(boundaryGrace), csvOptional(captureLatencyMs),
            csvOptional(success), errorCode ?? "", errorMessage ?? "",
        ]
        return fields.map(csvField).joined(separator: ",")
    }

    func jsonLine() -> String {
        jsonObject([
            ("session_id", sessionId),
            ("role", role),
            ("manufacturer", manufacturer),
            ("model", model),
            ("os_version", osVersion),
            ("app_version", appVersion),
            ("protocol", protocolName),
            ("advertise_method", advertiseMethod),
            ("period_seconds", periodSeconds),
            ("interval_ms", intervalMs),
            ("connectable", connectable),
            ("event_type", eventType),
            ("monotonic_ms", monotonicMs),
            ("wall_time_utc", wallTimeUtc),
            ("wall_epoch_ms", wallEpochMs),
            ("code", code),
            ("major", major),
            ("minor", minor),
            ("rssi", rssi),
            ("classification", classification),
            ("boundary_grace", boundaryGrace),
            ("capture_latency_ms", captureLatencyMs),
            ("success", success),
            ("error_code", errorCode),
            ("error_message", errorMessage),
        ])
    }
}

extension LabSummary {
    func json() -> String {
        jsonObject([
            ("captureAttempts", captureAttempts),
            ("captureSuccesses", captureSuccesses),
            ("captureFailures", captureFailures),
            ("successPercent", successPercent),
            ("captureMeanMs", captureMeanMs),
            ("captureP50Ms", captureP50Ms),
            ("captureP95Ms", captureP95Ms),
            ("captureP99Ms", captureP99Ms),
            ("transitionCount", transitionCount),
            ("transitionMeanMs", transitionMeanMs),
            ("transitionP50Ms", transitionP50Ms),
            ("transitionP95Ms", transitionP95Ms),
            ("missedWindows", missedWindows),
            ("missedWindowsEstimated", missedWindowsEstimated),
            ("currentObservations", currentObservations),
            ("previousObservations", previousObservations),
            ("nextObservations", nextObservations),
            ("otherObservations", otherObservations),
            ("boundaryGraceObservations", boundaryGraceObservations),
            ("emitterErrors", emitterErrors),
            ("receiverErrors", receiverErrors),
            ("recoveries", recoveries),
        ])
    }
}

func jsonString(_ value: String) -> String {
    var out = "\""
    for scalar in value.unicodeScalars {
        switch scalar {
        case "\\": out += "\\\\"
        case "\"": out += "\\\""
        case "\n": out += "\\n"
        case "\r": out += "\\r"
        case "\t": out += "\\t"
        default:
            if scalar.value < 0x20 {
                out += String(format: "\\u%04x", scalar.value)
            } else {
                out.unicodeScalars.append(scalar)
            }
        }
    }
    out += "\""
    return out
}

func jsonNumber(_ value: Double) -> String {
    if !value.isFinite { return "null" }
    var raw = String(format: "%.4f", locale: Locale(identifier: "en_US_POSIX"), value)
    while raw.contains("."), raw.last == "0" {
        raw.removeLast()
    }
    if raw.last == "." { raw.removeLast() }
    return raw == "-0" ? "0" : raw
}

func jsonValue(_ value: Any?) -> String {
    guard let value else { return "null" }
    let mirror = Mirror(reflecting: value)
    if mirror.displayStyle == .optional {
        if let child = mirror.children.first {
            return jsonValue(child.value)
        }
        return "null"
    }
    switch value {
    case let text as String:
        return jsonString(text)
    case let flag as Bool:
        return flag ? "true" : "false"
    case let number as Int:
        return String(number)
    case let number as Int64:
        return String(number)
    case let number as Double:
        return jsonNumber(number)
    default:
        preconditionFailure("Tipo no exportable")
    }
}

func jsonObject(_ fields: [(String, Any?)]) -> String {
    let body = fields.map { key, value in "\(jsonString(key)):\(jsonValue(value))" }.joined(separator: ",")
    return "{\(body)}"
}

private func csvOptional<T>(_ value: T?) -> String {
    guard let value else { return "" }
    return String(describing: value)
}

private func csvField(_ value: String) -> String {
    let quoted = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
    if !quoted { return value }
    return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
}
