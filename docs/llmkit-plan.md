# HomeBoy AI Engine Migration — llama.cpp / GGUF (`:llmkit`)

> **Read this first.** This is a self-contained implementation brief for a fresh
> Claude Code session running **locally on a Windows PC**. It carries all the
> context, decisions, exact versions, flags, gotchas, and a file-by-file change
> list needed to build the migration without re-researching. The cloud session
> that wrote this could not build native code (no Android SDK/NDK, and the
> Qualcomm Hexagon SDK is license-gated) — that is why the work moves to the PC.

---

## 0. TL;DR of the goal

Replace the app's **two** on-device AI engines with **one** engine built on
**llama.cpp + GGUF**, serving **both** semantic search (embeddings) and tag
suggestions (generation), with a **NPU → GPU → CPU** execution pipeline
(Qualcomm Hexagon HTP / Adreno OpenCL / CPU), adapted from how PocketPal AI does
it. Package the native engine as a reusable Android library module **`:llmkit`**
(later extractable to its own public repo for use in other apps).

Why: the current MediaPipe (LiteRT `.task`) generation engine can only run a tiny
slice of HuggingFace; GGUF + llama.cpp runs *almost any* HF model and supports
the Hexagon NPU. Confirmed by studying `github.com/a-ghorbani/pocketpal-ai`
(RN → `llama.rn` → llama.cpp → GGUF). We use llama.cpp **directly via JNI**
(not `llama.rn`, which is React-Native-only) because HomeBoy is native
Kotlin/Compose.

---

## 1. Current state (what's already shipped — do not rebuild)

- App: native **Android, Kotlin + Jetpack Compose**, under `android/`.
- Version at migration start: **v1.0.24** (`versionCode 25`), on `main`.
- Target device: **Xiaomi tablet, Snapdragon 8 Elite (Hexagon v79), Android 16, 12 GB RAM**. `minSdk 26`, `compileSdk/targetSdk 35`.
- **Build/release workflow (IMPORTANT):**
  - Push to **main only**: `git push origin HEAD:main` (never a feature branch).
  - CI (`.github/workflows/android.yml`) auto-bumps the version, builds an
    **unsigned-but-self-signed release APK**, creates a GitHub Release, patches
    `apps.json`. If a push is rejected because CI bumped the version first:
    `git fetch origin main && git rebase origin/main && git push origin HEAD:main`.
  - There is **no local Android SDK in the cloud env** — all builds went through
    CI. On the PC you will have a real SDK/NDK, so you can build locally too.
  - Commit author is already `Claude <noreply@anthropic.com>`. The "Unverified"
    stop-hook warning is cosmetic (no GPG key) — ignore it.

### Current AI implementation (the thing being replaced)
All under `android/app/src/main/java/com/homeboy/app/`:

