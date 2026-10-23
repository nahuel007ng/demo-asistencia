package dev.municipal.blelab.core

enum class CapturePhase { IDLE, RUNNING, GAP, DONE }

data class CaptureOutcome(
    val attempt: Int,
    val success: Boolean,
    val latencyMs: Long?,
    val code: Long?,
    val reason: String,
    val seriesComplete: Boolean,
)

/**
 * Serie de capturas de duración fija. El llamador decide si el código es aceptable
 * y tiene que consultar [onFrame] antes que [onClock] cuando ambos ocurren en el mismo instante.
 */
class CaptureRunner(
    val repeats: Int,
    val durationMs: Long = LabConstants.CAPTURE_DURATION_MS,
    val gapMs: Long = LabConstants.CAPTURE_GAP_MS,
) {
    var phase: CapturePhase = CapturePhase.IDLE
        private set
    var attempt: Int = 0
        private set
    var attemptStartedAt: Long = 0L
        private set

    private var lastCode: Long? = null

    init {
        require(repeats > 0)
        require(durationMs > 0)
        require(gapMs >= 0)
    }

    fun start(nowMs: Long) {
        attempt = 1
        phase = CapturePhase.RUNNING
        attemptStartedAt = nowMs
        lastCode = null
    }

    fun stop() {
        phase = CapturePhase.DONE
    }

    fun onFrame(nowMs: Long, code: Long, accepted: Boolean): CaptureOutcome? {
        if (phase != CapturePhase.RUNNING) return null
        lastCode = code
        if (!accepted) return null
        return finish(nowMs, success = true, reason = "ok")
    }

    fun onClock(nowMs: Long): CaptureOutcome? {
        when (phase) {
            CapturePhase.RUNNING -> {
                if (nowMs - attemptStartedAt >= durationMs) {
                    val reason = if (lastCode == null) "no_signal" else "not_current"
                    return finish(nowMs, success = false, reason = reason)
                }
            }
            CapturePhase.GAP -> {
                if (nowMs - attemptStartedAt >= gapMs) {
                    attempt += 1
                    phase = CapturePhase.RUNNING
                    attemptStartedAt = nowMs
                    lastCode = null
                }
            }
            CapturePhase.IDLE, CapturePhase.DONE -> Unit
        }
        return null
    }

    private fun finish(nowMs: Long, success: Boolean, reason: String): CaptureOutcome {
        val complete = attempt >= repeats
        val outcome = CaptureOutcome(
            attempt = attempt,
            success = success,
            latencyMs = if (success) nowMs - attemptStartedAt else null,
            code = lastCode,
            reason = reason,
            seriesComplete = complete,
        )
        if (complete) {
            phase = CapturePhase.DONE
        } else {
            phase = CapturePhase.GAP
            attemptStartedAt = nowMs
            lastCode = null
        }
        return outcome
    }
}
