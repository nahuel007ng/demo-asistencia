package dev.municipal.blelab.core

object WindowGap {
    /** null cuando el rango es demasiado grande para estimarlo con el reloj local. */
    fun estimatedMissed(seen: Set<Long>, firstIndex: Long, lastIndex: Long): Long? {
        if (lastIndex < firstIndex) return 0
        val span = lastIndex - firstIndex + 1
        if (span > 20_000) return null
        var missed = 0L
        var window = firstIndex
        while (window <= lastIndex) {
            if ((window and 0xFFFFFFFFL) !in seen) missed++
            window++
        }
        return missed
    }
}

class RunningStats {
    private val captureLatencies = mutableListOf<Long>()
    private var captureAttempts = 0
    private var captureSuccesses = 0
    private val transitionLatencies = mutableListOf<Long>()
    private var activeCode: Long? = null
    private var activeAt: Long? = null
    private val seen = HashSet<Long>()
    private var firstWall: Long? = null
    private var lastWall: Long? = null
    private var periodSeconds: Int = 0
    private var current = 0
    private var previous = 0
    private var next = 0
    private var other = 0
    private var grace = 0
    private var emitterErrors = 0
    private var receiverErrors = 0
    private var recoveries = 0

    fun absorb(event: SessionEvent) {
        when (event.eventType) {
            EventTypes.OBSERVATION -> absorbObservation(event)
            EventTypes.CAPTURE_ATTEMPT -> {
                captureAttempts++
                if (event.success == true) {
                    captureSuccesses++
                    event.captureLatencyMs?.let { captureLatencies.add(it) }
                }
            }
            EventTypes.ADVERTISE_START_FAILED,
            EventTypes.ADVERTISE_DATA_FAILED,
            -> countRoleError(event)
            EventTypes.SCAN_FAILED,
            EventTypes.PERMISSION_MISSING,
            EventTypes.PERMISSION_REVOKED,
            EventTypes.ERROR,
            EventTypes.BLUETOOTH_OFF,
            -> countRoleError(event)
            EventTypes.RECOVERY -> recoveries++
        }
    }

    fun summary(): LabSummary {
        val mean = Percentiles.mean(captureLatencies)
        val transitionsMean = Percentiles.mean(transitionLatencies)
        val percent = if (captureAttempts == 0) null else captureSuccesses * 100.0 / captureAttempts
        val missed = missedWindows()
        return LabSummary(
            captureAttempts = captureAttempts,
            captureSuccesses = captureSuccesses,
            captureFailures = captureAttempts - captureSuccesses,
            successPercent = percent,
            captureMeanMs = mean,
            captureP50Ms = Percentiles.nearestRank(captureLatencies, 50.0),
            captureP95Ms = Percentiles.nearestRank(captureLatencies, 95.0),
            captureP99Ms = Percentiles.nearestRank(captureLatencies, 99.0),
            transitionCount = transitionLatencies.size,
            transitionMeanMs = transitionsMean,
            transitionP50Ms = Percentiles.nearestRank(transitionLatencies, 50.0),
            transitionP95Ms = Percentiles.nearestRank(transitionLatencies, 95.0),
            missedWindows = missed,
            missedWindowsEstimated = missed != null,
            currentObservations = current,
            previousObservations = previous,
            nextObservations = next,
            otherObservations = other,
            boundaryGraceObservations = grace,
            emitterErrors = emitterErrors,
            receiverErrors = receiverErrors,
            recoveries = recoveries,
        )
    }

    private fun absorbObservation(event: SessionEvent) {
        val code = event.code ?: return
        when (event.classification) {
            CodeClass.CURRENT.wire -> current++
            CodeClass.PREVIOUS.wire -> previous++
            CodeClass.NEXT.wire -> next++
            CodeClass.OTHER.wire -> other++
        }
        if (event.boundaryGrace == true) grace++
        seen.add(code and 0xFFFFFFFFL)
        if (firstWall == null || event.wallEpochMs < firstWall!!) firstWall = event.wallEpochMs
        if (lastWall == null || event.wallEpochMs > lastWall!!) lastWall = event.wallEpochMs
        if (periodSeconds == 0) periodSeconds = event.periodSeconds
        val active = activeCode
        val at = activeAt
        if (active == null || at == null) {
            activeCode = code
            activeAt = event.monotonicMs
            return
        }
        if (code == active) return
        if (code > active) {
            transitionLatencies.add(event.monotonicMs - at)
            activeCode = code
            activeAt = event.monotonicMs
        }
    }

    private fun missedWindows(): Long? {
        val startWall = firstWall ?: return 0
        val endWall = lastWall ?: return 0
        val period = periodSeconds
        if (period <= 0) return null
        val first = IdentifierSchedule.windowIndex(startWall / 1000, period)
        val last = IdentifierSchedule.windowIndex(endWall / 1000, period)
        return WindowGap.estimatedMissed(seen, first, last)
    }

    private fun countRoleError(event: SessionEvent) {
        if (event.role == "emitter") emitterErrors++ else receiverErrors++
    }
}
