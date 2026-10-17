# Smriti — architecture and build spec

Offline camera-and-voice work memory. Everything runs on the phone; the app declares no
network permission at all.

Target: Android 26+, arm64-v8a. Test device: Realme RMX5106 (MediaTek Dimensity 7300 / MT6878,
Mali GPU, 8 GB RAM, Android 16). Model: Gemma 4 E2B as `gemma-4-E2B-it.litertlm` (~2.6 GB, the
file Google AI Edge Gallery downloads), run through LiteRT-LM. Verified build host: macOS arm64, JDK 21, AGP 8.13.2,
Gradle 8.14, compileSdk 36, build-tools 36.0.0. No product flavors: `source env.sh` then
`./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`, applicationId
`com.smriti.app`.

> Provenance note: this repository is a reference implementation written outside the iQOO
> Hackathon event window. Competition rules require all submitted code to be written during
> the event. The venue build starts from an empty directory; this repo is not submitted and
> is not linked from the Phase 1 form.

---

## 1. Module layout (single Gradle module, `:app`)

```
com.smriti.app
├── MainActivity.kt              Compose host, nav graph, permission gate
├── data/
│   ├── SmritiDb.kt              Room database (v1)
│   ├── RecordEntity.kt          one captured moment
│   ├── TaskEntity.kt            an action item extracted from a record
│   ├── RecordDao.kt
│   └── Converters.kt            FloatArray <-> ByteArray, Instant <-> Long
├── capture/
│   ├── CameraController.kt      CameraX ImageCapture -> JPEG in filesDir/photos
│   ├── Asr.kt                   speech-to-text interface
│   ├── AsrFactory.kt            sherpa-onnx Whisper -> Vosk -> PlatformAsr, first model present wins
│   ├── SherpaWhisperAsr.kt      Whisper small via sherpa-onnx, on device
│   ├── VoskAsr.kt               lightweight offline fallback
│   └── PlatformAsr.kt           Android SpeechRecognizer, last resort
├── ai/
│   ├── LlmBackend.kt            generation interface
│   ├── LiteRtLmHolder.kt        one LiteRT-LM Engine per process; GPU then CPU
│   ├── LiteRtLmBackend.kt       LlmBackend over the Engine; clears the GPU sentinel
│   ├── BackendPolicy.kt         crash sentinel and GPU quarantine
│   ├── BackendFactory.kt        entry point; optional forced backend for the self-test
│   ├── Extractor.kt             photo OCR + transcript -> StructuredRecord
│   ├── Ocr.kt                   ML Kit TextRecognition (Latin + Devanagari)
│   ├── Embedder.kt              MediaPipe TextEmbedder -> FloatArray
│   └── Recall.kt                cosine top-k + answer synthesis
├── ui/
│   ├── CaptureScreen.kt         the only input surface
│   ├── TimelineScreen.kt        reverse-chronological records
│   ├── DetailScreen.kt          photo + transcript + fields + tasks
│   ├── AskScreen.kt             spoken question -> answer + evidence photo
│   └── theme/                   colours, type
└── ModelProvisioner.kt          locates the .litertlm file, reports absence clearly
```

## 2. Data model

```kotlin
@Entity data class RecordEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val createdAt: Long,            // epoch millis
  val photoPath: String,          // absolute path in filesDir/photos
  val ocrText: String,            // ML Kit output, may be empty
  val transcript: String,         // ASR output, may be empty
  val title: String,              // LLM
  val summary: String,            // LLM
  val peopleJson: String,         // LLM, JSON array of strings
  val amountsJson: String,        // LLM, JSON array of {value, currency, label}
  val tagsJson: String,           // LLM, JSON array of strings
  val embedding: ByteArray?       // 1 x N float32, little-endian
)

@Entity data class TaskEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val recordId: Long,
  val text: String,
  val dueDateMillis: Long?,       // null when the model gave no date
  val done: Boolean = false
)
```

`embedding` is a raw float32 blob, not a foreign vector store. See §5.

## 3. Capture pipeline

One button. Press → photo; hold → photo plus voice. Sequence, all on `Dispatchers.IO`:

