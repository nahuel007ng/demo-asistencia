package dev.municipal.blelab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import dev.municipal.blelab.core.AdvertiseIntervals

interface LabAdvertiser {
    fun start(data: AdvertiseData, connectable: Boolean, intervalMs: Int, listener: Listener)
    fun update(data: AdvertiseData)
    fun stop()

    interface Listener {
        fun onStarted()
        fun onStartFailed(code: Int, name: String)
        fun onDataUpdated()
        fun onDataFailed(code: Int, name: String)
        fun onStopped()
    }
}

class RestartAdvertiser(
    private val context: Context,
    private val handler: Handler,
) : LabAdvertiser {
    private var listener: LabAdvertiser.Listener? = null
    private var settings: AdvertiseSettings? = null
    private var callback: AdvertiseCallback? = null
    private var desired: AdvertiseData? = null
    private var state = State.IDLE
    private var dirty = false
    private var retryUsed = false
    private var rotation = false

    override fun start(data: AdvertiseData, connectable: Boolean, intervalMs: Int, listener: LabAdvertiser.Listener) {
        onThread {
            this.listener = listener
            settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(mode(intervalMs))
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(connectable)
                .setTimeout(0)
                .build()
            desired = data
            dirty = false
            retryUsed = false
            rotation = false
            beginStart()
        }
    }

    override fun update(data: AdvertiseData) {
        onThread {
            desired = data
            when (state) {
                State.UP -> {
                    dirty = false
                    rotation = true
                    stopHardware()
                    state = State.ROTATING
                    handler.postDelayed({
                        if (state == State.ROTATING) beginStart()
                    }, RESTART_GAP_MS)
                }
                State.STARTING, State.ROTATING -> dirty = true
                State.IDLE -> Unit
            }
        }
    }

    override fun stop() {
        onThread {
            state = State.IDLE
            desired = null
            dirty = false
            stopHardware()
            listener?.onStopped()
            listener = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginStart() {
        val advertiser = advertiser()
        val data = desired
        val settings = settings
        if (advertiser == null || data == null || settings == null) {
            state = State.IDLE
            listener?.onStartFailed(-1, "NO_ADVERTISER")
            return
        }
        val cb = callback ?: object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                onThread {
                    if (state == State.IDLE || listener == null) return@onThread
                    retryUsed = false
                    val wasRotation = rotation
                    rotation = false
                    state = State.UP
                    if (wasRotation) listener?.onDataUpdated() else listener?.onStarted()
                    if (dirty) {
                        dirty = false
                        update(desired ?: return@onThread)
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                onThread {
                    if (state == State.IDLE) return@onThread
                    listener?.onStartFailed(errorCode, advertiseErrorName(errorCode))
                    if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED && !retryUsed && state != State.IDLE) {
                        retryUsed = true
                        stopHardware()
                        handler.postDelayed({
                            if (state != State.IDLE) beginStart()
                        }, RETRY_GAP_MS)
                    } else {
                        state = State.IDLE
                    }
                }
            }
        }.also { callback = it }
        state = if (rotation) State.ROTATING else State.STARTING
        try {
            advertiser.startAdvertising(settings, data, cb)
        } catch (error: IllegalArgumentException) {
            state = State.IDLE
            listener?.onStartFailed(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE, error.message ?: "DATA_TOO_LARGE")
        } catch (_: SecurityException) {
            state = State.IDLE
            listener?.onStartFailed(-1, "SECURITY")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopHardware() {
        val cb = callback ?: return
        try {
            advertiser()?.stopAdvertising(cb)
        } catch (_: SecurityException) {
            // El registro del controlador ya va a mostrar la falta de permiso.
        }
    }

    private fun advertiser(): BluetoothLeAdvertiser? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser

    private fun onThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }

    private enum class State { IDLE, STARTING, ROTATING, UP }

    private companion object {
        const val RESTART_GAP_MS = 80L
        const val RETRY_GAP_MS = 200L

        fun mode(intervalMs: Int): Int = when (intervalMs) {
            100 -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            250 -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            else -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class UpdateSetAdvertiser(
    private val context: Context,
    private val handler: Handler,
) : LabAdvertiser {
    private var listener: LabAdvertiser.Listener? = null
    private var callback: AdvertisingSetCallback? = null
    private var advertisingSet: AdvertisingSet? = null
    private var pending: AdvertiseData? = null

    @SuppressLint("MissingPermission")
    override fun start(data: AdvertiseData, connectable: Boolean, intervalMs: Int, listener: LabAdvertiser.Listener) {
        onThread {
            this.listener = listener
            pending = null
            val parameters = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(connectable)
                .setScannable(connectable)
                .setInterval(AdvertiseIntervals.toAdvertisingSetUnits(intervalMs))
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val cb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
                    onThread {
                        if (callback != this) return@onThread
                        val current = listener ?: return@onThread
                        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS && set != null) {
                            advertisingSet = set
                            current.onStarted()
                            pending?.let { queued ->
                                pending = null
                                set.setAdvertisingData(queued)
                            }
                        } else {
                            current.onStartFailed(status, advertiseErrorName(status))
                        }
                    }
                }

                override fun onAdvertisingDataSet(set: AdvertisingSet?, status: Int) {
                    onThread {
                        if (callback != this) return@onThread
                        val current = listener ?: return@onThread
                        if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) current.onDataUpdated()
                        else current.onDataFailed(status, advertiseErrorName(status))
                    }
                }

                override fun onAdvertisingSetStopped(set: AdvertisingSet?) {
                    onThread {
                        if (callback != this) return@onThread
                        listener?.onStopped()
                    }
                }
            }
            callback = cb
            val advertiser = advertiser()
            if (advertiser == null) {
                listener.onStartFailed(-1, "NO_ADVERTISER")
                return@onThread
            }
            try {
                advertiser.startAdvertisingSet(parameters, data, null, null, null, cb, handler)
            } catch (error: IllegalArgumentException) {
                listener.onStartFailed(AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE, error.message ?: "DATA_TOO_LARGE")
            } catch (_: SecurityException) {
                listener.onStartFailed(-1, "SECURITY")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun update(data: AdvertiseData) {
        onThread {
            val set = advertisingSet
            if (set == null) {
                pending = data
            } else {
                try {
                    set.setAdvertisingData(data)
                } catch (_: SecurityException) {
                    listener?.onDataFailed(-1, "SECURITY")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun stop() {
        onThread {
            val advertiser = advertiser()
            val cb = callback
            pending = null
            advertisingSet = null
            listener = null
            callback = null
            if (advertiser != null && cb != null) {
                try {
                    advertiser.stopAdvertisingSet(cb)
                } catch (_: SecurityException) {
                    // El controlador registra la falta de permiso por su cuenta.
                }
            }
        }
    }

    private fun advertiser(): BluetoothLeAdvertiser? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser

    private fun onThread(block: () -> Unit) {
        if (Looper.myLooper() == handler.looper) block() else handler.post(block)
    }
}
