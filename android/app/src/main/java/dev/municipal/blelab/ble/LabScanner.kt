package dev.municipal.blelab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import dev.municipal.blelab.core.IBeaconCodec
import dev.municipal.blelab.core.LabConstants
import java.util.UUID

class LabScanner(
    private val context: Context,
    private val handler: Handler,
) {
    private var callback: ScanCallback? = null

    @SuppressLint("MissingPermission")
    fun start(
        protocol: String,
        onResult: (ScanResult, stackElapsedMs: Long, callbackElapsedMs: Long) -> Unit,
        onFailed: (Int, String) -> Unit,
    ) {
        stop()
        val scanner = scanner()
        if (scanner == null) {
            onFailed(-1, "NO_SCANNER")
            return
        }
        val filters = listOf(filterFor(protocol))
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .apply {
                if (Build.VERSION.SDK_INT >= 23) {
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                }
                if (Build.VERSION.SDK_INT >= 26) {
                    setLegacy(true)
                }
            }
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val callbackElapsed = android.os.SystemClock.elapsedRealtime()
                val stackElapsed = result.timestampNanos / 1_000_000L
                handler.post { onResult(result, stackElapsed, callbackElapsed) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { onFailed(errorCode, scanErrorName(errorCode)) }
            }
        }
        callback = cb
        try {
            scanner.startScan(filters, settings, cb)
        } catch (error: IllegalArgumentException) {
            callback = null
            onFailed(-1, error.message ?: "ILLEGAL_ARGUMENT")
        } catch (_: SecurityException) {
            callback = null
            onFailed(-1, "SECURITY")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            scanner()?.stopScan(cb)
        } catch (_: SecurityException) {
            // Ya quedó registrado como falta de permiso.
        }
    }

    private fun scanner(): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    private fun filterFor(protocol: String): ScanFilter {
        if (protocol == "ibeacon") {
            val filter = IBeaconCodec.manufacturerFilter(LabConstants.IBEACON_UUID)
            return ScanFilter.Builder()
                .setManufacturerData(filter.companyId, filter.data, filter.mask)
                .build()
        }
        return ScanFilter.Builder()
            .setServiceData(ParcelUuid(UUID.fromString(LabConstants.SERVICE_UUID)), null)
            .build()
    }
}
