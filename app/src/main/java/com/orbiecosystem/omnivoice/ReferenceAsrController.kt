package com.orbiecosystem.omnivoice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

class ReferenceAsrController(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String, Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var latestPartial: String = ""
    private var usingOnDevice = false

    fun start() {
        destroyRecognizer()
        latestPartial = ""

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

        recognizer = candidate
        candidate.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatus(if (usingOnDevice) "ASR: escuchando localmente · Español Latino" else "ASR: escuchando con reconocedor del sistema · preferencia offline")
            }

            override fun onBeginningOfSpeech() {
                onStatus("ASR: voz detectada…")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                onStatus("ASR: procesando transcripción…")
            }

            override fun onError(error: Int) {
                if (latestPartial.isNotBlank()) {
                    onTranscript(latestPartial, false)
                    onStatus("ASR: se recuperó la transcripción parcial · revísala antes de clonar")
                } else {
                    onStatus("ASR: ${friendlyError(error)} · puedes escribir/corregir manualmente")
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
                    onStatus("ASR: transcripción lista · revisa y corrige solo si hace falta")
                } else {
                    onStatus("ASR: no devolvió texto · puedes escribir la transcripción manualmente")
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
        }

        try {
            candidate.startListening(intent)
        } catch (t: Throwable) {
            onStatus("ASR: no pudo iniciar (${t.message}) · transcripción manual habilitada")
            destroyRecognizer()
        }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Throwable) {
        }
    }

    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (_: Throwable) {
        }
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
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