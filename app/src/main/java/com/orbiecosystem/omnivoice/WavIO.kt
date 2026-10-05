package com.orbiecosystem.omnivoice

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

data class WavData(val samples: FloatArray, val sampleRate: Int)

object WavIO {
    fun readPcm16(file: File): WavData {
        RandomAccessFile(file, "r").use { raf ->
            fun read4(): String {
                val b = ByteArray(4)
                raf.readFully(b)
                return String(b, Charsets.US_ASCII)
            }
            fun u16(): Int {
                val b = ByteArray(2); raf.readFully(b)
                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
            }
            fun i32(): Int {
                val b = ByteArray(4); raf.readFully(b)
                return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
            }

            require(read4() == "RIFF") { "No es WAV RIFF" }
            i32()
            require(read4() == "WAVE") { "WAV inválido" }

            var channels = 0
            var sampleRate = 0
            var bits = 0
            var format = 0
            var dataOffset = -1L
            var dataSize = 0

            while (raf.filePointer + 8 <= raf.length()) {
                val id = read4()
                val size = i32()
                val start = raf.filePointer
                when (id) {
                    "fmt " -> {
                        format = u16()
                        channels = u16()
                        sampleRate = i32()
                        i32()
                        u16()
                        bits = u16()
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        break
                    }
                }
                raf.seek(start + size + (size and 1))
            }

            require(dataOffset >= 0) { "Chunk data no encontrado" }
            require(format == 1) { "Solo PCM WAV es soportado en este spike (format=$format)" }
            require(bits == 16) { "Solo WAV PCM16 (bits=$bits)" }
            require(channels in 1..2) { "Canales no soportados: $channels" }

            raf.seek(dataOffset)
            val bytes = ByteArray(dataSize)
            raf.readFully(bytes)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val frames = dataSize / 2 / channels
            val mono = FloatArray(frames)
            for (i in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum += bb.short.toFloat() / 32768f
                }
                mono[i] = sum / channels
            }
            return WavData(mono, sampleRate)
        }
    }

    fun resampleLinear(samples: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (sourceRate == targetRate) return samples.copyOf()
        val outLen = ((samples.size.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        for (i in 0 until outLen) {
            val x = i * ratio
            val x0 = floor(x).toInt().coerceIn(0, samples.lastIndex)
            val x1 = (x0 + 1).coerceAtMost(samples.lastIndex)
            val a = (x - x0).toFloat()
            out[i] = samples[x0] * (1f - a) + samples[x1] * a
        }
        return out
    }

    fun writePcm16(file: File, samples: FloatArray, sampleRate: Int) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val dataBytes = samples.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16)
            header.putShort(1)
            header.putShort(1)
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataBytes)
            raf.write(header.array())

            val block = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                if (block.remaining() < 2) {
                    raf.write(block.array(), 0, block.position())
                    block.clear()
                }
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                block.putShort(v)
            }
            if (block.position() > 0) raf.write(block.array(), 0, block.position())
        }
    }
}
