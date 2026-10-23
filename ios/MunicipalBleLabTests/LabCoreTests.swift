import XCTest
@testable import MunicipalBleLab

final class LabCoreTests: XCTestCase {
    func testWindowsMatchTheSharedVectors() throws {
        let file = try fixture("windows.json")
        let cases = file["cases"] as? [[String: Any]] ?? []
        XCTAssertFalse(cases.isEmpty)
        for row in cases {
            let epoch = int64(row, "unixEpochSeconds")
            let period = intValue(row, "periodSeconds")
            let identifier = IdentifierSchedule.ofEpoch(epoch, periodSeconds: period)
            XCTAssertEqual(int64(row, "windowIndex"), identifier.windowIndex, string(row, "name"))
            XCTAssertEqual(int64(row, "identifier"), identifier.identifier)
            XCTAssertEqual(intValue(row, "major"), identifier.major)
            XCTAssertEqual(intValue(row, "minor"), identifier.minor)
            XCTAssertEqual(intValue(row, "secondsRemaining"), IdentifierSchedule.secondsRemaining(epoch, periodSeconds: period))
            XCTAssertEqual(identifier.identifier, (Int64(identifier.major) << 16) | Int64(identifier.minor))
        }
    }

    func testRotationDelayHitsTheUtcBoundary() {
        let boundary: Int64 = 1_699_999_995_000
        XCTAssertEqual(15_000, IdentifierSchedule.millisUntilNextChange(boundary, periodSeconds: 15))
        XCTAssertEqual(14_500, IdentifierSchedule.millisUntilNextChange(boundary + 500, periodSeconds: 15))
        XCTAssertEqual(1, IdentifierSchedule.millisUntilNextChange(boundary - 1, periodSeconds: 15))
    }

    func testIBeaconPayloadMatchesTheFixtureAndFitsLegacy() throws {
        let row = try fixture("ibeacon-payload.json")
        let encoded = IBeaconCodec.encodePayload(
            uuid: string(row, "uuid"),
            major: intValue(row, "major"),
            minor: intValue(row, "minor"),
            measuredPower: intValue(row, "measuredPower")
        )
        XCTAssertEqual(string(row, "hex"), toHex(encoded))
        let decoded = try XCTUnwrap(IBeaconCodec.decodePayload(encoded))
        XCTAssertEqual(LabConstants.ibeaconUUID, decoded.uuid)
        XCTAssertEqual(113333333, decoded.identifier)
        XCTAssertEqual(-59, decoded.measuredPower)
        XCTAssertEqual(intValue(row, "adBytesWithoutFlags"), IBeaconCodec.advertisingBytes(includeFlags: false))
        XCTAssertEqual(intValue(row, "adBytesWithFlags"), IBeaconCodec.advertisingBytes(includeFlags: true))
        XCTAssertLessThanOrEqual(IBeaconCodec.advertisingBytes(includeFlags: true), LabConstants.legacyAdvertisingBytes)
        XCTAssertNil(IBeaconCodec.decodePayload([0x02, 0x15]))
    }

    func testIBeaconFilterKeepsMajorAndMinorOpen() {
        let filter = IBeaconCodec.manufacturerFilter(uuid: LabConstants.ibeaconUUID)
        XCTAssertEqual(LabConstants.appleCompanyId, filter.companyId)
        XCTAssertEqual(0x02, filter.data[0])
        XCTAssertEqual(0x15, filter.data[1])
        XCTAssertEqual(parseUuidBytes(LabConstants.ibeaconUUID), Array(filter.data[2..<18]))
        XCTAssertEqual(0xFF, filter.mask[0])
        XCTAssertEqual(0xFF, filter.mask[17])
        for index in 18..<filter.mask.count {
            XCTAssertEqual(0, filter.mask[index])
        }
    }

