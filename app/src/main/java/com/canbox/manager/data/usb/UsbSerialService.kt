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

class UsbSerialService : Service() {

    companion object {
        private const val CHANNEL_ID = "canbox_usb_channel"
        private const val NOTIFICATION_ID = 1
    }

    private val binder = LocalBinder()
    lateinit var usbManager: UsbSerialManager
        private set

    inner class LocalBinder : Binder() {
        fun getService(): UsbSerialService = this@UsbSerialService
    }

    override fun onCreate() {
        super.onCreate()
        usbManager = UsbSerialManager(applicationContext)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        usbManager.release()
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
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CANBox Manager")
            .setContentText("USB connection active")
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CANBox Manager")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
