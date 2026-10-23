package dev.municipal.blelab.ble

import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.ScanCallback

fun advertiseErrorName(code: Int): String = when (code) {
    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
    -1 -> "SECURITY"
    else -> "UNKNOWN_$code"
}

fun scanErrorName(code: Int): String = when (code) {
    ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
    ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "REGISTRATION_FAILED"
    ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
    ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
    ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "OUT_OF_HARDWARE_RESOURCES"
    ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "SCANNING_TOO_FREQUENTLY"
    else -> "UNKNOWN_$code"
}
