package com.orbiecosystem.omnivoice

import java.io.File

data class ModelFile(
    val relativePath: String,
    val minBytes: Long
) {
    val url: String
        get() = "https://huggingface.co/onnx-community/OmniVoice-Onnx/resolve/main/$relativePath?download=true"
}

object ModelCatalog {
    // INT4 backbone + full Higgs audio codec. The APK intentionally contains no model weights.
    val files = listOf(
        ModelFile("int4/audio_embeddings_encoder.onnx", 1_000),
        ModelFile("int4/audio_embeddings_encoder.onnx.data", 80_000_000),
        ModelFile("int4/audio_heads_decoder.onnx", 4_000_000),
        ModelFile("int4/llm_decoder.onnx", 200_000),
        ModelFile("int4/llm_decoder.onnx.data", 280_000_000),
        ModelFile("int4/tokenizer.json", 1_000_000),
        ModelFile("audio_tokenizer/acoustic_encoder.onnx", 190_000_000),
        ModelFile("audio_tokenizer/semantic_encoder.onnx", 400_000_000),
        ModelFile("audio_tokenizer/quantizer_encoder.onnx", 10_000_000),
        ModelFile("audio_tokenizer/higgs_decoder.onnx", 80_000_000)
    )

    fun modelRoot(filesDir: File): File = File(filesDir, "models/omnivoice_onnx")
    fun backboneDir(filesDir: File): File = File(modelRoot(filesDir), "int4")
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
