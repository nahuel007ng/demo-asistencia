package dev.municipal.blelab.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementTest {
    @Test
    fun percentilesUseNearestRank() {
        val file = fixture("percentiles.json")
        val samples = file.getJSONArray("samples").let { array ->
            (0 until array.length()).map { array.getLong(it) }
        }
        assertEquals(file.getLong("p50"), Percentiles.nearestRank(samples, 50.0))
        assertEquals(file.getLong("p95"), Percentiles.nearestRank(samples, 95.0))
        assertEquals(file.getLong("p99"), Percentiles.nearestRank(samples, 99.0))
        assertEquals(file.getDouble("mean"), Percentiles.mean(samples)!!, 0.0001)
        assertNull(Percentiles.nearestRank(emptyList(), 50.0))
        assertEquals(file.getLong("single"), Percentiles.nearestRank(listOf(file.getLong("single")), 99.0))

        val hundred = (1L..100L).toList()
        assertEquals(50L, Percentiles.nearestRank(hundred, 50.0))
        assertEquals(95L, Percentiles.nearestRank(hundred, 95.0))
        assertEquals(99L, Percentiles.nearestRank(hundred, 99.0))
    }

    @Test
    fun captureSeriesKeepsFailuresAndDoesNotTreatStaleAsSuccess() {
        val runner = CaptureRunner(repeats = 2, durationMs = 5_000, gapMs = 2_000)
        runner.start(0)
        assertNull(runner.onFrame(100, code = 10, accepted = false))
        val timeout = runner.onClock(5_000)!!
        assertEquals(false, timeout.success)
        assertEquals("not_current", timeout.reason)
        assertEquals(10L, timeout.code)
        assertNull(timeout.latencyMs)
        assertEquals(false, timeout.seriesComplete)
        assertEquals(CapturePhase.GAP, runner.phase)

        assertNull(runner.onClock(6_999))
        assertNull(runner.onClock(7_000))
        assertEquals(2, runner.attempt)
        assertEquals(CapturePhase.RUNNING, runner.phase)

        val ok = runner.onFrame(7_250, code = 11, accepted = true)!!
        assertEquals(true, ok.success)
        assertEquals(250L, ok.latencyMs)
        assertEquals("ok", ok.reason)
        assertTrue(ok.seriesComplete)
        assertEquals(CapturePhase.DONE, runner.phase)
        assertNull(runner.onFrame(8_000, code = 12, accepted = true))
    }

    @Test
    fun captureTimeoutWithoutPacketsIsNoSignal() {
        val runner = CaptureRunner(repeats = 1, durationMs = 5_000, gapMs = 0)
        runner.start(10)
        val outcome = runner.onClock(5_010)!!
        assertEquals("no_signal", outcome.reason)
        assertNull(outcome.code)
        assertTrue(outcome.seriesComplete)
    }

    @Test
    fun expectedCodeOverridesTheTimeClassification() {
        val current = Classification(CodeClass.CURRENT, boundaryGrace = false)
        val previous = Classification(CodeClass.PREVIOUS, boundaryGrace = false)
        assertTrue(CodeClassifier.captureAccepted(5, previous, expectedCode = 5))
        assertTrue(!CodeClassifier.captureAccepted(4, previous, expectedCode = 5))
        assertTrue(CodeClassifier.captureAccepted(4, current, expectedCode = null))
        assertTrue(
            CodeClassifier.captureAccepted(
                4,
                Classification(CodeClass.PREVIOUS, boundaryGrace = true),
                expectedCode = null,
            ),
        )
    }

    @Test
    fun summaryCountsTransitionsStaleCodesAndEmitterRecovery() {
        val stats = RunningStats()
        stats.absorb(observation(code = 113333333, monotonic = 0, wall = 1_699_999_995_000, classification = "current"))
        stats.absorb(observation(code = 113333333, monotonic = 1_000, wall = 1_699_999_996_000, classification = "current"))
        stats.absorb(observation(code = 113333332, monotonic = 1_500, wall = 1_699_999_996_500, classification = "previous"))
        stats.absorb(observation(code = 113333334, monotonic = 15_000, wall = 1_700_000_010_000, classification = "current"))
        stats.absorb(capture(success = true, latency = 100))
        stats.absorb(capture(success = false, latency = null))
        stats.absorb(baseEvent(EventTypes.ADVERTISE_START_FAILED, role = "emitter"))
        stats.absorb(baseEvent(EventTypes.RECOVERY, role = "emitter"))
        stats.absorb(baseEvent(EventTypes.SCAN_FAILED, role = "receiver"))

        val summary = stats.summary()
        assertEquals(1, summary.transitionCount)
        assertEquals(15_000.0, summary.transitionMeanMs!!, 0.001)
        assertEquals(3, summary.currentObservations)
        assertEquals(1, summary.previousObservations)
        assertEquals(2, summary.captureAttempts)
        assertEquals(1, summary.captureSuccesses)
        assertEquals(1, summary.captureFailures)
        assertEquals(50.0, summary.successPercent!!, 0.001)
        assertEquals(100L, summary.captureP50Ms)
        assertEquals(1, summary.emitterErrors)
        assertEquals(1, summary.receiverErrors)
        assertEquals(1, summary.recoveries)
        assertEquals(0L, summary.missedWindows)
    }

    @Test
    fun missedWindowIsEstimatedFromTheReceiverClock() {
        val seen = setOf(10L, 12L)
        assertEquals(1L, WindowGap.estimatedMissed(seen, firstIndex = 10, lastIndex = 12))
        assertNull(WindowGap.estimatedMissed(seen, firstIndex = 0, lastIndex = 20_000))
    }

    @Test
    fun csvAndJsonKeepFailuresAndEscapeText() {
        val event = baseEvent(EventTypes.ERROR, role = "emitter").copy(
            errorCode = "DATA_TOO_LARGE",
            errorMessage = "dato, \"demasiado\" grande",
        )
        val stats = RunningStats().also { it.absorb(event) }
        val csv = SessionExport.csv(listOf(event))
        assertTrue(csv.startsWith(SessionExport.csvHeader))
        assertTrue(csv.contains("\"dato, \"\"demasiado\"\" grande\""))

        val json = JSONObject(SessionExport.json(listOf(event), stats.summary()))
        assertEquals(LabConstants.SCHEMA, json.getString("schema"))
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals("DATA_TOO_LARGE", json.getJSONArray("events").getJSONObject(0).getString("error_code"))
        assertEquals(1, json.getJSONObject("summary").getInt("emitterErrors"))
        assertTrue(json.getString("clockNote").contains("no están sincronizados"))
    }

    private fun observation(
        code: Long,
        monotonic: Long,
        wall: Long,
        classification: String,
    ) = baseEvent(EventTypes.OBSERVATION, role = "receiver").copy(
        monotonicMs = monotonic,
        wallEpochMs = wall,
        wallTimeUtc = formatUtc(wall),
        code = code,
        major = (code shr 16).toInt(),
        minor = (code and 0xFFFF).toInt(),
        rssi = -70,
        classification = classification,
        boundaryGrace = false,
        periodSeconds = 15,
    )

    private fun capture(success: Boolean, latency: Long?) =
        baseEvent(EventTypes.CAPTURE_ATTEMPT, role = "receiver").copy(
            success = success,
            captureLatencyMs = latency,
        )

    private fun baseEvent(type: String, role: String) = SessionEvent(
        sessionId = "s1",
        role = role,
        manufacturer = "lab",
        model = "pixel",
        osVersion = "14",
        appVersion = "0.1.0",
        protocol = "ibeacon",
        advertiseMethod = "update_set",
        periodSeconds = 15,
        intervalMs = 100,
        connectable = false,
        eventType = type,
        monotonicMs = 0,
        wallTimeUtc = formatUtc(0),
        wallEpochMs = 0,
    )
}
