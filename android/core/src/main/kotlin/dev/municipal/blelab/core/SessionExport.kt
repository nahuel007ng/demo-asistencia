package dev.municipal.blelab.core

object SessionExport {
    val columns = listOf(
        "session_id",
        "role",
        "manufacturer",
        "model",
        "os_version",
        "app_version",
        "protocol",
        "advertise_method",
        "period_seconds",
        "interval_ms",
        "connectable",
        "event_type",
        "monotonic_ms",
        "wall_time_utc",
        "wall_epoch_ms",
        "code",
        "major",
        "minor",
        "rssi",
        "classification",
        "boundary_grace",
        "capture_latency_ms",
        "success",
        "error_code",
        "error_message",
    )

    val csvHeader: String = columns.joinToString(",")

    fun csv(events: List<SessionEvent>): String = buildString {
        append(csvHeader)
        append('\n')
        events.forEach { append(it.toCsvRow()).append('\n') }
    }

    fun json(events: List<SessionEvent>, summary: LabSummary): String = buildString {
        append("{\"schema\":${jsonString(LabConstants.SCHEMA)},")
        append("\"schemaVersion\":${LabConstants.SCHEMA_VERSION},")
        append("\"clockNote\":${jsonString(LabConstants.CLOCK_NOTE)},")
        append("\"summary\":${summary.toJson()},")
        append("\"events\":[")
        events.forEachIndexed { index, event ->
            if (index > 0) append(',')
            append(event.toJsonLine())
        }
        append("]}")
    }
}

fun SessionEvent.toCsvRow(): String = listOf(
    sessionId,
    role,
    manufacturer,
    model,
    osVersion,
    appVersion,
    protocol,
    advertiseMethod,
    periodSeconds.toString(),
    intervalMs.toString(),
    connectable.toString(),
    eventType,
    monotonicMs.toString(),
    wallTimeUtc,
    wallEpochMs.toString(),
    code.csv(),
    major.csv(),
    minor.csv(),
    rssi.csv(),
    classification.orEmpty(),
    boundaryGrace.csv(),
    captureLatencyMs.csv(),
    success.csv(),
    errorCode.orEmpty(),
    errorMessage.orEmpty(),
).joinToString(",") { csvField(it) }

fun SessionEvent.toJsonLine(): String = jsonObject(
    listOf(
        "session_id" to sessionId,
        "role" to role,
        "manufacturer" to manufacturer,
        "model" to model,
        "os_version" to osVersion,
        "app_version" to appVersion,
        "protocol" to protocol,
        "advertise_method" to advertiseMethod,
        "period_seconds" to periodSeconds,
        "interval_ms" to intervalMs,
        "connectable" to connectable,
        "event_type" to eventType,
        "monotonic_ms" to monotonicMs,
        "wall_time_utc" to wallTimeUtc,
        "wall_epoch_ms" to wallEpochMs,
        "code" to code,
        "major" to major,
        "minor" to minor,
        "rssi" to rssi,
        "classification" to classification,
        "boundary_grace" to boundaryGrace,
        "capture_latency_ms" to captureLatencyMs,
        "success" to success,
        "error_code" to errorCode,
        "error_message" to errorMessage,
    ),
)

fun LabSummary.toJson(): String = jsonObject(
    listOf(
        "captureAttempts" to captureAttempts,
        "captureSuccesses" to captureSuccesses,
        "captureFailures" to captureFailures,
        "successPercent" to successPercent,
        "captureMeanMs" to captureMeanMs,
        "captureP50Ms" to captureP50Ms,
        "captureP95Ms" to captureP95Ms,
        "captureP99Ms" to captureP99Ms,
        "transitionCount" to transitionCount,
        "transitionMeanMs" to transitionMeanMs,
        "transitionP50Ms" to transitionP50Ms,
        "transitionP95Ms" to transitionP95Ms,
        "missedWindows" to missedWindows,
        "missedWindowsEstimated" to missedWindowsEstimated,
        "currentObservations" to currentObservations,
        "previousObservations" to previousObservations,
        "nextObservations" to nextObservations,
        "otherObservations" to otherObservations,
        "boundaryGraceObservations" to boundaryGraceObservations,
        "emitterErrors" to emitterErrors,
        "receiverErrors" to receiverErrors,
        "recoveries" to recoveries,
    ),
)

internal fun csvField(value: String): String {
    val quoted = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!quoted) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

private fun Any?.csv(): String = when (this) {
    null -> ""
    is Boolean -> toString()
    else -> toString()
}
