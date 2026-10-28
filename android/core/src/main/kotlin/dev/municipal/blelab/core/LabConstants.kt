package dev.municipal.blelab.core

object LabConstants {
    const val SCHEMA = "blelab.session"
    const val SCHEMA_VERSION = 1
    const val IBEACON_UUID = "7B2E4A10-9C3D-4F58-A1E6-2D8B5C04F917"
    const val SERVICE_UUID = "C4A15E20-6B17-4D3E-9F80-11A2B3C4D5E6"
    const val APPLE_COMPANY_ID = 0x004C
    const val PROTOCOL_VERSION = 1
    const val DEFAULT_MEASURED_POWER = -59
    const val DEFAULT_PERIOD_SECONDS = 15
    const val LEGACY_ADVERTISING_BYTES = 31
    const val CAPTURE_DURATION_MS = 5_000L
    const val CAPTURE_GAP_MS = 2_000L
    const val CLOCK_NOTE =
        "wall_time_utc y wall_epoch_ms de dos dispositivos no están sincronizados. " +
            "capture_latency_ms y los tiempos entre códigos usan el reloj monotónico del dispositivo que midió."

    val allowedPeriods = setOf(10, 15, 30)
    val advertiseIntervalsMs = listOf(100, 250, 1000)
}

object EventTypes {
    const val SESSION_START = "session_start"
    const val SESSION_STOP = "session_stop"
    const val ADVERTISE_STARTED = "advertise_started"
    const val ADVERTISE_START_FAILED = "advertise_start_failed"
    const val ADVERTISE_DATA_SET = "advertise_data_set"
    const val ADVERTISE_DATA_FAILED = "advertise_data_failed"
    const val ADVERTISE_STOPPED = "advertise_stopped"
    const val ROTATION_SCHEDULED = "rotation_scheduled"
    const val ROTATION_APPLIED = "rotation_applied"
    const val BLUETOOTH_OFF = "bluetooth_off"
    const val BLUETOOTH_ON = "bluetooth_on"
    const val PERMISSION_MISSING = "permission_missing"
    const val PERMISSION_REVOKED = "permission_revoked"
    const val RECOVERY = "recovery"
    const val SCAN_STARTED = "scan_started"
    const val SCAN_STOPPED = "scan_stopped"
    const val SCAN_FAILED = "scan_failed"
    const val OBSERVATION = "observation"
    const val CAPTURE_ATTEMPT = "capture_attempt"
    const val UNSUPPORTED_VERSION = "unsupported_version"
    const val ERROR = "error"
    const val QR_SHOWN = "qr_shown"
    const val CHECKIN = "checkin"
}

object LabPermissions {
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val SCAN = "android.permission.BLUETOOTH_SCAN"
    const val ADVERTISE = "android.permission.BLUETOOTH_ADVERTISE"
    const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
    const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val CAMERA = "android.permission.CAMERA"

    fun forCheckIn(sdkInt: Int): List<String> = forReceiver(sdkInt) + CAMERA

    fun forReceiver(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= 31) {
            add(SCAN)
            add(CONNECT)
        }
        add(FINE_LOCATION)
        if (sdkInt >= 31) add(COARSE_LOCATION)
        if (sdkInt >= 33) add(NOTIFICATIONS)
    }

    fun forEmitter(sdkInt: Int): List<String> = buildList {
        if (sdkInt >= 31) {
            add(ADVERTISE)
            add(CONNECT)
        }
        if (sdkInt >= 33) add(NOTIFICATIONS)
    }
}

object AdvertiseIntervals {
    /** Unidades de 0,625 ms que acepta AdvertisingSetParameters.setInterval. El mínimo público es 160. */
    fun toAdvertisingSetUnits(millis: Int): Int = when (millis) {
        100 -> 160
        250 -> 400
        1000 -> 1600
        else -> throw IllegalArgumentException("Intervalo $millis ms no está en el laboratorio")
    }
}
