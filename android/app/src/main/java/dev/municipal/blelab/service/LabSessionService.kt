package dev.municipal.blelab.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * El servicio en primer plano se completa cuando la sesión de emisión y recepción
 * queda conectada. Esta clase existe para que el manifiesto compile desde el andamiaje.
 */
class LabSessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
