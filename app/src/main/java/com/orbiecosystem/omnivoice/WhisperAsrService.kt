package com.orbiecosystem.omnivoice

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Runs Whisper/Sherpa in an isolated app process (:asr).
 *
 * OmniVoice stays on ORT 1.30.0 in the main process. CI packages Sherpa's official ORT under a
 * private renamed SONAME for this process so the two native stacks never share an address space.
 */
class WhisperAsrService : Service() {
    private val work = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ticket = intent?.getLongExtra(EXTRA_TICKET, -1L) ?: -1L
        val wavPath = intent?.getStringExtra(EXTRA_WAV_PATH)

        if (ticket < 0 || wavPath.isNullOrBlank()) {
            writeResult(ticket, null, "Solicitud ASR inválida")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        work.execute {
            try {
                val wavFile = File(wavPath)
                require(wavFile.isFile) { "Referencia WAV no encontrada" }
                require(ModelCatalog.isAsrComplete(filesDir)) { "El pack Whisper local no está completo" }

                // Force the known-good official Sherpa runtime before OfflineRecognizer initializes.
                System.loadLibrary("sherpa_onnxruntime")
                System.loadLibrary("sherpa-onnx-jni")

                val dir = ModelCatalog.asrDir(filesDir)
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
                var stream: OfflineStream? = null
                try {
                    recognizer = OfflineRecognizer(config = config)
                    stream = recognizer.createStream()
                    stream.acceptWaveform(wav.samples, wav.sampleRate)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isBlank()) {
                        writeResult(ticket, null, "Whisper no devolvió texto")
                    } else {
                        writeResult(ticket, text, null)
                    }
                } finally {
                    try { stream?.release() } catch (_: Throwable) {}
                    try { recognizer?.release() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                writeResult(ticket, null, "${t.javaClass.simpleName}: ${t.message}")
            } finally {
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        work.shutdown()
        super.onDestroy()
    }

    private fun writeResult(ticket: Long, text: String?, error: String?) {
        val target = resultFile(filesDir)
        target.parentFile?.mkdirs()
        val payload = JSONObject()
            .put("ticket", ticket)
            .put("ok", error == null && !text.isNullOrBlank())
            .put("text", text ?: "")
            .put("error", error ?: "")
            .toString()

        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(payload, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(payload, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val EXTRA_WAV_PATH = "wav_path"
        private const val EXTRA_TICKET = "ticket"

        fun start(context: Context, wavFile: File, ticket: Long) {
            resultFile(context.filesDir).delete()
            val intent = Intent(context, WhisperAsrService::class.java)
                .putExtra(EXTRA_WAV_PATH, wavFile.absolutePath)
                .putExtra(EXTRA_TICKET, ticket)
            context.startService(intent)
        }

        fun resultFile(filesDir: File): File = File(filesDir, "asr/last_result.json")
    }
}
