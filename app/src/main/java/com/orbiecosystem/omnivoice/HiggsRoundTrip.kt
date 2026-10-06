package com.orbiecosystem.omnivoice

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.system.measureTimeMillis

data class RoundTripStats(
    val frames: Int,
    val encodeMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val inputSeconds: Float,
    val outputSeconds: Float,
    val outputFile: File
)

/**
 * Standalone Higgs codec diagnostic.
 *
 * It deliberately bypasses the OmniVoice text/backbone generation path:
 * reference WAV -> acoustic+semantic encoders -> quantizer codes -> Higgs decoder -> WAV.
 * If this output is intelligible, the codec path is healthy and any synthesis problem is upstream.
 */
class HiggsRoundTrip(
    private val filesDir: File,
    private val backend: Backend,
    private val log: (String) -> Unit
) : Closeable {

    companion object {
        private const val SR24 = 24_000
        private const val SR16 = 16_000
        private const val NUM_CODEBOOKS = 8
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions()
    private var acoustic: OrtSession? = null
    private var semantic: OrtSession? = null
    private var quantizer: OrtSession? = null
    private var decoder: OrtSession? = null

    init {
        options.setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(8)))
        options.setInterOpNumThreads(1)
        when (backend) {
            Backend.CPU -> Unit
            Backend.XNNPACK -> try {
                options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                log("Round-trip EP: XNNPACK + CPU fallback")
            } catch (t: Throwable) {
                log("Round-trip XNNPACK no disponible (${t.message}); CPU fallback")
            }
            Backend.NNAPI -> try {
                options.addNnapi()
                log("Round-trip EP: NNAPI + CPU fallback")
            } catch (t: Throwable) {
                log("Round-trip NNAPI no disponible (${t.message}); CPU fallback")
            }
        }
    }

    private fun load() {
        if (acoustic != null) return
        require(ModelCatalog.isComplete(filesDir)) { "Model Pack incompleto" }
        val h = ModelCatalog.higgsDir(filesDir)
        log("Round-trip: cargando codec Higgs…")
        acoustic = env.createSession(File(h, "acoustic_encoder.onnx").absolutePath, options)
        semantic = env.createSession(File(h, "semantic_encoder.onnx").absolutePath, options)
        quantizer = env.createSession(File(h, "quantizer_encoder.onnx").absolutePath, options)
        decoder = env.createSession(File(h, "higgs_decoder.onnx").absolutePath, options)
        log("Round-trip: codec Higgs READY")
    }

    fun run(referenceWav: File, outputFile: File): RoundTripStats {
        require(referenceWav.isFile) { "Falta WAV de referencia" }
        val totalStart = System.currentTimeMillis()
        load()

        val ref = WavIO.readPcm16(referenceWav)
        val wav24 = WavIO.resampleLinear(ref.samples, ref.sampleRate, SR24)
        val wav16 = WavIO.resampleLinear(ref.samples, ref.sampleRate, SR16)
        val inputSeconds = wav24.size.toFloat() / SR24

        var codes = LongArray(0)
        var frames = 0
        val encodeMs = measureTimeMillis {
            log("Round-trip: codificando referencia…")
            val p = encode(wav24, wav16)
            codes = p.first
            frames = p.second
        }

        var waveform = FloatArray(0)
        val decodeMs = measureTimeMillis {
            log("Round-trip: decodificando ${frames} frames…")
            waveform = decode(codes, frames)
        }

        outputFile.parentFile?.mkdirs()
        WavIO.writePcm16(outputFile, waveform, SR24)
        val totalMs = System.currentTimeMillis() - totalStart
        val outputSeconds = waveform.size.toFloat() / SR24
        log("ROUND-TRIP SUCCESS · ${frames} frames · ${totalMs} ms")

        return RoundTripStats(
            frames = frames,
            encodeMs = encodeMs,
            decodeMs = decodeMs,
            totalMs = totalMs,
            inputSeconds = inputSeconds,
            outputSeconds = outputSeconds,
            outputFile = outputFile
        )
    }

    private fun encode(wav24: FloatArray, wav16: FloatArray): Pair<LongArray, Int> {
        val acousticOut = runFloat(
            acoustic!!,
            mapOf("waveform_24k" to floatTensor(wav24, longArrayOf(1, 1, wav24.size.toLong()))),
            "acoustic_features"
        )
        val aT = acousticOut.shape[2].toInt()

        val semanticOut = runFloat(
            semantic!!,
            mapOf("waveform_16k" to floatTensor(wav16, longArrayOf(1, wav16.size.toLong()))),
            "semantic_features"
        )
        val sT = semanticOut.shape[2].toInt()

        val t = minOf(aT, sT)
        val aTrim = if (aT == t) acousticOut.data else trimLastDim(acousticOut.data, 256, aT, t)
        val sTrim = if (sT == t) semanticOut.data else trimLastDim(semanticOut.data, 768, sT, t)

        val aTensor = floatTensor(aTrim, longArrayOf(1, 256, t.toLong()))
        val sTensor = floatTensor(sTrim, longArrayOf(1, 768, t.toLong()))
        try {
            quantizer!!.run(
                mapOf("acoustic_features" to aTensor, "semantic_features" to sTensor)
            ).use { result ->
                val out = result.get("codes").orElseThrow() as OnnxTensor
                val shape = (out.info as TensorInfo).shape
                require(shape.size == 3 && shape[0].toInt() == NUM_CODEBOOKS) {
                    "Forma de codes inesperada: ${shape.joinToString("x")}" 
                }
                val buf = out.longBuffer ?: error("codes no es int64")
                val data = LongArray(buf.remaining())
                buf.get(data)
                return data to shape[2].toInt()
            }
        } finally {
            aTensor.close()
            sTensor.close()
        }
    }

    private fun decode(codes: LongArray, frames: Int): FloatArray {
        val tensor = longTensor(codes, longArrayOf(NUM_CODEBOOKS.toLong(), 1, frames.toLong()))
        try {
            decoder!!.run(mapOf("codes" to tensor)).use { result ->
                val out = result.get("waveform_24k").orElseThrow() as OnnxTensor
                val buf = out.floatBuffer ?: error("waveform_24k no convertible a float")
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        } finally {
            tensor.close()
        }
    }

    private data class FloatOut(val data: FloatArray, val shape: LongArray)

    private fun runFloat(
        session: OrtSession,
        tensors: Map<String, OnnxTensor>,
        output: String
    ): FloatOut {
        try {
            session.run(tensors).use { result ->
                val t = result.get(output).orElseThrow() as OnnxTensor
                val shape = (t.info as TensorInfo).shape
                val buf = t.floatBuffer ?: error("$output no convertible a float")
                return FloatOut(FloatArray(buf.remaining()).also { buf.get(it) }, shape)
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun trimLastDim(src: FloatArray, channels: Int, oldT: Int, newT: Int): FloatArray {
        val out = FloatArray(channels * newT)
        for (c in 0 until channels) {
            System.arraycopy(src, c * oldT, out, c * newT, newT)
        }
        return out
    }

    private fun floatTensor(data: FloatArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb: FloatBuffer = bb.asFloatBuffer()
        fb.put(data)
        fb.flip()
        return OnnxTensor.createTensor(env, fb, shape)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 8).order(ByteOrder.nativeOrder())
        val lb: LongBuffer = bb.asLongBuffer()
        lb.put(data)
        lb.flip()
        return OnnxTensor.createTensor(env, lb, shape)
    }

    override fun close() {
        listOf(acoustic, semantic, quantizer, decoder).forEach {
            try { it?.close() } catch (_: Throwable) {}
        }
        try { options.close() } catch (_: Throwable) {}
        acoustic = null
        semantic = null
        quantizer = null
        decoder = null
    }
}
