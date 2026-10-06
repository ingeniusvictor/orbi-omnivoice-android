package com.orbiecosystem.omnivoice

import android.app.Application
import android.content.Context

class OrbiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
