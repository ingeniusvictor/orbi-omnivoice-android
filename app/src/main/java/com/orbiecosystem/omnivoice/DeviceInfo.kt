package com.orbiecosystem.omnivoice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

data class DeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val abis: String,
    val ramGb: Double,
    val freeGb: Double,
    val sdk: Int
) {
    fun pretty(): String = buildString {
        appendLine("$manufacturer $model")
        appendLine("SoC: $soc")
        appendLine("ABI: $abis")
        appendLine("Android API: $sdk")
        appendLine("RAM física: %.1f GB".format(ramGb))
        append("Espacio libre app: %.1f GB".format(freeGb))
    }
}

object DeviceInfo {
    fun read(context: Context): DeviceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val stat = StatFs(context.filesDir.absolutePath)
        val free = stat.availableBytes.toDouble() / 1_000_000_000.0
        val ram = mi.totalMem.toDouble() / 1_000_000_000.0
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL
        } else {
            Build.HARDWARE
        }
        return DeviceSnapshot(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = soc,
            abis = Build.SUPPORTED_ABIS.joinToString(),
            ramGb = ram,
            freeGb = free,
            sdk = Build.VERSION.SDK_INT
        )
    }
}
