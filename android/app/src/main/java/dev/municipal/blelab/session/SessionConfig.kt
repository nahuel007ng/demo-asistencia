package dev.municipal.blelab.session

import dev.municipal.blelab.ble.BleCapabilities
import dev.municipal.blelab.core.LabConstants
import dev.municipal.blelab.core.LabSummary
import dev.municipal.blelab.core.RunningStats
import dev.municipal.blelab.core.SessionEvent

data class EmitterConfig(
    val protocol: String = "ibeacon",
    val method: String = "restart",
    val periodSeconds: Int = LabConstants.DEFAULT_PERIOD_SECONDS,
    val intervalMs: Int = 100,
    val connectable: Boolean = false,
    val fixedIdentifier: Boolean = false,
    val fixedValueText: String = "1000",
    val measuredPowerText: String = "-59",
) {
    val fixedValue: Long?
        get() = fixedValueText.trim().toLongOrNull()?.and(0xFFFFFFFFL)

    val measuredPower: Int?
        get() = measuredPowerText.trim().toIntOrNull()?.takeIf { it in -128..127 }
}

data class ReceiverConfig(
    val protocol: String = "ibeacon",
    val periodSeconds: Int = LabConstants.DEFAULT_PERIOD_SECONDS,
    val graceSeconds: Int = 0,
    val expectedText: String = "",
    val repeatsText: String = "20",
    val groupWindowText: String = "0",
) {
    val expectedCode: Long?
        get() = expectedText.trim().ifEmpty { null }?.toLongOrNull()?.and(0xFFFFFFFFL)

    val expectedInvalid: Boolean
        get() = expectedText.isNotBlank() && expectedText.trim().toLongOrNull() == null

    val repeats: Int
        get() = repeatsText.trim().toIntOrNull()?.coerceIn(1, 500) ?: 20

    val groupWindowMs: Long
        get() = groupWindowText.trim().toLongOrNull()?.coerceAtLeast(0L) ?: 0L
}

data class LabUiState(
    val capabilities: BleCapabilities = BleCapabilities(),
    val emitter: EmitterConfig = EmitterConfig(),
    val receiver: ReceiverConfig = ReceiverConfig(),
    val emitterDesired: Boolean = false,
    val receiverDesired: Boolean = false,
    val emitterOnAir: Boolean = false,
    val receiverScanning: Boolean = false,
    val capturePhase: String = "idle",
    val captureAttempt: Int = 0,
    val captureRepeats: Int = 0,
    val onAirCode: Long? = null,
    val onAirMajor: Int? = null,
    val onAirMinor: Int? = null,
    val rotationCount: Int = 0,
    val liveCode: Long? = null,
    val liveMajor: Int? = null,
    val liveMinor: Int? = null,
    val liveRssi: Int? = null,
    val liveClassification: String? = null,
    val liveGrace: Boolean = false,
    val livePacketPeriod: Int? = null,
    val secondsRemaining: Int = 0,
    val clockCode: Long? = null,
    val summary: LabSummary = RunningStats().summary(),
    val recentEvents: List<SessionEvent> = emptyList(),
    val status: String = "Listo",
    val sessionDirectory: String? = null,
    val activeRole: String? = null,
)