    func testCustomPayloadRoundTripAndUnknownVersion() throws {
        let row = try fixture("custom-payload.json")
        let encoded = CustomCodec.encode(
            identifier: int64(row, "identifier"),
            periodSeconds: intValue(row, "periodSeconds"),
            measuredPower: intValue(row, "measuredPower"),
            timeDerived: true
        )
        XCTAssertEqual(string(row, "hex"), toHex(encoded))
        guard case .frame(let frame) = CustomCodec.decode(encoded) else {
            return XCTFail("el payload propio no decodificó")
        }
        XCTAssertEqual(1729, frame.major)
        XCTAssertEqual(21589, frame.minor)
        XCTAssertTrue(frame.timeDerived)
        XCTAssertEqual(intValue(row, "adBytesWithFlags"), CustomCodec.advertisingBytes(includeFlags: true))
        XCTAssertLessThanOrEqual(CustomCodec.advertisingBytes(includeFlags: true), LabConstants.legacyAdvertisingBytes)

        guard case .frame(let fixed) = CustomCodec.decode(CustomCodec.encode(identifier: 7, periodSeconds: 15, measuredPower: -59, timeDerived: false)) else {
            return XCTFail("el identificador fijo no decodificó")
        }
        XCTAssertFalse(fixed.timeDerived)
        XCTAssertEqual(7, fixed.identifier)

        let rejected = CustomCodec.decode(hexToBytes(string(row, "unsupportedVersionHex")))
        guard case .unsupportedVersion(let version) = rejected else {
            return XCTFail("la versión desconocida no fue rechazada")
        }
        XCTAssertEqual(2, version)
        XCTAssertEqual(CustomCodec.decode([1, 2, 3]), .malformed)
    }

    func testClassificationMatchesTheSharedVectors() throws {
        let file = try fixture("classification.json")
        let epoch = int64(file, "unixEpochSeconds")
        let period = intValue(file, "periodSeconds")
        let cases = file["cases"] as? [[String: Any]] ?? []
        for row in cases {
            let result = CodeClassifier.classify(
                code: int64(row, "code"),
                unixEpochSeconds: epoch,
                periodSeconds: period,
                graceSeconds: intValue(row, "graceSeconds")
            )
            XCTAssertEqual(string(row, "classification"), result.base.rawValue)
            XCTAssertEqual(bool(row, "boundaryGrace"), result.boundaryGrace)
        }
    }

    func testUtcFormatterMatchesKnownInstants() {
        XCTAssertEqual("1970-01-01T00:00:00.000Z", formatUtc(0))
        XCTAssertEqual("1970-01-01T00:00:00.001Z", formatUtc(1))
        XCTAssertEqual("1970-01-01T00:00:00.999Z", formatUtc(999))
        XCTAssertEqual("1970-01-02T00:00:00.000Z", formatUtc(86_400_000))
        XCTAssertEqual("2023-11-14T22:13:20.000Z", formatUtc(1_700_000_000_000))
        XCTAssertEqual("2023-11-14T22:13:15.000Z", formatUtc(1_699_999_995_000))
        XCTAssertEqual("1969-12-31T23:59:59.000Z", formatUtc(-1_000))
    }

    func testPercentilesUseNearestRank() throws {
        let file = try fixture("percentiles.json")
        let samples = (file["samples"] as? [Any] ?? []).compactMap { ($0 as? NSNumber)?.int64Value }
        XCTAssertEqual(int64(file, "p50"), Percentiles.nearestRank(samples, percentile: 50))
        XCTAssertEqual(int64(file, "p95"), Percentiles.nearestRank(samples, percentile: 95))
        XCTAssertEqual(int64(file, "p99"), Percentiles.nearestRank(samples, percentile: 99))
        XCTAssertEqual(double(file, "mean"), try XCTUnwrap(Percentiles.mean(samples)), accuracy: 0.0001)
        XCTAssertNil(Percentiles.nearestRank([], percentile: 50))
        XCTAssertEqual(int64(file, "single"), Percentiles.nearestRank([int64(file, "single")], percentile: 99))
        let hundred = Array(Int64(1)...Int64(100))
        XCTAssertEqual(50, Percentiles.nearestRank(hundred, percentile: 50))
        XCTAssertEqual(95, Percentiles.nearestRank(hundred, percentile: 95))
        XCTAssertEqual(99, Percentiles.nearestRank(hundred, percentile: 99))
    }

