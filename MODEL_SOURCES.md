# Model/runtime sources

- Upstream model: `k2-fsa/OmniVoice`
- Android inference conversion used by this spike: `onnx-community/OmniVoice-Onnx`
- Runtime: ONNX Runtime Android
- Tokenizer bridge: `zhufucdev/huggingface-tokenizers-kmp`

Runtime model files are intentionally downloaded from Hugging Face and are not embedded in this repository/artifact.

## Model paths downloaded

Backbone:
- int4/audio_embeddings_encoder.onnx
- int4/audio_embeddings_encoder.onnx.data
- int4/audio_heads_decoder.onnx
- int4/llm_decoder.onnx
- int4/llm_decoder.onnx.data
- int4/tokenizer.json

Higgs:
- audio_tokenizer/acoustic_encoder.onnx
- audio_tokenizer/semantic_encoder.onnx
- audio_tokenizer/quantizer_encoder.onnx
- audio_tokenizer/higgs_decoder.onnx
