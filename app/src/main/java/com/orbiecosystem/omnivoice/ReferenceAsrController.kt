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

                onStatus("ASR local: Whisper Tiny INT8 · Español · transcribiendo WAV…")
                val dir = ModelCatalog.asrDir(context.filesDir)
                val wav = WavIO.readPcm16(wavFile)

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