    func testCaptureSeriesKeepsFailuresAndDoesNotTreatStaleAsSuccess() {
        let runner = CaptureRunner(repeats: 2, durationMs: 5_000, gapMs: 2_000)
        runner.start(nowMs: 0)
        XCTAssertNil(runner.onFrame(nowMs: 100, code: 10, accepted: false))
        let timeout = try? XCTUnwrap(runner.onClock(nowMs: 5_000))
        XCTAssertEqual(false, timeout?.success)
        XCTAssertEqual("not_current", timeout?.reason)
        XCTAssertEqual(Int64(10), timeout?.code)
        XCTAssertNil(timeout?.latencyMs)
        XCTAssertEqual(false, timeout?.seriesComplete)
        XCTAssertEqual(CapturePhase.gap, runner.phase)

        XCTAssertNil(runner.onClock(nowMs: 6_999))
        XCTAssertNil(runner.onClock(nowMs: 7_000))
        XCTAssertEqual(2, runner.attempt)
        XCTAssertEqual(CapturePhase.running, runner.phase)

        let ok = runner.onFrame(nowMs: 7_250, code: 11, accepted: true)
        XCTAssertEqual(true, ok?.success)
        XCTAssertEqual(Int64(250), ok?.latencyMs)
        XCTAssertEqual("ok", ok?.reason)
        XCTAssertEqual(true, ok?.seriesComplete)
        XCTAssertEqual(CapturePhase.done, runner.phase)
        XCTAssertNil(runner.onFrame(nowMs: 8_000, code: 12, accepted: true))
    }

    func testCaptureTimeoutWithoutPacketsIsNoSignal() {
        let runner = CaptureRunner(repeats: 1, durationMs: 5_000, gapMs: 0)
        runner.start(nowMs: 10)
        let outcome = runner.onClock(nowMs: 5_010)
        XCTAssertEqual("no_signal", outcome?.reason)
        XCTAssertNil(outcome?.code)
        XCTAssertEqual(true, outcome?.seriesComplete)
    }

    func testExpectedCodeOverridesTheTimeClassification() {
        let current = Classification(base: .current, boundaryGrace: false)
        let previous = Classification(base: .previous, boundaryGrace: false)
        XCTAssertTrue(CodeClassifier.captureAccepted(code: 5, classification: previous, expectedCode: 5))
        XCTAssertFalse(CodeClassifier.captureAccepted(code: 4, classification: previous, expectedCode: 5))
        XCTAssertTrue(CodeClassifier.captureAccepted(code: 4, classification: current, expectedCode: nil))
        XCTAssertTrue(CodeClassifier.captureAccepted(
            code: 4,
            classification: Classification(base: .previous, boundaryGrace: true),
            expectedCode: nil
        ))
    }

    func testSummaryCountsTransitionsStaleCodesAndEmitterRecovery() throws {
        let stats = RunningStats()
        stats.absorb(observation(code: 113333333, monotonic: 0, wall: 1_699_999_995_000, classification: "current"))
        stats.absorb(observation(code: 113333333, monotonic: 1_000, wall: 1_699_999_996_000, classification: "current"))
        stats.absorb(observation(code: 113333332, monotonic: 1_500, wall: 1_699_999_996_500, classification: "previous"))
        stats.absorb(observation(code: 113333334, monotonic: 15_000, wall: 1_700_000_010_000, classification: "current"))
        stats.absorb(capture(success: true, latency: 100))
        stats.absorb(capture(success: false, latency: nil))
        stats.absorb(baseEvent(EventTypes.advertiseStartFailed, role: "emitter"))
        stats.absorb(baseEvent(EventTypes.recovery, role: "emitter"))
        stats.absorb(baseEvent(EventTypes.scanFailed, role: "receiver"))

        let summary = stats.summary()
        XCTAssertEqual(1, summary.transitionCount)
        XCTAssertEqual(15_000.0, try XCTUnwrap(summary.transitionMeanMs), accuracy: 0.001)
        XCTAssertEqual(3, summary.currentObservations)
        XCTAssertEqual(1, summary.previousObservations)
        XCTAssertEqual(2, summary.captureAttempts)
        XCTAssertEqual(1, summary.captureSuccesses)
        XCTAssertEqual(1, summary.captureFailures)
        XCTAssertEqual(50.0, try XCTUnwrap(summary.successPercent), accuracy: 0.001)
        XCTAssertEqual(Int64(100), summary.captureP50Ms)
        XCTAssertEqual(1, summary.emitterErrors)
        XCTAssertEqual(1, summary.receiverErrors)
        XCTAssertEqual(1, summary.recoveries)
        XCTAssertEqual(Int64(0), summary.missedWindows)
    }

