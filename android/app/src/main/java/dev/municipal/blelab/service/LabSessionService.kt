package dev.municipal.blelab.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.municipal.blelab.MainActivity
import dev.municipal.blelab.R
import dev.municipal.blelab.session.LabController

class LabSessionService : Service() {
    private val thread = HandlerThread("ble-lab")
    private lateinit var handler: Handler
    private lateinit var controller: LabController

    override fun onCreate() {
        super.onCreate()
        thread.start()
        handler = Handler(thread.looper)
        createChannel()
        startInForeground("Laboratorio BLE en espera")
        controller = LabController(applicationContext, handler, ::startInForeground)
        controller.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = LabBinder()

    override fun onDestroy() {
        if (::controller.isInitialized) controller.shutdown()
        thread.quitSafely()
        super.onDestroy()
    }

    inner class LabBinder : Binder() {
        fun controller(): LabController = this@LabSessionService.controller
    }

    private fun startInForeground(text: String) {
        val notification = notification(text)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_desc)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "ble_lab_session"
        const val NOTIFICATION_ID = 41
    }
}