| File | Role today |
|---|---|
| `ai/AiBackend.kt` | enum `AiBackend { NPU, GPU, CPU }` (label/shortLabel). Keep. |
| `ai/EmbeddingEngine.kt` | ONNX Runtime + QNN BERT embeddings (mean-pool + L2). **Replace internals.** |
| `ai/EmbeddingService.kt` | Singleton façade: builds engine from `model.onnx`+`vocab.txt`, caches vectors, `rank(query, items)` cosine re-rank. Keep API, swap file/engine. |
| `ai/WordPieceTokenizer.kt` | Hand-rolled BERT tokenizer (needs `vocab.txt`). **Delete** (llama.cpp tokenizes). |
| `ai/LlmEngineManager.kt` | MediaPipe `LlmInference` lifecycle: `State{Unloaded,Loading,Ready(backend),Generating,Error}`, `ensureLoaded`, `generate`, idle `scheduleUnload`, `unload`. **Keep the state machine & lifecycle; swap internals.** |
| `ai/TagSuggestionService.kt` | Builds prompt from item name+desc, calls `LlmEngineManager.generate`, parses comma list → existing/novel tags. Keep; only model filename changes. |
| `ai/ModelRepository.kt` | Catalog + download manager. `ModelSpec(onnxFileName, vocabFileName, files, purpose)`, `State{NotDownloaded,Downloading(p),Ready,Failed}`, OkHttp streamed download w/ progress StateFlow, custom models persisted as JSON (`CustomEntry{id,name,modelUrl,vocabUrl,purpose}`). **Repurpose to single `.gguf`.** |
| `ai/HuggingFaceRepository.kt` | HF Hub client: `search(query,purpose,token,sort)`, `Compatibility{quantizedOnnx,floatOnnx,mediaPipeFiles,...}`, `classify(files)`, `planDownload(model,purpose,tree)`, `files(id)`, `resolveUrl`, `Sort` enum. **Repurpose to GGUF.** |
| `ui/ai/AiManagementScreen.kt` | Full-screen manager: Semantic-search + Tag-suggestion sections, enable switches, model list (`ModelRow` w/ progress bar + delete), acceleration badge, `LlmStatusRow` (+ manual Unload), memory-timeout dropdown, HF-token field. **Mostly unchanged.** |
| `ui/ai/HuggingFaceSearchScreen.kt` | In-app HF browser: search field, **sort chips** (Downloads/Trending/Recent/Likes), result cards w/ compat badges + download status, detail bottom-sheet using `planDownload` (size/count) + add. **Update copy + GGUF formats.** |
| `ui/settings/SettingsViewModel.kt` | Wires everything; `addHfEmbeddingModel(model,onnxPath,vocabPath)`, `addHfGenModel(model,taskPath)`, `searchHuggingFace`, `setHfSort`, `unloadLlm`, `embedBackend`, `llmState`. |
| `ui/items/AddEditItemViewModel.kt` | `requestTagSuggestions` (debounced 800ms), `aiSuggestionsOn` (combine enabled+model+state), `createNovelTag`. Loads `model.task`. |
| `ui/items/AddEditItemScreen.kt` | `AiTagSuggestions` composable: Loading/Generating/chips + "Suggested by <model> · <tier>". Unchanged. |
| `data/PreferencesRepository.kt` | DataStore keys: `ai_search_enabled, ai_gen_model_id, ai_embed_model_id, ai_custom_models, hf_token, ai_tags_enabled, ai_unload_minutes`. Format-agnostic — **no change.** |
| `app/build.gradle.kts` | has `onnxruntime-android-qnn` + `mediapipe-tasks-genai`, QNN `jniLibs` excludes, `pickFirsts libc++_shared.so`. |
| `gradle/libs.versions.toml` | version refs `onnxruntimeQnn=1.26.0`, `mediapipeGenai=0.10.27`. |
| `app/proguard-rules.pro` | keep rules for `ai.onnxruntime.**`, `com.google.mediapipe.**`, protobuf. |

There is **no `settings.gradle.kts` module list** yet beyond `:app` — verify and
add `:llmkit`.

---

## 2. Decisions (locked by the user)

1. **Replace** both MediaPipe (generation) and ONNX (embeddings) — do not keep them.
2. **Unify embeddings + generation on llama.cpp/GGUF** (accept that embeddings now
   run on NPU/GPU/CPU via llama.cpp rather than the old ONNX-on-NPU path).