    func testMissedWindowIsEstimatedFromTheReceiverClock() {
        XCTAssertEqual(Int64(1), WindowGap.estimatedMissed(seen: [10, 12], firstIndex: 10, lastIndex: 12))
        XCTAssertNil(WindowGap.estimatedMissed(seen: [10, 12], firstIndex: 0, lastIndex: 20_000))
    }

    func testCsvAndJsonKeepFailuresAndEscapeText() throws {
        var event = baseEvent(EventTypes.error, role: "emitter")
        event.errorCode = "DATA_TOO_LARGE"
        event.errorMessage = "dato, \"demasiado\" grande"
        let stats = RunningStats()
        stats.absorb(event)
        let csv = SessionExport.csv([event])
        XCTAssertTrue(csv.hasPrefix(SessionExport.csvHeader))
        XCTAssertTrue(csv.contains("\"dato, \"\"demasiado\"\" grande\""))

        let jsonText = SessionExport.json(events: [event], summary: stats.summary())
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(jsonText.utf8)) as? [String: Any])
        XCTAssertEqual(LabConstants.schema, json["schema"] as? String)
        XCTAssertEqual(1, (json["schemaVersion"] as? NSNumber)?.intValue)
        let events = json["events"] as? [[String: Any]] ?? []
        XCTAssertEqual("DATA_TOO_LARGE", events.first?["error_code"] as? String)
        let summary = json["summary"] as? [String: Any] ?? [:]
        XCTAssertEqual(1, (summary["emitterErrors"] as? NSNumber)?.intValue)
        XCTAssertEqual(1, (summary["receiverErrors"] as? NSNumber)?.intValue)
        XCTAssertTrue((json["clockNote"] as? String)?.contains("no están sincronizados") == true)
    }

    private func observation(code: Int64, monotonic: Int64, wall: Int64, classification: String) -> SessionEvent {
        var event = baseEvent(EventTypes.observation, role: "receiver")
        event.monotonicMs = monotonic
        event.wallEpochMs = wall
        event.wallTimeUtc = formatUtc(wall)
        event.code = code
        event.major = Int((code >> 16) & 0xFFFF)
        event.minor = Int(code & 0xFFFF)
        event.rssi = -70
        event.classification = classification
        event.boundaryGrace = false
        event.periodSeconds = 15
        return event
    }

    private func capture(success: Bool, latency: Int64?) -> SessionEvent {
        var event = baseEvent(EventTypes.captureAttempt, role: "receiver")
        event.success = success
        event.captureLatencyMs = latency
        return event
    }

    private func baseEvent(_ type: String, role: String) -> SessionEvent {
        SessionEvent(
            sessionId: "s1",
            role: role,
            manufacturer: "lab",
            model: "iphone",
            osVersion: "15",
            appVersion: "0.1.0",
            protocolName: "ibeacon",
            advertiseMethod: "update_set",
            periodSeconds: 15,
            intervalMs: 100,
            connectable: false,
            eventType: type,
            monotonicMs: 0,
            wallTimeUtc: formatUtc(0),
            wallEpochMs: 0
        )
    }
}

private func fixturesRoot() -> URL {
    URL(fileURLWithPath: #file)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appendingPathComponent("protocol")
        .appendingPathComponent("fixtures")
}

private func fixture(_ name: String) throws -> [String: Any] {
    let data = try Data(contentsOf: fixturesRoot().appendingPathComponent(name))
    return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
}

private func string(_ row: [String: Any], _ key: String) -> String {
    row[key] as? String ?? ""
}

private func intValue(_ row: [String: Any], _ key: String) -> Int {
    (row[key] as? NSNumber)?.intValue ?? 0
}

private func int64(_ row: [String: Any], _ key: String) -> Int64 {
    (row[key] as? NSNumber)?.int64Value ?? 0
}

private func double(_ row: [String: Any], _ key: String) -> Double {
    (row[key] as? NSNumber)?.doubleValue ?? 0
}

private func bool(_ row: [String: Any], _ key: String) -> Bool {
    (row[key] as? NSNumber)?.boolValue ?? false
}
