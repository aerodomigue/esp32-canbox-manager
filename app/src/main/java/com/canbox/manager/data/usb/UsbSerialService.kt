package com.canbox.manager.data.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.canbox.manager.MainActivity
import com.canbox.manager.R
import com.canbox.manager.data.repository.CanBoxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Owns the long-lived USB process while the activity is backgrounded.
 * The actual USB manager and CAN log writer are application-scoped through
 * CanBoxRepository; this service keeps that process eligible to stay alive.
 */
class UsbSerialService : Service() {

    companion object {
        private const val CHANNEL_ID = "canbox_usb_channel"
        private const val NOTIFICATION_ID = 1
    }

    private val repository: CanBoxRepository by inject()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): UsbSerialService = this@UsbSerialService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("Waiting for USB connection"))
        ensureConnection()
        return START_STICKY
    }

    private fun ensureConnection() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            delay(250)
            repository.connect()

            // If a capture was active before process recreation, wait for the
            // USB permission/connection and resume it without the UI.
            if (repository.isCanLoggingRequested()) {
                repository.connectionState
                    .filter { it.isConnected }
                    .first()
                repository.resumeCanLoggingIfRequested()
            }
        }
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        serviceScope.cancel()
        // Do not release the repository here: the service may be recreated and
        // the repository owns the application-scoped USB/logging resources.
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CANBox USB Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains USB connection to ESP32 CANBox"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CANBox Manager")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(isConnected: Boolean, deviceName: String? = null) {
        val text = if (isConnected) {
            "Connected to ${deviceName ?: "USB Device"}"
        } else {
            "Waiting for USB connection"
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, createNotification(text))
    }
}
