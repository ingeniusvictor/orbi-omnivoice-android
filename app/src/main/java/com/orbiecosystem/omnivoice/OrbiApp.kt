package com.orbiecosystem.omnivoice

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OrbiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // Whisper and voice inference run in dedicated processes. Do not execute main-process
        // UI/session setup there.
        if (Application.getProcessName() != packageName) return

        val prefs = getSharedPreferences("orbi_omnivoice_session", MODE_PRIVATE)
        clearTransientOutputs()

        // Preserve cross-process inference state only when it belongs to the job that this UI
        // session explicitly launched. This lets us recover from UI-process death without
        // resurrecting unrelated/old clone results.
        val pendingJob = prefs.getString(KEY_PENDING_JOB, null)
        val snapshot = InferenceJobStore.read(this)
        if (pendingJob.isNullOrBlank() || snapshot == null || snapshot.jobId != pendingJob) {
            InferenceJobStore.clear(this)
            prefs.edit().remove(KEY_PENDING_JOB).apply()
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                clearTransientOutputs()
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun clearTransientOutputs() {
        getSharedPreferences("orbi_omnivoice_session", MODE_PRIVATE)
            .edit()
            .remove("clone_output")
            .remove("auto_output")
            .remove("codec_output")
            .commit()
    }

    companion object {
        private const val KEY_PENDING_JOB = "pending_inference_job"

        lateinit var appContext: android.content.Context
            private set

        // Kept for downloads/imports/diagnostics. Production voice generation now belongs to
        // VoiceInferenceService in the dedicated :inference process.
        val work: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "orbi-voice-ui-worker").apply { priority = Thread.NORM_PRIORITY }
            }
        }
    }
}
