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
import kotlin.math.ln
import kotlin.math.max
import kotlin.random.Random
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
        private const val AUDIO_VOCAB = 1025
        private const val AUDIO_MASK_ID = 1024L
        private const val HIDDEN = 1024
        private const val SR24 = 24_000
        private const val SR16 = 16_000
        private const val LANGUAGE_ID = "es"

        private const val GUIDANCE_SCALE = 2.0f
        private const val T_SHIFT = 0.1f
        private const val LAYER_PENALTY = 5.0f
        private const val POSITION_TEMPERATURE = 5.0f
    }

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions()
    private val llmOptions = OrtSession.SessionOptions()

    private var embeddings: OrtSession? = null
    private var llm: OrtSession? = null
    private var heads: OrtSession? = null
    private var acoustic: OrtSession? = null
    private var semantic: OrtSession? = null
    private var quantizer: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: Tokenizer? = null

    init {
        val threads = max(2, Runtime.getRuntime().availableProcessors().coerceAtMost(8))
        options.setIntraOpNumThreads(threads)
        options.setInterOpNumThreads(1)
        llmOptions.setIntraOpNumThreads(threads)
        llmOptions.setInterOpNumThreads(1)

        when (backend) {
            Backend.CPU -> Unit
            Backend.XNNPACK -> {
                try {
                    options.addXnnpack(mapOf("intra_op_num_threads" to "4"))
                    log("Execution Provider auxiliar: XNNPACK + CPU fallback")
                } catch (t: Throwable) {
                    log("XNNPACK no disponible (${t.message}); CPU fallback")
                }
            }
            Backend.NNAPI -> {
                try {
                    options.addNnapi()
                    log("Execution Provider auxiliar: NNAPI + CPU fallback")
                } catch (t: Throwable) {
                    log("NNAPI no disponible (${t.message}); CPU fallback")
                }
            }
        }
        log("Backbone bidireccional: CPUExecutionProvider (correctness path)")
        log("Idioma OmniVoice: Español ($LANGUAGE_ID)")
    }

    private fun load() {
        if (embeddings != null) return
        require(ModelCatalog.isComplete(filesDir)) {
            "Faltan modelos: ${ModelCatalog.describeMissing(filesDir).joinToString()}"
        }
        val b = ModelCatalog.backboneDir(filesDir)
        val bidir = ModelCatalog.bidirBackboneDir(filesDir)
        val h = ModelCatalog.higgsDir(filesDir)

        log("Cargando sesiones ONNX…")
        embeddings = env.createSession(File(b, "audio_embeddings_encoder.onnx").absolutePath, options)
        llm = env.createSession(File(bidir, "llm_decoder.onnx").absolutePath, llmOptions)
        heads = env.createSession(File(b, "audio_heads_decoder.onnx").absolutePath, options)
        acoustic = env.createSession(File(h, "acoustic_encoder.onnx").absolutePath, options)
        semantic = env.createSession(File(h, "semantic_encoder.onnx").absolutePath, options)
        quantizer = env.createSession(File(h, "quantizer_encoder.onnx").absolutePath, options)
        decoder = env.createSession(File(h, "higgs_decoder.onnx").absolutePath, options)
        tokenizer = Tokenizer.fromFile(File(b, "tokenizer.json").absolutePath)

        val llmInputs = llm!!.inputNames.sorted().joinToString(",")
        log("Sesiones READY · LLM bidireccional inputs=[$llmInputs]")
        log("CFG compatibility: ramas cond/uncond serializadas como batch=1")
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
            log("Codificando referencia Higgs (24k + 16k)…")
            val p = higgsEncode(wav24, wav16)
            prefix = p.first
            refFrames = p.second
        }
        log("Referencia: $refFrames frames (≈ %.2f s), ${refMs} ms".format(refFrames / 25f))

        val genFrames = max(20, (outputSeconds * 25f).toInt())
        var codes = LongArray(0)
        val genMs = measureTimeMillis {
            codes = diffusionGenerate(
                targetText = targetText,
                referenceText = referenceText,
                prefixCodes = prefix,
                refFrames = refFrames,
                genFrames = genFrames,
                steps = steps
            )
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

        val genFrames = max(20, (outputSeconds * 25f).toInt())
        var codes = LongArray(0)
        val genMs = measureTimeMillis {
            codes = diffusionGenerate(
                targetText = targetText,
                referenceText = null,
                prefixCodes = LongArray(0),
                refFrames = 0,
                genFrames = genFrames,
                steps = steps
            )
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

    private fun encodeText(text: String): LongArray =
        tokenizer!!.encode(text, false).ids.map { it.toLong() }.toLongArray()

    private fun diffusionGenerate(
        targetText: String,
        referenceText: String?,
        prefixCodes: LongArray,
        refFrames: Int,
        genFrames: Int,
        steps: Int
    ): LongArray {
        val cloning = refFrames > 0
        require(!cloning || prefixCodes.size == NUM_CODEBOOKS * refFrames) {
            "Prefix Higgs inválido: ${prefixCodes.size} valores para $refFrames frames"
        }

        var styleText = ""
        if (cloning) styleText += "<|denoise|>"
        styleText += "<|lang_start|>$LANGUAGE_ID<|lang_end|>"
        styleText += "<|instruct_start|>None<|instruct_end|>"

        val fullText = if (cloning && !referenceText.isNullOrBlank()) {
            referenceText.trim() + " " + targetText.trim()
        } else {
            targetText.trim()
        }.replace(Regex("[\\r\\n]+"), " ")
            .replace(Regex("[ \\t]+"), " ")

        val wrappedText = "<|text_start|>$fullText<|text_end|>"
        val styleTokens = encodeText(styleText)
        val textTokens = encodeText(wrappedText)
        val promptTokens = LongArray(styleTokens.size + textTokens.size)
        System.arraycopy(styleTokens, 0, promptTokens, 0, styleTokens.size)
        System.arraycopy(textTokens, 0, promptTokens, styleTokens.size, textTokens.size)

        val promptN = promptTokens.size
        val condSeq = promptN + refFrames + genFrames
        val condGenStart = promptN + refFrames
        val condAudioStart = if (cloning) promptN else condGenStart
        val batch = 2

        log(
            "DIFFUSION · mode=${if (cloning) "clone" else "auto"} · lang=$LANGUAGE_ID · prompt=$promptN tokens · " +
                "ref=$refFrames · target=$genFrames · steps=$steps"
        )
        log("CFG=$GUIDANCE_SCALE · tShift=$T_SHIFT · layerPenalty=$LAYER_PENALTY · posTemp=$POSITION_TEMPERATURE")

        val attention = BooleanArray(batch * condSeq * condSeq)
        fun attIndex(b: Int, q: Int, k: Int): Int = (b * condSeq + q) * condSeq + k
        for (q in 0 until condSeq) {
            for (k in 0 until condSeq) attention[attIndex(0, q, k)] = true
        }
        for (q in 0 until genFrames) {
            for (k in 0 until genFrames) attention[attIndex(1, q, k)] = true
        }
        for (p in genFrames until condSeq) attention[attIndex(1, p, p)] = true

        val audioMask = BooleanArray(batch * condSeq)
        for (p in condAudioStart until condSeq) audioMask[p] = true
        for (p in 0 until genFrames) audioMask[condSeq + p] = true

        val tokens = LongArray(NUM_CODEBOOKS * genFrames) { AUDIO_MASK_ID }
        val schedule = revealSchedule(genFrames * NUM_CODEBOOKS, steps)
        var remaining = genFrames * NUM_CODEBOOKS

        for (step in 0 until steps) {
            val k = schedule[step].coerceAtMost(remaining)
            if (k <= 0 || remaining <= 0) continue

            log("Difusión ${step + 1}/$steps · reveal=$k · pendientes=$remaining")

            val ids = LongArray(batch * NUM_CODEBOOKS * condSeq) { AUDIO_MASK_ID }
            fun idIndex(b: Int, cb: Int, pos: Int): Int = ((b * NUM_CODEBOOKS + cb) * condSeq) + pos

            for (cb in 0 until NUM_CODEBOOKS) {
                for (i in promptTokens.indices) ids[idIndex(0, cb, i)] = promptTokens[i]
                if (cloning) {
                    for (i in 0 until refFrames) {
                        ids[idIndex(0, cb, promptN + i)] = prefixCodes[cb * refFrames + i]
                    }
                }
                for (p in 0 until genFrames) {
                    val token = tokens[cb * genFrames + p]
                    ids[idIndex(0, cb, condGenStart + p)] = token
                    ids[idIndex(1, cb, p)] = token
                }
            }

            val logits = runBackboneBatch(ids, audioMask, attention, batch, condSeq)
            val candidates = ArrayList<SlotCandidate>(remaining)

            for (cb in 0 until NUM_CODEBOOKS) {
                for (p in 0 until genFrames) {
                    val slot = cb * genFrames + p
                    if (tokens[slot] != AUDIO_MASK_ID) continue

                    val cBase = (((0 * NUM_CODEBOOKS + cb) * condSeq + condGenStart + p) * AUDIO_VOCAB)
                    val uBase = (((1 * NUM_CODEBOOKS + cb) * condSeq + p) * AUDIO_VOCAB)

                    val cNorm = logSumExp(logits, cBase, AUDIO_VOCAB)
                    val uNorm = logSumExp(logits, uBase, AUDIO_VOCAB)

                    var guidedMax = Float.NEGATIVE_INFINITY
                    val guided = FloatArray(AUDIO_VOCAB)
                    for (v in 0 until AUDIO_VOCAB) {
                        val cLp = logits[cBase + v] - cNorm
                        val uLp = logits[uBase + v] - uNorm
                        val g = cLp + GUIDANCE_SCALE * (cLp - uLp)
                        guided[v] = g
                        if (g > guidedMax) guidedMax = g
                    }

                    var guidedSum = 0.0
                    for (v in 0 until AUDIO_VOCAB) {
                        guidedSum += exp((guided[v] - guidedMax).toDouble())
                    }
                    val guidedNorm = guidedMax + ln(guidedSum).toFloat()

                    var bestToken = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (v in 0 until AUDIO_VOCAB_REAL) {
                        val lp = guided[v] - guidedNorm
                        if (lp > bestScore) {
                            bestScore = lp
                            bestToken = v
                        }
                    }

                    var positionScore = bestScore - cb * LAYER_PENALTY
                    if (POSITION_TEMPERATURE > 0f) {
                        positionScore = positionScore / POSITION_TEMPERATURE + sampleGumbel()
                    }
                    candidates += SlotCandidate(slot, positionScore, bestToken)
                }
            }

            candidates.sortByDescending { it.score }
            val take = minOf(k, candidates.size)
            for (i in 0 until take) {
                val c = candidates[i]
                tokens[c.slot] = c.token.toLong()
            }
            remaining -= take
        }

        require(tokens.none { it == AUDIO_MASK_ID }) {
            "Difusión terminó con celdas MASK sin resolver ($remaining)"
        }

        log("DIFFUSION READY · ${NUM_CODEBOOKS * genFrames} celdas de audio resueltas")
        return tokens
    }

    private data class SlotCandidate(val slot: Int, val score: Float, val token: Int)

    private fun revealSchedule(totalMask: Int, steps: Int): IntArray {
        val times = FloatArray(steps + 1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            times[i] = T_SHIFT * t / (1f + (T_SHIFT - 1f) * t)
        }

        var rem = totalMask
        return IntArray(steps) { step ->
            val n = if (step == steps - 1) {
                rem
            } else {
                minOf(ceil(totalMask * (times[step + 1] - times[step]).toDouble()).toInt(), rem)
            }
            rem -= n
            n
        }
    }

    private fun sampleGumbel(): Float {
        val u = Random.nextDouble().coerceIn(1e-10, 1.0 - 1e-10)
        return (-ln(-ln(u))).toFloat()
    }

    private fun logSumExp(values: FloatArray, base: Int, count: Int): Float {
        var m = Float.NEGATIVE_INFINITY
        for (i in 0 until count) if (values[base + i] > m) m = values[base + i]
        var sum = 0.0
        for (i in 0 until count) sum += exp((values[base + i] - m).toDouble())
        return m + ln(sum).toFloat()
    }

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

    private fun runBackboneBatch(
        ids: LongArray,
        audioMask: BooleanArray,
        attentionMask: BooleanArray,
        batch: Int,
        seq: Int
    ): FloatArray {
        require(batch >= 1)
        val idsPerBranch = NUM_CODEBOOKS * seq
        val audioPerBranch = seq
        val attentionPerBranch = seq * seq
        val logitsPerBranch = NUM_CODEBOOKS * seq * AUDIO_VOCAB

        require(ids.size == batch * idsPerBranch) { "ids CFG shape inválido" }
        require(audioMask.size == batch * audioPerBranch) { "audioMask CFG shape inválido" }
        require(attentionMask.size == batch * attentionPerBranch) { "attentionMask CFG shape inválido" }

        val merged = FloatArray(batch * logitsPerBranch)
        for (branch in 0 until batch) {
            val branchIds = ids.copyOfRange(
                branch * idsPerBranch,
                (branch + 1) * idsPerBranch
            )
            val branchAudio = audioMask.copyOfRange(
                branch * audioPerBranch,
                (branch + 1) * audioPerBranch
            )
            val branchAttention = attentionMask.copyOfRange(
                branch * attentionPerBranch,
                (branch + 1) * attentionPerBranch
            )

            val branchLogits = runBackboneSingle(
                branchIds,
                branchAudio,
                branchAttention,
                seq
            )
            require(branchLogits.size == logitsPerBranch) {
                "logits branch=$branch tamaño=${branchLogits.size}, esperado=$logitsPerBranch"
            }
            System.arraycopy(
                branchLogits,
                0,
                merged,
                branch * logitsPerBranch,
                logitsPerBranch
            )
        }
        return merged
    }

    private fun runBackboneSingle(
        ids: LongArray,
        audioMask: BooleanArray,
        attentionMask: BooleanArray,
        seq: Int
    ): FloatArray {
        val idTensor = longTensor(ids, longArrayOf(1, NUM_CODEBOOKS.toLong(), seq.toLong()))
        val audioTensor = boolTensor(audioMask, longArrayOf(1, seq.toLong()))
        val embedsData: FloatArray

        try {
            embeddings!!.run(mapOf("input_ids" to idTensor, "audio_mask" to audioTensor)).use { r ->
                val t = r.get("inputs_embeds").orElseThrow() as OnnxTensor
                val b = t.floatBuffer ?: error("inputs_embeds dtype no convertible a float")
                embedsData = FloatArray(b.remaining())
                b.get(embedsData)
            }
        } finally {
            idTensor.close()
            audioTensor.close()
        }

        require(embedsData.size == seq * HIDDEN) {
            "inputs_embeds tamaño=${embedsData.size}, esperado=${seq * HIDDEN}"
        }

        val feed = LinkedHashMap<String, OnnxTensor>()
        feed["inputs_embeds"] = floatTensor(
            embedsData,
            longArrayOf(1, seq.toLong(), HIDDEN.toLong())
        )

        val names = llm!!.inputNames
        require("attention_mask" in names) {
            "El backbone cargado no es el bidireccional esperado: falta attention_mask 4-D"
        }
        feed["attention_mask"] = boolTensor(
            attentionMask,
            longArrayOf(1, 1, seq.toLong(), seq.toLong())
        )

        val hidden: FloatArray
        try {
            llm!!.run(feed).use { r ->
                val t = r.get("hidden_states").orElseThrow() as OnnxTensor
                val b = t.floatBuffer ?: error("hidden_states dtype no convertible a float")
                hidden = FloatArray(b.remaining())
                b.get(hidden)
            }
        } finally {
            feed.values.forEach { it.close() }
        }

        require(hidden.size == seq * HIDDEN) {
            "hidden_states tamaño=${hidden.size}, esperado=${seq * HIDDEN}"
        }

        val hTensor = floatTensor(
            hidden,
            longArrayOf(1, seq.toLong(), HIDDEN.toLong())
        )
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
        try { llmOptions.close() } catch (_: Throwable) {}
        embeddings = null
        llm = null
        heads = null
        acoustic = null
        semantic = null
        quantizer = null
        decoder = null
    }
}
