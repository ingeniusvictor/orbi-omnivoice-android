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

        // Whisper runs in its own :asr process. Do not execute main-process UI/session setup there.
        if (Application.getProcessName() != packageName) return

        // Generated audio belongs only to the current process/session. Reference audio, transcript
        // and synthesis settings intentionally remain persistent.
        clearTransientOutputs()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                InferenceKeepAliveService.start(this@OrbiApp)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private fun clearTransientOutputs() {
        // commit() is intentional here: Activity creation must never race an asynchronous apply().
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

        val work: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "orbi-omnivoice-worker").apply { priority = Thread.NORM_PRIORITY }
            }
        }
    }
}
