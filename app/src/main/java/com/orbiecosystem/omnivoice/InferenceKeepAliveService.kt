package com.orbiecosystem.omnivoice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class InferenceKeepAliveService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        promote("ORBI OmniVoice activo · inferencia protegida en segundo plano")
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: "ORBI OmniVoice activo · inferencia protegida en segundo plano"
        promote(message)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun promote(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ORBI OmniVoice inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene la inferencia local activa cuando cambias de aplicación"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("ORBI OmniVoice Edge Lab")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "orbi_omnivoice_inference"
        private const val NOTIFICATION_ID = 2307
        private const val EXTRA_MESSAGE = "message"

        @Volatile
        private var running = false

        fun start(context: Context) {
            try {
                val intent = Intent(context, InferenceKeepAliveService::class.java)
                context.startForegroundService(intent)
            } catch (_: Throwable) {
                // The app remains usable even if an OEM temporarily rejects FGS startup.
            }
        }

        fun update(context: Context, message: String) {
            try {
                val intent = Intent(context, InferenceKeepAliveService::class.java)
                    .putExtra(EXTRA_MESSAGE, message)
                context.startForegroundService(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
