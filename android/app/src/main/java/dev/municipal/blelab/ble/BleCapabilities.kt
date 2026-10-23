package dev.municipal.blelab.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build

data class BleCapabilities(
    val bluetoothSupported: Boolean = false,
    val leSupported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val advertiserAvailable: Boolean = false,
    val multipleAdvertisementReported: Boolean = false,
    val advertisingSetApi: Boolean = false,
    val maxAdvertisingDataLength: Int = 0,
    val extendedAdvertising: Boolean = false,
    val le2m: Boolean = false,
    val periodicAdvertising: Boolean = false,
    val locationServiceEnabled: Boolean = false,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val securityException: Boolean = false,
) {
    val canAdvertise: Boolean get() = advertiserAvailable && bluetoothEnabled && !securityException
    val canUpdateWithoutRestart: Boolean get() = canAdvertise && advertisingSetApi
}

fun probeCapabilities(context: Context): BleCapabilities {
    val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    val le = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val locationOn = locationServiceEnabled(context)
    if (adapter == null) {
        return BleCapabilities(leSupported = le, locationServiceEnabled = locationOn)
    }
    return try {
        val advertiser = adapter.bluetoothLeAdvertiser
        val sdk = Build.VERSION.SDK_INT
        BleCapabilities(
            bluetoothSupported = true,
            leSupported = le,
            bluetoothEnabled = adapter.isEnabled,
            advertiserAvailable = advertiser != null,
            multipleAdvertisementReported = adapter.isMultipleAdvertisementSupported,
            advertisingSetApi = sdk >= 26 && advertiser != null,
            maxAdvertisingDataLength = if (sdk >= 26) adapter.leMaximumAdvertisingDataLength else 31,
            extendedAdvertising = sdk >= 26 && adapter.isLeExtendedAdvertisingSupported,
            le2m = sdk >= 26 && adapter.isLe2MPhySupported,
            periodicAdvertising = sdk >= 26 && adapter.isLePeriodicAdvertisingSupported,
            locationServiceEnabled = locationOn,
            sdkInt = sdk,
        )
    } catch (_: SecurityException) {
        BleCapabilities(
            bluetoothSupported = true,
            leSupported = le,
            locationServiceEnabled = locationOn,
            securityException = true,
        )
    }
}

fun locationServiceEnabled(context: Context): Boolean {
    val location = context.getSystemService(LocationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= 28) {
        location.isLocationEnabled
    } else {
        @Suppress("DEPRECATION")
        location.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
