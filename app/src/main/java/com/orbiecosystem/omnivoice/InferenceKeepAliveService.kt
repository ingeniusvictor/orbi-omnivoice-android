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
import android.os.PowerManager

class InferenceKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var inferenceActive = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        promote("ORBI Voice activo · inferencia protegida en segundo plano")
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: "ORBI Voice activo · inferencia protegida en segundo plano"

        val shouldHoldCpu = isInferenceMessage(message)
        if (shouldHoldCpu) {
            inferenceActive = true
            acquireCpuWakeLock()
        } else if (isIdleMessage(message)) {
            inferenceActive = false
            releaseCpuWakeLock()
        }

        promote(message)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do NOT stop the service while inference is active. On HyperOS/Android, the task/UI can
        // disappear or the display can lock while a long CPU inference is still running.
        if (!inferenceActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseCpuWakeLock()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireCpuWakeLock() {
        val existing = wakeLock
        if (existing?.isHeld == true) return

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:OrbiVoiceInference"
        ).apply {
            setReferenceCounted(false)
            // Safety timeout. A normal clone on the POCO is only a few minutes, but allow enough
            // headroom for long text / reduced CPU frequency with the screen off.
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseCpuWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        } finally {
            wakeLock = null
        }
    }

    private fun isInferenceMessage(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("clonando") || m.contains("generando tts") || m.contains("inferencia en curso")
    }

    private fun isIdleMessage(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("· listo") || m.endsWith("listo") || m.contains("inferencia finalizada")
    }

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
            "ORBI Voice inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene activa la inferencia local incluso con la pantalla apagada"
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
            .setContentTitle("ORBI Voice")
            .setContentText(message)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        // Keep the existing channel ID so Android treats this as an update instead of creating a duplicate channel.
        private const val CHANNEL_ID = "orbi_omnivoice_inference"
        private const val NOTIFICATION_ID = 2307
        private const val EXTRA_MESSAGE = "message"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L

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
