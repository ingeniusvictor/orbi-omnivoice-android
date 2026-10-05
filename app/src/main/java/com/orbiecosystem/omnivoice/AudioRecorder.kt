package com.orbiecosystem.omnivoice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ReferenceRecorder {
    private val sampleRate = 24_000
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val pcm = ByteArrayOutputStream()

    fun start() {
        if (running.get()) return
        pcm.reset()
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min * 2
        )
        require(r.state == AudioRecord.STATE_INITIALIZED) {
            "No se pudo inicializar AudioRecord a 24 kHz"
        }
        record = r
        running.set(true)
        r.startRecording()

        worker = Thread {
            val buf = ByteArray(min)
            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) pcm.write(buf, 0, n)
            }
        }.also { it.name = "ORBI-RefRecorder"; it.start() }
    }

    fun stopToWav(out: File): File {
        if (!running.getAndSet(false)) return out
        try { record?.stop() } catch (_: Throwable) {}
        worker?.join(1500)
        record?.release()
        record = null
        worker = null

        val bytes = pcm.toByteArray()
        val samples = FloatArray(bytes.size / 2)
        var j = 0
        for (i in samples.indices) {
            val lo = bytes[j++].toInt() and 0xff
            val hi = bytes[j++].toInt()
            val s = ((hi shl 8) or lo).toShort()
            samples[i] = s / 32768f
        }
        WavIO.writePcm16(out, samples, sampleRate)
        return out
    }

    fun cancel() {
        if (running.getAndSet(false)) {
            try { record?.stop() } catch (_: Throwable) {}
        }
        try { worker?.join(500) } catch (_: Throwable) {}
        record?.release()
        record = null
        worker = null
        pcm.reset()
    }
}
