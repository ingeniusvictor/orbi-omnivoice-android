package com.orbiecosystem.omnivoice

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class ReferenceAsrController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit
) {
    private val work = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong(0)

    fun transcribeFile(wavFile: File) {
        val ticket = generation.incrementAndGet()
        if (!wavFile.isFile) {
            onStatus("ASR local: referencia WAV no encontrada")
            return
        }

        work.execute {
            try {
                if (!ModelCatalog.isAsrComplete(context.filesDir)) {
                    onStatus("ASR local: preparando Whisper Tiny INT8 (~99 MB, solo la primera vez)…")
                    val dl = ModelDownloader(
                        ModelCatalog.modelRoot(context.filesDir),
                        onStatus = { s ->
                            if (generation.get() == ticket && s.contains("asr_whisper_tiny")) {
                                onStatus("ASR local · $s")
                            }
                        },
                        onProgress = { }
                    )
                    dl.downloadAll()
                }
                if (generation.get() != ticket) return@execute
                require(ModelCatalog.isAsrComplete(context.filesDir)) {
                    "El pack Whisper local no quedó completo"
                }

                onStatus("ASR local: Whisper Tiny INT8 · Español · proceso aislado…")
                val resultFile = WhisperAsrService.resultFile(context.filesDir)
                resultFile.delete()
                WhisperAsrService.start(context.applicationContext, wavFile, ticket)

                val deadline = SystemClock.elapsedRealtime() + ASR_TIMEOUT_MS
                while (generation.get() == ticket && SystemClock.elapsedRealtime() < deadline) {
                    if (resultFile.isFile) {
                        val payload = try {
                            JSONObject(resultFile.readText(Charsets.UTF_8))
                        } catch (_: Throwable) {
                            null
                        }
                        if (payload != null && payload.optLong("ticket", -1L) == ticket) {
                            resultFile.delete()
                            if (payload.optBoolean("ok", false)) {
                                val text = payload.optString("text", "").trim()
                                if (text.isNotBlank()) {
                                    onTranscript(text, true)
                                    onStatus("ASR local: transcripción lista · revisa y corrige solo si hace falta")
                                } else {
                                    onStatus("ASR local: Whisper no devolvió texto · puedes escribirlo manualmente")
                                }
                            } else {
                                val error = payload.optString("error", "Error ASR desconocido")
                                onStatus("ASR local ERROR aislado: $error")
                            }
                            return@execute
                        }
                    }
                    Thread.sleep(POLL_MS)
                }

                if (generation.get() == ticket) {
                    onStatus("ASR local ERROR: timeout esperando al proceso Whisper")
                }
            } catch (t: Throwable) {
                if (generation.get() == ticket) {
                    onStatus("ASR local ERROR: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    fun cancel() {
        generation.incrementAndGet()
    }

    fun close() {
        generation.incrementAndGet()
        work.shutdownNow()
    }

    companion object {
        private const val ASR_TIMEOUT_MS = 90_000L
        private const val POLL_MS = 100L
    }
}
