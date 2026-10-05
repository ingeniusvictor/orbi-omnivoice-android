# Validation checklist — v0.1

## Static architecture
- [x] arm64-v8a only
- [x] no weights in APK/source ZIP
- [x] model downloader with resume support
- [x] device RAM/storage/SoC readout
- [x] in-app PCM16 24 kHz reference recorder
- [x] manual reference transcript required
- [x] CPU / XNNPACK / NNAPI selector
- [x] 4 / 8 / 16 / 32 decoding steps
- [x] variable output frames by seconds
- [x] full Higgs encode path
- [x] iterative OmniVoice unmasking port
- [x] Higgs decoder -> 24 kHz WAV
- [x] consent gate
- [x] local evidence timings

## Source-level checks completed in this environment
- [x] `WavIO.kt` compiles with Kotlin/JVM compiler.
- [x] `ModelCatalog.kt` + `ModelDownloader.kt` compile with Kotlin/JVM compiler.
- [x] `OmniVoiceEngine.kt` compiles with Kotlin/JVM compiler against API-compatible ONNX/tokenizer stubs.
- [x] GitHub Actions workflow included for `:app:assembleDebug` using Android SDK + JDK 17.
- [ ] Full Android Gradle dependency resolution/build in this container (Android SDK is not installed here).

## Runtime gates (must be run on Android build/physical phone)
- [ ] Android Gradle dependency resolution
- [ ] debug APK build
- [ ] POCO X7 Pro installation
- [ ] model pack download
- [ ] tokenizer smoke test
- [ ] acoustic encoder
- [ ] semantic encoder
- [ ] quantizer encoder
- [ ] backbone step
- [ ] iterative unmasking
- [ ] decoder
- [ ] audible cloned output
- [ ] compare CPU / XNNPACK / NNAPI

## Definition of v0.1 success
A build is not considered "working OmniVoice Android" until a physical device produces an audible WAV from a user-authorized reference voice and logs the full encode → generate → decode timings.
