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
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInferenceService : Service() {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "orbi-voice-inference").apply { priority = Thread.NORM_PRIORITY }
    }
    private val busy = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action != ACTION_CLONE && action != ACTION_TTS) return START_NOT_STICKY

        if (!busy.compareAndSet(false, true)) {
            publishState(
                jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty(),
                kind = if (action == ACTION_CLONE) InferenceJobStore.KIND_CLONE else InferenceJobStore.KIND_TTS,
                state = InferenceJobStore.STATE_ERROR,
                message = "Ya existe una inferencia activa. Espera a que termine antes de iniciar otra.",
                outputPath = null
            )
            return START_NOT_STICKY
        }

        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()
        val kind = if (action == ACTION_CLONE) InferenceJobStore.KIND_CLONE else InferenceJobStore.KIND_TTS
        val initial = if (kind == InferenceJobStore.KIND_CLONE) {
            "ORBI Voice · clonación iniciada en segundo plano"
        } else {
            "ORBI Voice · TTS iniciado en segundo plano"
        }

        acquireCpuWakeLock()
        promote(initial)
        publishState(jobId, kind, InferenceJobStore.STATE_RUNNING, initial, null)

        worker.execute {
            runJob(intent, jobId, kind)
        }
        return START_NOT_STICKY
    }

    private fun runJob(intent: Intent, jobId: String, kind: String) {
        val backend = Backend.valueOf(intent.getStringExtra(EXTRA_BACKEND) ?: "CPU")
        val steps = intent.getIntExtra(EXTRA_STEPS, 32)
        val seconds = intent.getFloatExtra(EXTRA_SECONDS, 2f)
        val target = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        val refPath = intent.getStringExtra(EXTRA_REF_PATH)
        val refText = intent.getStringExtra(EXTRA_REF_TEXT).orEmpty()
        val stamp = System.currentTimeMillis()
        val raw = File(filesDir, "outputs/raw_orbi_voice_${kind.lowercase()}_$stamp.wav")
        val finalOut = File(
            filesDir,
            if (kind == InferenceJobStore.KIND_CLONE) {
                "outputs/orbi_omnivoice_$stamp.wav"
            } else {
                "outputs/auto_voice_$stamp.wav"
            }
        )
        raw.parentFile?.mkdirs()

        val local = OmniVoiceEngine(filesDir, backend) { line ->
            publishState(jobId, kind, InferenceJobStore.STATE_RUNNING, line, null)
            if (line.contains("step", ignoreCase = true)) {
                promote("ORBI Voice · $line")
            }
        }

        try {
            val summary = if (kind == InferenceJobStore.KIND_CLONE) {
                val ref = refPath?.let(::File)
                    ?: error("No se recibió referencia de voz")
                require(ref.isFile) { "La referencia de voz ya no existe" }
                val stats = local.generate(ref, refText, target, steps, seconds, raw)
                val cleanSeconds = postProcess(raw, finalOut)
                "CLONE SUCCESS · ${stats.steps} steps · total=${stats.totalMs} ms · clean=%.2f s".format(cleanSeconds)
            } else {
                val stats = local.generateAutoVoice(target, steps, seconds, raw)
                val cleanSeconds = postProcess(raw, finalOut)
                "TTS SUCCESS · ${stats.steps} steps · total=${stats.totalMs} ms · clean=%.2f s".format(cleanSeconds)
            }

            publishState(jobId, kind, InferenceJobStore.STATE_COMPLETED, summary, finalOut.absolutePath)
            promote("ORBI Voice · generación completada")
        } catch (t: Throwable) {
            raw.delete()
            publishState(
                jobId,
                kind,
                InferenceJobStore.STATE_ERROR,
                "INFERENCE ERROR: ${t.javaClass.simpleName}: ${t.message}",
                null
            )
            promote("ORBI Voice · error de inferencia")
        } finally {
            try { local.close() } catch (_: Throwable) {}
            System.gc()
            busy.set(false)
            releaseCpuWakeLock()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun postProcess(raw: File, finalFile: File): Float {
        val wav = WavIO.readPcm16(raw)
        val clean = AudioPostProcessor.process(wav.samples, wav.sampleRate)
        WavIO.writePcm16(finalFile, clean, wav.sampleRate)
        raw.delete()
        return clean.size.toFloat() / wav.sampleRate
    }

    private fun publishState(
        jobId: String,
        kind: String,
        state: String,
        message: String,
        outputPath: String?
    ) {
        try {
            InferenceJobStore.write(
                this,
                InferenceSnapshot(jobId, kind, state, message, outputPath, System.currentTimeMillis())
            )
        } catch (_: Throwable) {
        }
    }

    private fun acquireCpuWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:OrbiVoiceDedicatedInference"
        ).apply {
            setReferenceCounted(false)
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

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "ORBI Voice · generación",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la generación local activa aunque ORBI Voice no esté visible"
                setShowBadge(false)
            }
        )
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

    override fun onDestroy() {
        releaseCpuWakeLock()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_CLONE = "com.orbiecosystem.omnivoice.action.CLONE"
        private const val ACTION_TTS = "com.orbiecosystem.omnivoice.action.TTS"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_BACKEND = "backend"
        private const val EXTRA_STEPS = "steps"
        private const val EXTRA_SECONDS = "seconds"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_REF_PATH = "ref_path"
        private const val EXTRA_REF_TEXT = "ref_text"
        private const val CHANNEL_ID = "orbi_voice_generation"
        private const val NOTIFICATION_ID = 2410
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60L * 1000L

        fun startClone(
            context: Context,
            refPath: String,
            refText: String,
            target: String,
            backend: Backend,
            steps: Int,
            seconds: Float
        ): String {
            val jobId = UUID.randomUUID().toString()
            val intent = Intent(context, VoiceInferenceService::class.java).apply {
                action = ACTION_CLONE
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_REF_PATH, refPath)
                putExtra(EXTRA_REF_TEXT, refText)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_BACKEND, backend.name)
                putExtra(EXTRA_STEPS, steps)
                putExtra(EXTRA_SECONDS, seconds)
            }
            context.startForegroundService(intent)
            return jobId
        }

        fun startTts(
            context: Context,
            target: String,
            backend: Backend,
            steps: Int,
            seconds: Float
        ): String {
            val jobId = UUID.randomUUID().toString()
            val intent = Intent(context, VoiceInferenceService::class.java).apply {
                action = ACTION_TTS
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_BACKEND, backend.name)
                putExtra(EXTRA_STEPS, steps)
                putExtra(EXTRA_SECONDS, seconds)
            }
            context.startForegroundService(intent)
            return jobId
        }
    }
}
