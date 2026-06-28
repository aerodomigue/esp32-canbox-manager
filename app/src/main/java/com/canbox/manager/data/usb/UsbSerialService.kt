package com.canbox.manager.data.usb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.canbox.manager.MainActivity
import com.canbox.manager.R

/**
 * Foreground service that keeps the process alive while the USB connection is active.
 * USB management is handled by the Koin-injected UsbSerialManager singleton — this
 * service only owns the foreground notification so Android does not kill the process
 * when the app goes to the background (e.g. during a debug session).
 */
class UsbSerialService : Service() {

    companion object {
        private const val CHANNEL_ID = "canbox_usb_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val connected = intent?.getBooleanExtra(EXTRA_CONNECTED, false) ?: false
        val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME)
        startForeground(NOTIFICATION_ID, buildNotification(connected, deviceName))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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

    private fun buildNotification(connected: Boolean, deviceName: String?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (connected) "Connected to ${deviceName ?: "USB Device"}"
                   else "Waiting for USB connection"
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
}
