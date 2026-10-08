# ORBI Voice

**ORBI Voice** es la aplicación de voz local de ORBI Ecosystem para clonación de voz, síntesis y procesamiento on-device en Android.

El producto se presenta y se desarrolla bajo la marca **ORBI Voice**. La implementación experimental actual utiliza tecnologías y modelos de terceros —incluyendo OmniVoice para síntesis/clonación y Whisper/Sherpa-ONNX para transcripción local— que se mantienen identificados internamente por razones técnicas, de atribución y licencia.

## Estado actual

El prototipo Android ya demuestra en el POCO X7 Pro:

- grabación de referencia WAV;
- reproducción de la referencia;
- transcripción automática local y editable;
- clonación de voz local;
- TTS sin referencia;
- duración AUTO y control de velocidad;
- inferencia protegida con Foreground Service + Wake Lock;
- persistencia de referencia y configuración;
- resultados de generación limitados a la sesión activa;
- exportación de WAV a `Descargas/ORBI Voice`;
- diagnóstico de codec Higgs;
- aislamiento del runtime nativo de Whisper/Sherpa respecto del runtime usado por el motor de clonación.

## Arquitectura técnica actual

```text
ORBI Voice Android UI
        |
        +-- Reference Recorder
        +-- Local ASR (Whisper Tiny INT8 / Sherpa-ONNX)
        +-- Voice synthesis / cloning engine
        |      +-- OmniVoice ONNX backbone
        |      +-- Higgs Audio V2 tokenizer / decoder
        |
        +-- Audio post-processing
        +-- Public WAV export
```

La ruta de clonación actual es:

```text
reference WAV
  -> Higgs codes
  -> OmniVoice iterative unmasking
  -> generated audio codes
  -> Higgs decoder
  -> ORBI Voice WAV 24 kHz
```

## Dispositivo de referencia

Desarrollo inicial y validación principal:

- POCO X7 Pro
- 12 GB RAM / 512 GB
- Dimensity 8400-Ultra
- Android arm64-v8a

Otros dispositivos previstos para comparación:

- Xiaomi 14 Ultra
- Samsung Galaxy S26 Ultra

## Build

- JDK 17+
- Android SDK 36
- minSdk 28
- arm64-v8a

El proyecto conserva por ahora su `applicationId`, namespace y nombres técnicos internos históricos para mantener compatibilidad de actualización y no invalidar los modelos/datos ya descargados en los dispositivos de prueba.

## Modelos

Los pesos no se incluyen dentro del APK. Se descargan en tiempo de ejecución al almacenamiento privado de la aplicación.

La implementación actual utiliza:

- ONNX Runtime Android 1.30.0 para el motor de clonación;
- Whisper Tiny multilingual INT8 + Sherpa-ONNX para ASR local;
- runtimes nativos aislados para evitar conflictos ABI dentro de Android.

## Licensing / R&D guardrail

ORBI Voice se encuentra todavía en etapa de I+D. El código de integración es propio del proyecto, pero los modelos y componentes de terceros conservan sus licencias correspondientes.

Los pesos preentrenados de OmniVoice y sus derivados deben tratarse como **uso no comercial / I+D** mientras no exista una licencia de pesos independiente que autorice expresamente su explotación comercial.

La denominación **ORBI Voice** identifica nuestro producto y experiencia de usuario; no implica propiedad sobre los modelos o proyectos upstream utilizados por la implementación actual.

## Branding

- Producto: **ORBI Voice**
- Ecosistema: **ORBI Ecosystem**
- Motor de síntesis/clonación actual: OmniVoice (referencia técnica interna)
- ASR actual: Whisper + Sherpa-ONNX

A partir de v0.10.0, la interfaz, notificaciones, documentación y artefactos de prueba se presentan como **ORBI Voice**.
