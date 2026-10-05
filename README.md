# ORBI OmniVoice Android Edge Lab

Experimental Android **on-device voice-cloning** spike for ORBI Ecosystem.

## Goal

Prove true OmniVoice voice cloning on a modern Android phone without a PC/server after the model pack is downloaded.

This spike intentionally uses **ONNX Runtime Android** and `onnx-community/OmniVoice-Onnx` rather than the incomplete 2-file LiteRT spike. The ONNX conversion includes:

- INT4 OmniVoice backbone:
  - `audio_embeddings_encoder`
  - `llm_decoder`
  - `audio_heads_decoder`
- Full Higgs Audio V2 tokenizer:
  - `acoustic_encoder`
  - `semantic_encoder`
  - `quantizer_encoder`
  - `higgs_decoder`

That is enough to run:

`reference WAV -> Higgs codes -> OmniVoice iterative unmasking -> new codes -> WAV 24 kHz`

## Target phones

Initial test order:

1. POCO X7 Pro, 12 GB RAM / 512 GB — first bring-up and MediaTek compatibility check.
2. Xiaomi 14 Ultra, 16 GB / 512 GB — Snapdragon comparison.
3. Samsung Galaxy S26 Ultra, 12 GB / 256 GB — newer Snapdragon performance comparison.

## Build

Recommended: Android Studio current stable (2026).

- JDK 17+
- Android SDK 35
- arm64-v8a device
- minSdk 28

Open this folder as an Android Studio project and run `app` on the POCO X7 Pro.

Dependencies are pulled from Maven Central:
- `com.microsoft.onnxruntime:onnxruntime-android:1.30.0`
- `com.zhufucdev.hgtk:core:0.1.1`

> The ZIP deliberately does not bundle the Gradle wrapper JAR or multi-GB model weights.

## First run

1. Launch app.
2. Check Device Readiness.
3. Tap **Descargar / reanudar modelos**. Approx model pack is around 1.2 GB.
4. Record 5–8 s of clean voice.
5. Type the exact transcript in **Reference Text**.
6. Target text: keep first smoke test short.
7. Backend: start with **XNNPACK** on POCO X7 Pro.
8. Steps: start at **8**; if needed try **4**.
9. Duration: 1–2 s for first run.
10. Confirm consent.
11. Generate.
12. Compare CPU / XNNPACK / NNAPI with the evidence timings shown in the app.

## Important implementation notes

- Model weights are downloaded at runtime into app-private storage.
- Downloads are resumable using HTTP Range when the server supports it.
- WAV reference path currently supports PCM16 WAV. In-app recorder produces exactly that.
- Reference audio is resampled to 24 kHz (acoustic) and 16 kHz (semantic).
- Iterative unmasking is ported from the ONNX conversion's Python reference:
  codebook weights `[8,8,6,6,4,4,2,2]`, mask id 1024, 8 codebooks.
- The decoder output is saved as 24 kHz PCM16 WAV.
- This is an engineering spike, not a production app.

## Licensing / R&D guardrail

The code used here is experimental integration code. The app does not redistribute model weights.

Even if a conversion repository advertises a permissive license, **treat pretrained OmniVoice weights and derivatives as non-commercial R&D unless/until upstream weight licensing is independently cleared**. Do not ship this in a commercial ORBI product based only on the conversion repository's metadata.

## v0.1 exit criteria

- Model pack completes on device.
- Reference WAV encodes into Higgs codes.
- At least one backend completes the full voice-cloning path.
- Output WAV plays locally.
- Timings are captured for POCO X7 Pro.
- Same build can be benchmarked on Xiaomi 14 Ultra and S26 Ultra after the POCO smoke test.

## Next phase after first success

- Add QNN package/build for Snapdragon NPU (Qualcomm).
- Add model/session staged loading to reduce peak native RAM.
- Add cached VoiceClonePrompt/reference codes.
- Add native audio preprocessing and better resampling.
- Add benchmark export JSON.
- Compare ONNX FP16 vs INT4 on Snapdragon.
- Evaluate LiteRT conversion once the full audio-encoder path is available.

## v0.2 POCO X7 Pro profile
- Default backend: XNNPACK
- Default inference steps: 4
- First-device target: POCO X7 Pro 12GB/512GB, Dimensity 8400-Ultra
- NNAPI remains an experimental second pass after XNNPACK smoke test.
