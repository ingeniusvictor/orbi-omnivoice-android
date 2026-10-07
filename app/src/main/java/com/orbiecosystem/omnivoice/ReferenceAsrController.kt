package com.orbiecosystem.omnivoice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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

    /**
     * Deterministic local ASR path for v0.9.
     *
     * v0.7 tried to run Android SpeechRecognizer while ReferenceRecorder owned the microphone.
     * v0.8 fed the saved WAV through EXTRA_AUDIO_SOURCE, but that path is OEM/recognizer-service
     * dependent and did not return text reliably on the POCO X7 Pro. This version bypasses the
     * Android recognition service completely and decodes the saved WAV with sherpa-onnx +
     * multilingual Whisper tiny INT8, language pinned to Spanish.
     */
    fun transcribeFile(wavFile: File) {
        val ticket = generation.incrementAndGet()
        if (!wavFile.isFile) {
            onStatus("ASR local: referencia WAV no encontrada")
            return
        }
        if (!ModelCatalog.isAsrComplete(context.filesDir)) {
            val missing = ModelCatalog.describeMissingAsr(context.filesDir)
            onStatus(
                "ASR local: faltan ${missing.size} archivos (~99 MB). " +
                    "Pulsa Descargar / reanudar modelos y vuelve a grabar."
            )
            return
        }

        onStatus("ASR local: Whisper Tiny INT8 · Español · transcribiendo WAV…")
        work.execute {
            try {
                val dir = ModelCatalog.asrDir(context.filesDir)
                val wav = WavIO.readPcm16(wavFile)
                if (generation.get() != ticket) return@execute

                val modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = File(dir, "tiny-encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "tiny-decoder.int8.onnx").absolutePath,
                        language = "es",
                        task = "transcribe",
                        tailPaddings = 250
                    ),
                    tokens = File(dir, "tiny-tokens.txt").absolutePath,
                    numThreads = 4,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper"
                )
                val config = OfflineRecognizerConfig(
                    modelConfig = modelConfig,
                    decodingMethod = "greedy_search"
                )

                var recognizer: OfflineRecognizer? = null
                var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
                try {
                    recognizer = OfflineRecognizer(config = config)
                    stream = recognizer.createStream()
                    stream.acceptWaveform(wav.samples, wav.sampleRate)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    if (generation.get() != ticket) return@execute
                    if (text.isNotBlank()) {
                        onTranscript(text, true)
                        onStatus("ASR local: transcripción lista · revisa y corrige solo si hace falta")
                    } else {
                        onStatus("ASR local: Whisper no devolvió texto · puedes escribirlo manualmente")
                    }
                } finally {
                    try { stream?.release() } catch (_: Throwable) {}
                    try { recognizer?.release() } catch (_: Throwable) {}
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
}
