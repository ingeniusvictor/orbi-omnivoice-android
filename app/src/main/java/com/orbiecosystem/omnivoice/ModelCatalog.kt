package com.orbiecosystem.omnivoice

import java.io.File

data class ModelFile(
    val relativePath: String,
    val minBytes: Long,
    val remoteUrl: String? = null
) {
    val url: String
        get() = remoteUrl ?: "https://huggingface.co/onnx-community/OmniVoice-Onnx/resolve/main/$relativePath?download=true"
}

object ModelCatalog {
    private const val BIDIR_REPO =
        "https://huggingface.co/prskid1000/OmniVoice-Onnx-bidirectional/resolve/main"

    // INT4 audio embeddings/heads + corrected bidirectional INT4 Qwen backbone + full Higgs codec.
    // v0.5 intentionally stops using onnx-community's causal llm_decoder because OmniVoice is
    // a masked-diffusion model and requires full bidirectional attention during every denoising step.
    val files = listOf(
        ModelFile("int4/audio_embeddings_encoder.onnx", 1_000),
        ModelFile("int4/audio_embeddings_encoder.onnx.data", 80_000_000),
        ModelFile("int4/audio_heads_decoder.onnx", 4_000_000),
        ModelFile(
            "bidir_int4/llm_decoder.onnx",
            100_000,
            "$BIDIR_REPO/int4/llm_decoder.onnx?download=true"
        ),
        ModelFile(
            "bidir_int4/llm_decoder.onnx.data",
            250_000_000,
            "$BIDIR_REPO/int4/llm_decoder.onnx.data?download=true"
        ),
        ModelFile("int4/tokenizer.json", 1_000_000),
        ModelFile("audio_tokenizer/acoustic_encoder.onnx", 190_000_000),
        ModelFile("audio_tokenizer/semantic_encoder.onnx", 400_000_000),
        ModelFile("audio_tokenizer/quantizer_encoder.onnx", 10_000_000),
        ModelFile("audio_tokenizer/higgs_decoder.onnx", 80_000_000)
    )

    fun modelRoot(filesDir: File): File = File(filesDir, "models/omnivoice_onnx")
    fun backboneDir(filesDir: File): File = File(modelRoot(filesDir), "int4")
    fun bidirBackboneDir(filesDir: File): File = File(modelRoot(filesDir), "bidir_int4")
    fun higgsDir(filesDir: File): File = File(modelRoot(filesDir), "audio_tokenizer")

    fun target(filesDir: File, spec: ModelFile): File =
        File(modelRoot(filesDir), spec.relativePath)

    fun isComplete(filesDir: File): Boolean =
        files.all { spec ->
            val f = target(filesDir, spec)
            f.isFile && f.length() >= spec.minBytes
        }

    fun describeMissing(filesDir: File): List<String> =
        files.filterNot { target(filesDir, it).let { f -> f.isFile && f.length() >= it.minBytes } }
            .map { it.relativePath }
}
