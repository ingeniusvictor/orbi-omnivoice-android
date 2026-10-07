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
    private const val WHISPER_REPO =
        "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"

    // Core OmniVoice pack used for TTS/cloning.
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

    // Optional-but-recommended local ASR pack. Whisper tiny multilingual INT8 is used only to
    // transcribe the saved reference WAV; it is deliberately independent from Xiaomi/Google ASR.
    val asrFiles = listOf(
        ModelFile(
            "asr_whisper_tiny/tiny-encoder.int8.onnx",
            10_000_000,
            "$WHISPER_REPO/tiny-encoder.int8.onnx?download=true"
        ),
        ModelFile(
            "asr_whisper_tiny/tiny-decoder.int8.onnx",
            80_000_000,
            "$WHISPER_REPO/tiny-decoder.int8.onnx?download=true"
        ),
        ModelFile(
            "asr_whisper_tiny/tiny-tokens.txt",
            500_000,
            "$WHISPER_REPO/tiny-tokens.txt?download=true"
        )
    )

    val allFiles: List<ModelFile> get() = files + asrFiles

    fun modelRoot(filesDir: File): File = File(filesDir, "models/omnivoice_onnx")
    fun backboneDir(filesDir: File): File = File(modelRoot(filesDir), "int4")
    fun bidirBackboneDir(filesDir: File): File = File(modelRoot(filesDir), "bidir_int4")
    fun higgsDir(filesDir: File): File = File(modelRoot(filesDir), "audio_tokenizer")
    fun asrDir(filesDir: File): File = File(modelRoot(filesDir), "asr_whisper_tiny")

    fun target(filesDir: File, spec: ModelFile): File =
        File(modelRoot(filesDir), spec.relativePath)

    fun isComplete(filesDir: File): Boolean = isSetComplete(filesDir, files)
    fun isAsrComplete(filesDir: File): Boolean = isSetComplete(filesDir, asrFiles)
    fun isAllComplete(filesDir: File): Boolean = isComplete(filesDir) && isAsrComplete(filesDir)

    fun describeMissing(filesDir: File): List<String> = missing(filesDir, files)
    fun describeMissingAsr(filesDir: File): List<String> = missing(filesDir, asrFiles)
    fun describeMissingAll(filesDir: File): List<String> = missing(filesDir, allFiles)

    private fun isSetComplete(filesDir: File, specs: List<ModelFile>): Boolean =
        specs.all { spec ->
            val f = target(filesDir, spec)
            f.isFile && f.length() >= spec.minBytes
        }

    private fun missing(filesDir: File, specs: List<ModelFile>): List<String> =
        specs.filterNot { target(filesDir, it).let { f -> f.isFile && f.length() >= it.minBytes } }
            .map { it.relativePath }
}
