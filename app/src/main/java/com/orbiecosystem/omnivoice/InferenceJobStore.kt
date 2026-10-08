package com.orbiecosystem.omnivoice

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class InferenceSnapshot(
    val jobId: String,
    val kind: String,
    val state: String,
    val message: String,
    val outputPath: String?,
    val updatedAtMs: Long
)

object InferenceJobStore {
    const val KIND_CLONE = "CLONE"
    const val KIND_TTS = "TTS"

    const val STATE_RUNNING = "RUNNING"
    const val STATE_COMPLETED = "COMPLETED"
    const val STATE_ERROR = "ERROR"

    private const val FILE_NAME = "orbi_voice_inference_state.json"

    fun write(context: Context, snapshot: InferenceSnapshot) {
        val target = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        val json = JSONObject()
            .put("jobId", snapshot.jobId)
            .put("kind", snapshot.kind)
            .put("state", snapshot.state)
            .put("message", snapshot.message)
            .put("outputPath", snapshot.outputPath ?: JSONObject.NULL)
            .put("updatedAtMs", snapshot.updatedAtMs)
            .toString()

        FileOutputStream(tmp, false).use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "No se pudo publicar el estado de inferencia" }
        }
    }

    fun read(context: Context): InferenceSnapshot? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            InferenceSnapshot(
                jobId = o.optString("jobId"),
                kind = o.optString("kind"),
                state = o.optString("state"),
                message = o.optString("message"),
                outputPath = if (o.isNull("outputPath")) null else o.optString("outputPath"),
                updatedAtMs = o.optLong("updatedAtMs")
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, "$FILE_NAME.tmp").delete()
    }
}