1. `CameraController.capture()` → JPEG at `filesDir/photos/<epoch>.jpg`, max 1600 px long edge.
2. `Ocr.read(jpeg)` → `ocrText`. ML Kit `TextRecognition` with `DevanagariTextRecognizerOptions`
   falling back to Latin when the Devanagari model reports low confidence.
3. `Asr.transcribe()` → `transcript`.
4. `Extractor.extract(ocrText, transcript)` → `StructuredRecord` (§4).
5. `Embedder.embed(title + summary + ocrText)` → FloatArray → blob.
6. Insert `RecordEntity` + N `TaskEntity`.

Steps 2 and 3 run concurrently. Step 4 cannot start until both finish.

## 4. Structured extraction

`LiteRtLmHolder` holds one LiteRT-LM `Engine` for the process lifetime — loading Gemma per call
costs seconds and is the single easiest way to make the app feel broken.

```kotlin
Engine(EngineConfig(
  modelPath = model.file.absolutePath,
  backend = Backend.GPU(),            // then Backend.CPU() if GPU fails or is quarantined
  maxNumTokens = 4096,                // KV cache is allocated up front; 8 GB shared with camera
  cacheDir = app.cacheDir.absolutePath  // /data/local/tmp is not writable by the app
))
```

GPU is tried first, then CPU. A GPU failure on Mali can be a native `SIGSEGV`, which kills the
process before any fallback runs, so `BackendPolicy` writes a sentinel with `commit()` before
the GPU attempt and it stays armed until the first generation returns. If a later launch finds
it uncleared, the GPU is quarantined for good and the engine comes up on CPU. An ordinary
exception from the GPU clears the sentinel and falls through to CPU without quarantine.

Extraction prompt returns JSON only. A small on-device model will sometimes wrap it in prose or
emit a trailing comma, so `Extractor` must:
- slice from the first `{` to the last `}` before parsing,
- parse with a lenient reader,
- on failure, retry once with a shortened prompt,
- on second failure, store the record with `title = first 60 chars of transcript` and empty
  structured fields. **Never lose the capture because the model misbehaved.**

Schema:
```json
{ "title": "", "summary": "", "people": [], "amounts": [], "tags": [],
  "actions": [ { "text": "", "due": "YYYY-MM-DD or null" } ] }
```

## 5. Recall

Brute-force cosine in Kotlin over every record's embedding. At a few hundred records this is
sub-millisecond; a vector database here is a dependency we would spend a night debugging for
no user-visible gain. Revisit past ~10k records.

```
query -> Embedder.embed(query) -> cosine against all -> top 4
      -> prompt Gemma with those 4 records as context
      -> answer + the photoPath of the top-scoring record as evidence
```

The evidence photo is the product's whole trust story. Always show it.

## 6. Non-negotiables

- **No `android.permission.INTERNET` in the manifest.** This is the demo's proof, and it also
  makes an accidental cloud call impossible rather than merely unlikely.
  `:app:assertNoNetworkPermission` checks the merged manifest after every assemble.
- Model file is never bundled in the APK. `ModelProvisioner` looks in `filesDir/models`, then
  `/data/local/tmp/llm/`, and when absent shows the exact expected paths. It gets there either
  by adb (copy AI Edge Gallery's `gemma-4-E2B-it.litertlm` into `/data/local/tmp/llm/`, chmod
  644) or by the in-app import: tap the model badge on the capture screen and pick a
  `.litertlm`, which is copied into `filesDir/models`.
- `abiFilters += "arm64-v8a"` — keeps the APK near 35 MB instead of 113 MB.
- Every AI call is cancellable and reports which backend it actually used.

## 7. Build order

| # | Task | Depends on |
|---|------|-----------|
| 1 | Gradle skeleton, theme, nav, permission gate | — |
| 2 | Room schema, DAO, converters | 1 |
| 3 | CameraX capture + ML Kit OCR | 1 |
| 4 | ASR behind the `Asr` interface | 1 |
| 5 | `LiteRtLmHolder` + `Extractor` with the JSON-repair path | 2 |
| 6 | `Embedder` + `Recall` | 2, 5 |
| 7 | Capture / Timeline / Detail / Ask screens | 3, 4, 5, 6 |
| 8 | Airplane-mode demo rehearsal + seed data | 7 |
