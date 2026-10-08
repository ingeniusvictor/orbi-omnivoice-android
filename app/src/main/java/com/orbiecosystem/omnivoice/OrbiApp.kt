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

        // Generated audio belongs only to the active UI session. Preserve a RUNNING inference so
        // the user can leave the app, have Android recreate the UI process, and reconnect to it.
        clearTransientOutputs()
        val snapshot = InferenceJobStore.read(this)
        if (snapshot?.state != InferenceJobStore.STATE_RUNNING) {
            InferenceJobStore.clear(this)
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
