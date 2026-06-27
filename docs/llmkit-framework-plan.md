# `:llmkit` — Benchmark-Validated Framework & Integration Plan (v4)

> Supersedes the strategy/routing parts of `llmkit-plan.md` with on-device benchmark data.
> The mechanics (module layout, JNI surface, file-by-file app changes) in `llmkit-plan.md` still hold.

## What we proved on device (Snapdragon 8 Elite / Hexagon v79, Q4_0)

| Phase | NPU | GPU (Adreno) | CPU (Oryon) |
|---|---|---|---|
| Prefill pp128 — 1B / 4B | **1635 / 539** | 802 / 113 | 513 / 109 |
| Token-gen tg64 — 1B / 4B | 33.7 / 14.5 | 44.8 / 14.5 | **68.7 / 23.4** |

Graph-compiled QNN/Genie (Llama-1B, same chip): NPU **40.1 t/s** gen — beats llama.cpp's NPU (33.7,
+19%, so op-by-op dispatch *is* a real bottleneck) but still loses to CPU (68.7). Token-gen is
memory-bandwidth-bound; the NPU's edge is compute (prefill). Conclusion holds up to 4B; a graph-compiled
3B–8B *might* beat CPU at gen (untested — relevant only to the future coding app, not HomeBoy tags).

## Core design decisions

1. **One GGUF engine, per-workload backend routing** (the central learning):
   - **Embeddings / semantic search → NPU** (3–5× faster prefill; the win grows with model size).
   - **Tag generation → CPU** (fastest even vs QNN for small models).
   - **GPU → fallback** (other SoCs, or float models the NPU rejects).
   `LlmKit` already carries a `Backend` hint per call; expose routing as host-settable policy.
2. **Q4_0 is the universal quant** — accelerated by NPU *and* GPU *and* CPU (Hexagon only repacks
   Q4_0/Q8_0/MXFP4). One model file works everywhere.
3. **Build NPU natively in WSL2, distribute prebuilt** — Windows/CI cannot build the Hexagon backend.
   Ship a prebuilt AAR via Git LFS so the push-to-main CI keeps building the app with no NDK.
4. **Portable by construction** — `:llmkit` is a standalone library (Kotlin `LlmKit`+`Backend` API,
   GGUF model manager, HF browser). Reuse verbatim in the future agentic coding app, where the NPU
   *prefill* win (long-context / RAG over a codebase) is the headline feature.

## Choosing a tag-generation model (for HomeBoy)

Tag-gen = "name + description → a few tags": a **0.5–1.5B instruct model is plenty** (1B sweet spot;
3B+ is wasted memory/latency). HF selection checklist:

- **Format** GGUF · **Quant** Q4_0 · **Arch** standard transformer (Llama, Qwen2.5/Qwen3-dense, Gemma,
  Phi, Mistral — *not* MoE / Mamba / DeltaNet=Qwen3.5) · **Instruct-tuned** · reputable quantizer
  (bartowski / unsloth / ggml-org / lmstudio-community).
- Good defaults: **Llama-3.2-1B-Instruct Q4_0** or **Qwen2.5-1.5B-Instruct Q4_0**.
- Bake these rules into the curated catalog + the in-app HF browser's compatibility classifier.

## HomeBoy integration (task 10)

1. Build `libllmkit.so` in WSL2 against the same toolchain (link libllama/ggml).
2. Bundle `libllama` + `libggml*` + `libggml-hexagon` + `libggml-htp-v79` + `libggml-opencl` +
   `libllmkit` into `:llmkit/jniLibs/arm64-v8a` (or the prebuilt AAR).
3. `LlmKit.init()` sets `ADSP_LIBRARY_PATH` = `applicationInfo.nativeLibraryDir` (FastRPC skel discovery).
4. `EmbeddingService` → `LlmKit.embed` on **NPU**; `TagSuggestionService` → `LlmKit.generate` on **CPU**.
   Delete `WordPieceTokenizer`. Add robust load-failure handling + backend fallback (kills the old
   MediaPipe/LiteRT `.task` load-error class — gen is verified working under llama.cpp).
5. `ModelRepository` / HF browser → single `.gguf`, Q4_0, selection rules above.
6. Drop `onnxruntime-android-qnn` + `mediapipe-tasks-genai`; add `:llmkit`. Distribute prebuilt AAR via
   Git LFS; CI builds the app with no NDK step.

## Deferred / optional
- Graph-compiled (QNN-Genie or LiteRT-QNN) generation path — only if a 3B–8B NPU-gen benchmark beats
  CPU and the coding app needs it. LiteRT is the more *portable* graph-compiled option (cross-vendor).
