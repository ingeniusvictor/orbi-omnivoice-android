package com.orbiecosystem.omnivoice

import ai.onnxruntime.*
import tokenizers.Tokenizer
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.system.measureTimeMillis

enum class Backend { CPU, XNNPACK, NNAPI }

data class GenerationStats(
    val backend: Backend,
    val steps: Int,
    val frames: Int,
    val referenceFrames: Int,
    val referenceEncodeMs: Long,
    val generationMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val outputSeconds: Float,
    val outputFile: File
)

class OmniVoiceEngine(
    private val filesDir: File,
    private val backend: Backend,
    private val log: (String) -> Unit
) : Closeable {

    companion object {
        private const val NUM_CODEBOOKS = 8
        private const val AUDIO_VOCAB_REAL = 1024
        private const val AUDIO_MASK_ID = 1024L
        private const val HIDDEN = 1024
        private const val SR24 = 24_000
        private const val SR16 = 16_000
        private const val KV_HEADS = 8
        private const val HEAD_DIM = 128
        private val CB_WEIGHTS = floatArrayOf(8f, 8f, 6f, 6f, 4f, 4f, 2f, 2f).let { a ->
            val s = a.sum()
            FloatArray(a.size) { a[it] / s }
        }
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions()
    private var embeddings: OrtSession? = null
    private var llm: OrtSession? = null
    private var heads: OrtSession? = null
    private var acoustic: OrtSession? = null
    private var semantic: OrtSession? = null
    private var quantizer: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: Tokenizer? = null

    init {
        options.setIntraOpNumThreads(max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(8)))
        options.setInterOpNumThreads(1)
        when (backend) {
            Backend.CPU -> Unit
            Backend.XNNPACK -> {
                try {
                    options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                    log("Execution Provider: XNNPACK + CPU fallback")
                } catch (t: Throwable) {
                    log("XNNPACK no disponible (${t.message}); CPU fallback")
                }
            }
            Backend.NNAPI -> {
                try {
                    options.addNnapi()
                    log("Execution Provider: NNAPI + CPU fallback")
                } catch (t: Throwable) {
                    log("NNAPI no disponible (${t.message}); CPU fallback")
                }
            }
        }
    }

    private fun load() {
        if (embeddings != null) return
        require(ModelCatalog.isComplete(filesDir)) {
            "Faltan modelos: ${ModelCatalog.describeMissing(filesDir).joinToString()}"
        }
        val b = ModelCatalog.backboneDir(filesDir)
        val h = ModelCatalog.higgsDir(filesDir)
        log("Cargando sesiones ONNX…")
        embeddings = env.createSession(File(b, "audio_embeddings_encoder.onnx").absolutePath, options)
        llm = env.createSession(File(b, "llm_decoder.onnx").absolutePath, options)
        heads = env.createSession(File(b, "audio_heads_decoder.onnx").absolutePath, options)
        acoustic = env.createSession(File(h, "acoustic_encoder.onnx").absolutePath, options)
        semantic = env.createSession(File(h, "semantic_encoder.onnx").absolutePath, options)
        quantizer = env.createSession(File(h, "quantizer_encoder.onnx").absolutePath, options)
        decoder = env.createSession(File(h, "higgs_decoder.onnx").absolutePath, options)
        tokenizer = Tokenizer.fromFile(File(b, "tokenizer.json").absolutePath)
        log("Sesiones ONNX + tokenizer: READY")
    }

    fun generate(
        referenceWav: File,
        referenceText: String,
        targetText: String,
        steps: Int,
        outputSeconds: Float,
        outputFile: File
    ): GenerationStats {
        require(referenceWav.isFile) { "Falta audio de referencia WAV" }
        require(referenceText.isNotBlank()) { "Escribe la transcripción exacta de la referencia" }
        require(targetText.isNotBlank()) { "Escribe el texto a sintetizar" }
        require(steps in setOf(4, 8, 16, 32)) { "steps debe ser 4/8/16/32" }
        require(outputSeconds in 0.8f..8f) { "Duración fuera de rango" }

        val totalStart = System.currentTimeMillis()
        load()

        val ref = WavIO.readPcm16(referenceWav)
        val wav24 = WavIO.resampleLinear(ref.samples, ref.sampleRate, SR24)
        val wav16 = WavIO.resampleLinear(ref.samples, ref.sampleRate, SR16)
        val refDuration = wav24.size.toFloat() / SR24
        require(refDuration in 2f..12f) {
            "Referencia recomendada 3–10 s; recibida %.1f s".format(refDuration)
        }

        var prefix = LongArray(0)
        var refFrames = 0
        val refMs = measureTimeMillis {
            log("Codificando referencia (24k + 16k)…")
            val p = higgsEncode(wav24, wav16)
            prefix = p.first
            refFrames = p.second
        }
        log("Referencia: $refFrames frames (≈ %.2f s), ${refMs} ms".format(refFrames / 25f))

        val textIds = tokenize(targetText)
        log("Texto: ${textIds.size} tokens · ids=${textIds.take(12).joinToString(",")}")

        val genFrames = max(20, (outputSeconds * 25f).toInt())
        var codes = LongArray(0)
        val genMs = measureTimeMillis {
            codes = iterativeUnmask(textIds, prefix, refFrames, genFrames, steps)
        }

        var waveform = FloatArray(0)
        val decodeMs = measureTimeMillis {
            waveform = higgsDecode(codes, genFrames)
        }
        WavIO.writePcm16(outputFile, waveform, SR24)
        val total = System.currentTimeMillis() - totalStart
        val outSec = waveform.size.toFloat() / SR24

        return GenerationStats(
            backend, steps, genFrames, refFrames, refMs, genMs, decodeMs,
            total, outSec, outputFile
        )
    }

    /**
     * Diagnostic path that intentionally omits the reference prefix.
     * If this path is intelligible while voice cloning is not, the failure is in
     * reference-prefix conditioning. If this path is also unintelligible, the
     * problem is upstream of the codec: tokenization/backbone/unmasking.
     */
    fun generateAutoVoice(
        targetText: String,
        steps: Int,
        outputSeconds: Float,
        outputFile: File
    ): GenerationStats {
        require(targetText.isNotBlank()) { "Escribe el texto a sintetizar" }
        require(steps in setOf(4, 8, 16, 32)) { "steps debe ser 4/8/16/32" }
        require(outputSeconds in 0.8f..8f) { "Duración fuera de rango" }

        val totalStart = System.currentTimeMillis()
        load()
        val textIds = tokenize(targetText)
        log("AUTO-VOICE · ${textIds.size} tokens · ids=${textIds.take(12).joinToString(",")}")

        val genFrames = max(20, (outputSeconds * 25f).toInt())
        var codes = LongArray(0)
        val genMs = measureTimeMillis {
            codes = iterativeUnmask(textIds, LongArray(0), 0, genFrames, steps)
        }

        var waveform = FloatArray(0)
        val decodeMs = measureTimeMillis {
            waveform = higgsDecode(codes, genFrames)
        }
        WavIO.writePcm16(outputFile, waveform, SR24)
        val total = System.currentTimeMillis() - totalStart
        val outSec = waveform.size.toFloat() / SR24

        return GenerationStats(
            backend, steps, genFrames, 0, 0, genMs, decodeMs,
            total, outSec, outputFile
        )
    }

    private fun tokenize(text: String): LongArray =
        tokenizer!!.encode(text, true).ids.map { it.toLong() }.toLongArray()

    private fun higgsEncode(wav24: FloatArray, wav16: FloatArray): Pair<LongArray, Int> {
        val acousticOut = runFloat(
            acoustic!!,
            mapOf("waveform_24k" to floatTensor(wav24, longArrayOf(1, 1, wav24.size.toLong()))),
            "acoustic_features"
        )
        val aShape = acousticOut.shape
        val aT = aShape[2].toInt()

        val semanticOut = runFloat(
            semantic!!,
            mapOf("waveform_16k" to floatTensor(wav16, longArrayOf(1, wav16.size.toLong()))),
            "semantic_features"
        )
        val sShape = semanticOut.shape
        val sT = sShape[2].toInt()

        val t = minOf(aT, sT)
        val aTrim = if (aT == t) acousticOut.data else trimLastDim(acousticOut.data, 256, aT, t)
        val sTrim = if (sT == t) semanticOut.data else trimLastDim(semanticOut.data, 768, sT, t)

        val aTensor = floatTensor(aTrim, longArrayOf(1, 256, t.toLong()))
        val sTensor = floatTensor(sTrim, longArrayOf(1, 768, t.toLong()))
        try {
            val result = quantizer!!.run(mapOf(
                "acoustic_features" to aTensor,
                "semantic_features" to sTensor
            ))
            result.use {
                val out = it.get("codes").orElseThrow { IllegalStateException("codes output missing") } as OnnxTensor
                val shape = (out.info as TensorInfo).shape
                val buf = out.longBuffer ?: error("codes no es int64")
                val data = LongArray(buf.remaining())
                buf.get(data)
                val frames = shape[2].toInt()
                return data to frames
            }
        } finally {
            aTensor.close(); sTensor.close()
        }
    }

    private fun iterativeUnmask(
        textTokens: LongArray,
        prefixCodes: LongArray,
        refFrames: Int,
        genFrames: Int,
        steps: Int
    ): LongArray {
        val textN = textTokens.size
        val seq = textN + refFrames + genFrames
        val genStart = textN + refFrames
        val ids = LongArray(NUM_CODEBOOKS * seq)

        for (cb in 0 until NUM_CODEBOOKS) {
            val row = cb * seq
            for (i in textTokens.indices) ids[row + i] = textTokens[i]
            for (i in 0 until refFrames) ids[row + textN + i] = prefixCodes[cb * refFrames + i]
            for (i in 0 until genFrames) ids[row + genStart + i] = AUDIO_MASK_ID
        }

        val audioMask = BooleanArray(seq)
        for (i in genStart until seq) audioMask[i] = true

        var masked = genFrames
        var lastLogits: FloatArray? = null

        for (step in 0 until steps) {
            if (masked == 0) break
            log("Inferencia ${step + 1}/$steps · $masked frames pendientes")
            val logits = runBackbone(ids, audioMask, seq)
            lastLogits = logits

            val positions = IntArray(masked)
            var pi = 0
            for (p in 0 until genFrames) {
                if (ids[genStart + p] == AUDIO_MASK_ID) positions[pi++] = p
            }

            val confidence = FloatArray(genFrames) { Float.NEGATIVE_INFINITY }
            val argmax = Array(NUM_CODEBOOKS) { IntArray(genFrames) }

            for (p in positions) {
                var weighted = 0f
                val seqPos = genStart + p
                for (cb in 0 until NUM_CODEBOOKS) {
                    val base = ((cb * seq + seqPos) * 1025)
                    var maxLogit = Float.NEGATIVE_INFINITY
                    var best = 0
                    for (v in 0 until AUDIO_VOCAB_REAL) {
                        val x = logits[base + v]
                        if (x > maxLogit) { maxLogit = x; best = v }
                    }
                    var sum = 0.0
                    for (v in 0 until AUDIO_VOCAB_REAL) {
                        sum += exp((logits[base + v] - maxLogit).toDouble())
                    }
                    val maxProb = (1.0 / sum).toFloat()
                    weighted += maxProb * CB_WEIGHTS[cb]
                    argmax[cb][p] = best
                }
                confidence[p] = weighted
            }

            val remainingSteps = max(1, steps - step)
            val nThis = max(1, ceil(positions.size.toDouble() / remainingSteps).toInt())
            val chosen = positions.toList()
                .sortedByDescending { confidence[it] }
                .take(nThis)

            for (p in chosen) {
                val seqPos = genStart + p
                for (cb in 0 until NUM_CODEBOOKS) {
                    ids[cb * seq + seqPos] = argmax[cb][p].toLong()
                }
            }
            masked -= chosen.size
        }

        if (masked > 0) {
            log("Safety fill: $masked frames")
            val logits = lastLogits ?: runBackbone(ids, audioMask, seq)
            for (p in 0 until genFrames) {
                if (ids[genStart + p] != AUDIO_MASK_ID) continue
                val seqPos = genStart + p
                for (cb in 0 until NUM_CODEBOOKS) {
                    val base = ((cb * seq + seqPos) * 1025)
                    var best = 0
                    var maxLogit = Float.NEGATIVE_INFINITY
                    for (v in 0 until AUDIO_VOCAB_REAL) {
                        val x = logits[base + v]
                        if (x > maxLogit) { maxLogit = x; best = v }
                    }
                    ids[cb * seq + seqPos] = best.toLong()
                }
            }
        }

        val out = LongArray(NUM_CODEBOOKS * genFrames)
        for (cb in 0 until NUM_CODEBOOKS) {
            System.arraycopy(ids, cb * seq + genStart, out, cb * genFrames, genFrames)
        }
        return out
    }

    private fun runBackbone(ids: LongArray, audioMask: BooleanArray, seq: Int): FloatArray {
        val idTensor = longTensor(ids, longArrayOf(1, NUM_CODEBOOKS.toLong(), seq.toLong()))
        val maskTensor = boolTensor(audioMask, longArrayOf(1, seq.toLong()))
        val embedsData: FloatArray

        try {
            embeddings!!.run(mapOf("input_ids" to idTensor, "audio_mask" to maskTensor)).use { r ->
                val t = r.get("inputs_embeds").orElseThrow() as OnnxTensor
                val b = t.floatBuffer ?: error("inputs_embeds dtype no convertible a float")
                embedsData = FloatArray(b.remaining()); b.get(embedsData)
            }
        } finally {
            idTensor.close(); maskTensor.close()
        }

        val feed = LinkedHashMap<String, OnnxTensor>()
        feed["inputs_embeds"] = floatTensor(embedsData, longArrayOf(1, seq.toLong(), HIDDEN.toLong()))

        val names = llm!!.inputNames
        if ("attention_mask" in names) {
            feed["attention_mask"] = longTensor(LongArray(seq) { 1L }, longArrayOf(1, seq.toLong()))
        }
        if ("position_ids" in names) {
            feed["position_ids"] = longTensor(LongArray(seq) { it.toLong() }, longArrayOf(1, seq.toLong()))
        }
        for (name in names) {
            if ("past" in name) {
                feed[name] = floatTensor(FloatArray(0), longArrayOf(1, KV_HEADS.toLong(), 0, HEAD_DIM.toLong()))
            }
        }

        val hidden: FloatArray
        try {
            llm!!.run(feed).use { r ->
                val t = r.get("hidden_states").orElseThrow() as OnnxTensor
                val b = t.floatBuffer ?: error("hidden_states dtype no convertible a float")
                hidden = FloatArray(b.remaining()); b.get(hidden)
            }
        } finally {
            feed.values.forEach { it.close() }
        }

        val hTensor = floatTensor(hidden, longArrayOf(1, seq.toLong(), HIDDEN.toLong()))
        try {
            heads!!.run(mapOf("hidden_states" to hTensor)).use { r ->
                val t = r.get("logits").orElseThrow() as OnnxTensor
                val b = t.floatBuffer ?: error("logits dtype no convertible a float")
                return FloatArray(b.remaining()).also { b.get(it) }
            }
        } finally {
            hTensor.close()
        }
    }

    private fun higgsDecode(codes: LongArray, frames: Int): FloatArray {
        val t = longTensor(codes, longArrayOf(NUM_CODEBOOKS.toLong(), 1, frames.toLong()))
        try {
            decoder!!.run(mapOf("codes" to t)).use { r ->
                val out = r.get("waveform_24k").orElseThrow() as OnnxTensor
                val b = out.floatBuffer ?: error("waveform dtype no convertible a float")
                return FloatArray(b.remaining()).also { b.get(it) }
            }
        } finally {
            t.close()
        }
    }

    private data class FloatOut(val data: FloatArray, val shape: LongArray)

    private fun runFloat(
        session: OrtSession,
        tensors: Map<String, OnnxTensor>,
        output: String
    ): FloatOut {
        try {
            session.run(tensors).use { r ->
                val t = r.get(output).orElseThrow() as OnnxTensor
                val shape = (t.info as TensorInfo).shape
                val b = t.floatBuffer ?: error("$output dtype no convertible a float")
                return FloatOut(FloatArray(b.remaining()).also { b.get(it) }, shape)
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
        fb.put(data); fb.flip()
        return OnnxTensor.createTensor(env, fb, shape)
    }

    private fun longTensor(data: LongArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 8).order(ByteOrder.nativeOrder())
        val lb: LongBuffer = bb.asLongBuffer()
        lb.put(data); lb.flip()
        return OnnxTensor.createTensor(env, lb, shape)
    }

    private fun boolTensor(data: BooleanArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
        for (v in data) bb.put(if (v) 1 else 0)
        bb.flip()
        return OnnxTensor.createTensor(env, bb, shape, OnnxJavaType.BOOL)
    }

    override fun close() {
        listOf(embeddings, llm, heads, acoustic, semantic, quantizer, decoder).forEach {
            try { it?.close() } catch (_: Throwable) {}
        }
        try { options.close() } catch (_: Throwable) {}
        embeddings = null; llm = null; heads = null
        acoustic = null; semantic = null; quantizer = null; decoder = null
    }
}