3. **Build the native engine on the Windows PC** (Hexagon SDK can't run in CI).
4. NPU/GPU/CPU pipeline adapted from PocketPal / upstream llama.cpp.
5. `:llmkit` lives in-repo now; **extract to a public repo + JitPack later**.

---

## 3. Toolchain prerequisites (Windows PC) — install these first

The fresh local session should help install/verify:

- **JDK 17** (Android Gradle Plugin 8.5.2 requires 17).
- **Android Studio** + SDK with **compileSdk 35** / platform-tools.
- **Android NDK r28b** (≥ r28 — r28 aligns shared libs to **16 KB pages** by
  default, which is required for Android 16 on the 8 Elite; older NDKs SIGBUS at
  model load). Set in `local.properties` or `ANDROID_NDK_HOME`.
- **CMake 3.22+** (via SDK Manager).
- **Git** + **Git LFS** (the built AAR with native `.so` can exceed 50 MB).
- **Qualcomm Hexagon SDK 6.x** (tested 6.6.0.0), Community Edition — free, but
  requires a Qualcomm account and install via **Qualcomm Package Manager (QPM3)**.
  Windows is supported. After install set `HEXAGON_SDK_ROOT` (and source the
  SDK's `setup_sdk_env` equivalent on Windows). The SDK is **~2 GB and
  non-redistributable** — do not commit it.
- **OpenCL headers** (Khronos `CL/` headers) for the Adreno GPU backend
  (`-DGGML_OPENCL=ON`). The NDK ships an OpenCL loader stub; headers may need to
  be added. The Adreno GPU at runtime uses the device's `libOpenCL.so`.

> If the Hexagon SDK proves painful, you can ship a **CPU + OpenCL-only** AAR
> first (no Hexagon SDK needed) and add the NPU backend in a second pass. The
> Kotlin/UI side is identical either way.

---

## 4. `:llmkit` module — what to create

### 4.1 Layout
```
android/
  settings.gradle.kts            # add include(":llmkit")
  llmkit/
    build.gradle.kts
    src/main/AndroidManifest.xml # <uses-native-library libcdsprpc.so required=false/>
    src/main/cpp/
      CMakeLists.txt
      llmkit.cpp                 # JNI bridge
      llama.cpp/                 # git submodule, pinned tag
    src/main/java/com/homeboy/llmkit/
      Backend.kt                 # enum AUTO, NPU, GPU, CPU
      LlmKit.kt                  # Kotlin API + native method declarations
```

### 4.2 Add the submodule (pin a known-good tag)
```
cd android/llmkit/src/main/cpp
git submodule add https://github.com/ggml-org/llama.cpp
cd llama.cpp && git checkout <recent-stable-tag>   # pin; verify Hexagon+OpenCL present
```
Verify the Snapdragon backend docs exist at
`llama.cpp/docs/backend/snapdragon/README.md` and that `ggml/src/ggml-hexagon`
and `ggml/src/ggml-opencl` are present for the pinned tag.

### 4.3 `llmkit/build.gradle.kts` (essentials)
```kotlin
plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android) }
android {
    namespace = "com.homeboy.llmkit"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }       // device is arm64 only
        externalNativeBuild { cmake {
            arguments += listOf(
                "-DGGML_OPENCL=ON",
                "-DGGML_HEXAGON=ON",             // omit for the CPU/OpenCL-only first pass
                "-DGGML_OPENMP=OFF",             // OpenMP conflicts w/ Hexagon hybrid
                "-DLLAMA_CURL=OFF",
                "-DLLAMA_BUILD_TESTS=OFF",
                "-DLLAMA_BUILD_EXAMPLES=OFF"
            )
            cppFlags += "-O3"
        } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // NDK r28 handles 16KB page alignment; if on older NDK add linker flag:
    //   -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
    buildFeatures { /* none needed */ }
}
```

### 4.4 `CMakeLists.txt` (skeleton)
```cmake
cmake_minimum_required(VERSION 3.22)
project(llmkit)
add_subdirectory(llama.cpp)          # builds ggml + llama (+hexagon/opencl per flags)
add_library(llmkit SHARED llmkit.cpp)
target_link_libraries(llmkit llama ggml android log)
```

### 4.5 JNI bridge `llmkit.cpp` — functions to expose
Implement against the **llama.cpp C API** (`llama.h`, `ggml-backend.h`). Minimum:
- `nativeInit()` — `llama_backend_init()`, register backends.
- `nativeLoadModel(path, nGpuLayers, backendHint) -> handle` — set
  `llama_model_params.n_gpu_layers`; for Hexagon select the HTP device via
  `ggml_backend_dev_by_name`/`devices` list; create `llama_context`.
- `nativeGenerate(handle, prompt, maxTokens, temp, topK) -> String` (and/or a
  streaming variant invoking a Kotlin callback per token).
- `nativeEmbed(handle, text) -> FloatArray` — context with
  `llama_context_params.embeddings = true`, `pooling_type = MEAN` (or model
  default); `llama_get_embeddings_seq`; L2-normalize.
- `nativeProbeBackends() -> String[]` — enumerate `ggml_backend_dev_*` so the app
  can report NPU/GPU availability.
- `nativeFree(handle)`, `nativeBackendFree()`.

> Tokenization, sampling, KV-cache are all handled inside llama.cpp — do not
> reimplement. Use `common`/`llama_sampler` helpers if convenient.

### 4.6 `Backend.kt` / `LlmKit.kt`
```kotlin
enum class Backend { AUTO, NPU, GPU, CPU }
object LlmKit {
    init { System.loadLibrary("llmkit") }
    external fun nativeInit()
    external fun nativeLoadModel(path: String, nGpuLayers: Int, backend: Int): Long
    external fun nativeGenerate(h: Long, prompt: String, maxTokens: Int, temp: Float, topK: Int): String
    external fun nativeEmbed(h: Long, text: String): FloatArray
    external fun nativeProbeBackends(): Array<String>
    external fun nativeFree(h: Long)
    // Kotlin-friendly wrappers + the engaged-tier detection live here.
}
```

### 4.7 AndroidManifest (Hexagon FastRPC loader)
```xml
<uses-native-library android:name="libcdsprpc.so" android:required="false"/>
```

### 4.8 Runtime backend selection (NPU → GPU → CPU)
- **NPU (Hexagon):** select the HTP device/backend when the GGUF is quantized and
  the SoC exposes it; model must be **< ~4B params** (32-bit cDSP, ~3.5 GB per
  session). Env knob `GGML_HEXAGON_NDEV` controls session count.
- **GPU (Adreno):** `n_gpu_layers = 99` with the OpenCL backend (best with
  `Q4_0`-family quants; FP16 also works).
- **CPU:** `n_gpu_layers = 0`, universal fallback.
- Implement try-NPU → try-GPU → CPU and report which engaged → feed
  `AiBackend.{NPU,GPU,CPU}` to the existing UI badges.

---

## 5. App-side migration (file-by-file)

> Keep every public façade, ViewModel, and most Compose UI. Only swap engine
> internals + the model file format (`*.onnx`/`*.task` → `*.gguf`) and delete the
> tokenizer.

- **`ai/EmbeddingEngine.kt`** — gut ONNX/QNN; new `create(modelFile: File): EmbeddingEngine?`
  builds via `LlmKit.nativeLoadModel(..., embeddings)`, `embed(text)` → `LlmKit.nativeEmbed`.
  Drop `WordPieceTokenizer`, mean-pool, normalize (do normalize in JNI or keep a
  tiny L2 helper). Keep `companion cosine()` and `backend: AiBackend`.
- **`ai/EmbeddingService.kt`** — `fileFor(ctx,id,"model.gguf")`; `EmbeddingEngine.create(modelFile)`
  (no vocab). Everything else (`vectorCache`, `rank`) unchanged.
- **`ai/WordPieceTokenizer.kt`** — **delete** + remove all references.
- **`ai/LlmEngineManager.kt`** — replace MediaPipe import/`LlmInference` with
  `LlmKit`; `buildEngine` tries NPU/GPU/CPU and returns the tier; `generate` calls
  `LlmKit.nativeGenerate`. Keep `State`, mutex, `scheduleUnload`, `unload`,
  internal scope. File is now `model.gguf`.
- **`ai/TagSuggestionService.kt`** — no logic change; it just loads whatever file
  the manager points to. (Optionally apply the model's chat template to the prompt.)
- **`ai/ModelRepository.kt`** — `ModelSpec`: drop `onnxFileName`/`vocabFileName`,
  add `modelFileName = "model.gguf"`. `CATALOG`: curated GGUF entries (see §6).
  `CustomEntry.toSpec()`: both purposes → single `ModelFile(url,"model.gguf")`.
  `addCustomModel`/`addCustomGenModel`: one `.gguf` URL (no vocab). `download`
  post-step: invalidate `EmbeddingService` (EMBEDDING) or `LlmEngineManager.unload()`
  (GENERATION) — already in place; keep.
- **`ai/HuggingFaceRepository.kt`**
  - `Compatibility` → `{ ggufQuantized: List<String>, ggufFloat: List<String> }`
    with `hasGguf`/`isRunnable`; `best = quant? NPU : float? GPU : null`.
  - `search`: use `?filter=gguf` + `&sort=...&direction=-1&limit=100&full=true`
    (+ `pipeline_tag` optional; drop the litert-community scoping). Same call for
    both purposes — GGUF serves both.
  - `classify`: detect `.gguf`; quant hints: `q2_k,q3_k,q4_0,q4_k_m,q5_k_m,q6_k,q8_0`
    → quantized (NPU-capable); `f16,f32,bf16` → float (GPU).
  - `planDownload`: pick ONE `.gguf` (prefer a mid quant like `Q4_K_M`, else
    smallest) → `[PlannedFile(path,"model.gguf",size)]`. This already fixed the
    "whole-repo size" bug for the old formats — keep the single-file approach.
- **`ui/ai/HuggingFaceSearchScreen.kt`** — empty-state "No matching GGUF models";
  detail-sheet `formatOk = plan.any { it.localName == "model.gguf" }`; `onAdd`
  passes one URL for both purposes; `RunsOnLine`: quantized→NPU, float→GPU, +CPU;
  keep sort chips; (optional) add a quant picker. Badges already map to
  `compat.best`.
- **`ui/ai/AiManagementScreen.kt`** — no functional change; copy tweaks only
  ("Quantized GGUF runs on the NPU…"). Acceleration/`LlmStatusRow`/unload all work.
- **`ui/settings/SettingsViewModel.kt`** — `addHfEmbeddingModel(model, ggufPath)` /
  `addHfGenModel(model, ggufPath)` (single URL, empty vocab); rest unchanged.
- **`ui/items/AddEditItemViewModel.kt`** — load `model.gguf` instead of `model.task`.
- **`data/PreferencesRepository.kt`** — unchanged.
- **`app/build.gradle.kts`** — remove `onnxruntime-android-qnn` + `mediapipe-tasks-genai`;
  add `implementation(project(":llmkit"))`. Remove the QNN `jniLibs` excludes
  (no QNN libs anymore). Keep `pickFirsts += "**/libc++_shared.so"`.
- **`gradle/libs.versions.toml`** — remove the two version refs + library defs.
- **`app/proguard-rules.pro`** — remove ORT/MediaPipe/protobuf keeps; add
  `-keep class com.homeboy.llmkit.** { *; }` and `-dontwarn com.homeboy.llmkit.**`.
- **`settings.gradle.kts`** — `include(":app", ":llmkit")`.

---

## 6. Curated GGUF catalog (starting points — verify URLs on device/HF)
- **Generation (≤3B for NPU):** Gemma-2-2B-it, Llama-3.2-1B/3B-Instruct, Qwen2.5-1.5B/3B-Instruct — GGUF from `bartowski`/`unsloth`/`lmstudio-community`, quant **Q4_K_M**.
- **Embeddings:** `nomic-embed-text-v1.5`, `bge-small/base-en-v1.5`, or `all-MiniLM-L6-v2` GGUF (search `?filter=gguf` + the model name). Use mean pooling.
- Per-repo: one `.gguf` per quant; download a single file (see `planDownload`).

---

## 7. CI vs PC build & distribution
- **PC:** `./gradlew :llmkit:assembleRelease` → produces `llmkit-release.aar` with
  the native `.so`s (`libllmkit.so`, `libllama.so`, `libggml*.so`, hexagon HTP
  skel, opencl). 
- **Distribute** so GitHub Actions (no NDK/Hexagon SDK) can build the app:
  - **Option A (simple):** commit the prebuilt AAR to `android/app/libs/llmkit.aar`
    via **Git LFS**; app consumes `implementation(files("libs/llmkit.aar"))`.
  - **Option B (clean):** keep `:llmkit` as a source module but have CI build
    **CPU+OpenCL-only** (no Hexagon SDK needed) — loses NPU in CI-built APKs but
    keeps the module buildable. NPU APKs come from the PC build.
  - Recommended: build the full (NPU) AAR on PC, distribute via **Option A**;
    keep the `:llmkit` source for PC rebuilds.
- App CI stays the push-to-main flow; just ensure it doesn't try to run the NDK.

---

## 8. Gotchas (will cause errors if missed)
1. **NDK r28+** (16 KB page alignment) — else SIGBUS on model load (Android 16 / 8 Elite).
2. **`-DGGML_OPENMP=OFF`** — OpenMP is incompatible with the Hexagon hybrid backend on Android.
3. **`arm64-v8a` only** — don't build other ABIs (size + irrelevant).
4. **Hexagon model size < ~4B** params (32-bit cDSP). Tag suggestions use 1–3B — fine.
5. **`<uses-native-library libcdsprpc.so required=false>`** in the manifest for FastRPC.
6. **Git LFS** for the AAR (can be >50 MB).
7. **Hexagon SDK is non-redistributable** — never commit it; only the built `.so`s.
8. Pin the **llama.cpp submodule tag**; APIs move fast (`llama_get_embeddings_seq`, sampler API, backend device API have all churned).
9. GGUF chat models need their **chat template** for good output; embeddings need a model that supports **pooling**.
10. Keep the existing `pickFirsts libc++_shared.so` (llmkit + any lib may both ship it).

---

## 9. Verification (end-to-end, on the device)
1. Build `:llmkit` on PC; assemble app; sideload to the 8 Elite.
2. Settings → AI & Models → **Tag suggestions** → Browse → download a Gemma-2B
   Q4_K_M GGUF. Add an item, type a name → AI chips appear; manager shows
   **"Loaded · NPU (Hexagon)"**. Confirm idle auto-unload + manual Unload.
3. **Semantic search** → download an embedding GGUF → search "couch" finds "sofa".
4. Force fallbacks (e.g. a float GGUF) → manager shows **GPU**, then **CPU**.
5. Confirm GitHub Actions builds the APK from the prebuilt AAR with **no NDK step**.

---

## 10. Later: reusable library
Extract `android/llmkit` to its own **public** GitHub repo, publish via **JitPack**,
and consume from HomeBoy + future apps with a single Gradle coordinate. The Kotlin
API (`LlmKit` + `Backend`) is the stable surface; keep the GGUF model-manager and
HF browser app-side (or generalize them into the library too).
