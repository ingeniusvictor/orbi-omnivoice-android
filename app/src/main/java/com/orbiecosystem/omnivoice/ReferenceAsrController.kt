package com.orbiecosystem.omnivoice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class ReferenceAsrController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var sourceRead: ParcelFileDescriptor? = null
    private var sourceWrite: ParcelFileDescriptor? = null
    private var latestPartial: String = ""
    private var usingOnDevice = false

    /**
     * Transcribe the WAV only AFTER our own recorder has released the microphone.
     * Android 13+ can feed PCM directly to SpeechRecognizer via EXTRA_AUDIO_SOURCE,
     * avoiding the microphone contention that made v0.7 unreliable on HyperOS.
     */
    fun transcribeFile(wavFile: File) {
        cancel()
        latestPartial = ""

        if (Build.VERSION.SDK_INT < 33) {
            onStatus("ASR: este Android no admite transcripción directa del WAV · texto manual habilitado")
            return
        }

        val wav = try {
            WavIO.readPcm16(wavFile)
        } catch (t: Throwable) {
            onStatus("ASR: no pudo leer la referencia (${t.message})")
            return
        }

        val candidate = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                usingOnDevice = true
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                usingOnDevice = false
                SpeechRecognizer.createSpeechRecognizer(context)
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }

        if (candidate == null) {
            onStatus("ASR: no disponible en este dispositivo · transcripción manual habilitada")
            return
        }

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            onStatus("ASR: no pudo crear fuente de audio (${t.message})")
            candidate.destroy()
            return
        }
        sourceRead = pipe[0]
        sourceWrite = pipe[1]
        recognizer = candidate

        candidate.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatus(
                    if (usingOnDevice) "ASR: transcribiendo WAV localmente · Español Latino"
                    else "ASR: transcribiendo WAV con reconocedor del sistema · preferencia offline"
                )
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                onStatus("ASR: procesando transcripción…")
            }

            override fun onError(error: Int) {
                if (latestPartial.isNotBlank()) {
                    onTranscript(latestPartial, false)
                    onStatus("ASR: se recuperó transcripción parcial · revísala antes de clonar")
                } else {
                    onStatus("ASR: ${friendlyError(error)} · puedes corregir/escribir manualmente")
                }
                destroyRecognizer()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotBlank()) {
                    onTranscript(text, true)
                    onStatus("ASR: transcripción automática lista · revisa y corrige solo si hace falta")
                } else {
                    onStatus("ASR: no devolvió texto · transcripción manual habilitada")
                }
                destroyRecognizer()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotBlank()) {
                    latestPartial = text
                    onTranscript(text, false)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-419")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-419")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, sourceRead)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, wav.sampleRate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        try {
            onStatus("ASR: enviando referencia grabada al reconocedor…")
            candidate.startListening(intent)
        } catch (t: Throwable) {
            onStatus("ASR: no pudo iniciar (${t.message}) · texto manual habilitado")
            destroyRecognizer()
            return
        }

        val writer = sourceWrite ?: return
        thread(name = "orbi-asr-wav-feed", isDaemon = true) {
            try {
                FileOutputStream(writer.fileDescriptor).use { out ->
                    val block = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
                    for (sample in wav.samples) {
                        if (block.remaining() < 2) {
                            out.write(block.array(), 0, block.position())
                            block.clear()
                        }
                        val v = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                        block.putShort(v)
                    }
                    if (block.position() > 0) out.write(block.array(), 0, block.position())
                    out.flush()
                }
            } catch (_: Throwable) {
            } finally {
                try { writer.close() } catch (_: Throwable) {}
                sourceWrite = null
            }
        }
    }

    fun cancel() {
        try { recognizer?.cancel() } catch (_: Throwable) {}
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try { recognizer?.destroy() } catch (_: Throwable) {}
        recognizer = null
        try { sourceRead?.close() } catch (_: Throwable) {}
        try { sourceWrite?.close() } catch (_: Throwable) {}
        sourceRead = null
        sourceWrite = null
    }

    private fun friendlyError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "error de audio del reconocedor"
        SpeechRecognizer.ERROR_CLIENT -> "sesión de reconocimiento cancelada"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "faltan permisos de micrófono"
        SpeechRecognizer.ERROR_NETWORK -> "red no disponible para el fallback del sistema"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "tiempo de red agotado"
        SpeechRecognizer.ERROR_NO_MATCH -> "no se pudo reconocer con suficiente confianza"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "reconocedor ocupado"
        SpeechRecognizer.ERROR_SERVER -> "servicio de reconocimiento no disponible"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no detectó voz"
        else -> "error de reconocimiento $error"
    }
}
