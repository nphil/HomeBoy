# Changelog

All notable changes to HomeBoy Android are listed here. Versions are auto-assigned by CI on each push to main.

---

## v1.0.30 — 2026-06-28

### AI & Models
- **Better tag suggestions**: the prompt now asks the model to generate tags for the *item itself* rather than picking from your existing tag library. Previously a small model would force-fit unrelated existing tags (e.g. tagging "black pepper" as "Pesticides") instead of suggesting the obvious new ones ("Spices", "Cooking"). New tags now reliably appear with a `+` to create them.
- **Existing-tag matching moved fully into code** and made plural/case-insensitive, so a generated "Spices" still takes precedence over an existing "Spice" (shown without a `+`). Existing tags keep their stored spelling.

---

## v1.0.29 — 2026-06-28

### AI & Models
- **Chat template support**: `nativeGenerateChat` now applies each model's built-in GGUF chat template via `llama_chat_apply_template`, so instruct models (Qwen3, Llama-Instruct, etc.) receive properly formatted prompts instead of raw text concatenation. Tag suggestions are significantly more accurate as a result.
- **Fixed generation model**: catalog entry corrected from non-existent `Qwen3.5-1.7B` to `Qwen3 1.7B` — downloads now succeed. Old entry returned HTTP 401 (HuggingFace repo didn't exist).
- **Better embedding model**: swapped BGE Base EN v1.5 (~110 MB) for **BGE Large EN v1.5** (~358 MB, `bert` arch). Highest retrieval accuracy in the llama.cpp-compatible English embedding tier.

### Embedding catalog (final English-only lineup)
| Model | Size | Purpose |
|---|---|---|
| BGE Small EN v1.5 | 35 MB | Lightweight / quick download |
| Nomic Embed v1.5 *(default)* | 145 MB | Balanced; query/document prefix-tuned |
| BGE Large EN v1.5 | 358 MB | Highest accuracy |

---

## v1.0.28 — 2026-06-27

### AI & Models
- **Tag suggestions use chat-template path**: switched `TagSuggestionService` from raw `generate()` to `generateChat(system, user)` so system/user roles are correctly separated before the model sees them.
- **Qwen3 thinking suppression**: system prompt now includes `/no_think` directive; parser strips any `<think>…</think>` blocks that leak through anyway.
- **Title-cased novel tags**: AI-proposed new tags are now displayed as "Home Improvement" rather than "home improvement". Preserves intentional casing (USB, iPhone, etc.).
- **Nomic Embed catalog fix**: corrected GGUF URL to Q8_0 quantisation (was pointing at wrong quant).
- **Backend chip**: "CPU / NPU / GPU" badge on tag chip now reflects the actual backend used for the most recent generation call.

---

## v1.0.27 — 2026-06-26

### AI & Models
- **Last-used backend shown on AI chip**: the tag suggestion chip now displays the real backend (CPU / NPU / GPU) observed during the last run, instead of always showing CPU.

---

## v1.0.26 — 2026-06-25

### AI & Models
- **Unified on-device AI engine** (`:llmkit`): replaced MediaPipe LLM Task API with a custom llama.cpp/GGUF JNI bridge. All models (embeddings + generation) now share a single native engine backed by the same Snapdragon-optimised llama.cpp build used for semantic search.
- NPU (Hexagon HTP), Adreno GPU (OpenCL), and CPU backends — auto-selected per workload.

---

## v1.0.25 — 2026-06-22

### Items
- Fixed HuggingFace model browser: real download sizes from `content-length`, LLM search, sort controls, download progress indicator.

---

## v1.0.24 — 2026-06-20

### AI & Models
- AI tag suggestions via on-device MediaPipe LLM (first generation).

---

## v1.0.23 — 2026-06-18

### AI & Models
- Full-screen model manager, NPU/GPU/CPU tier indicators, in-app HuggingFace search.

---

## v1.0.22 — 2026-06-17

### AI & Models
- Model catalog with multiple curated models, default selection, and custom HuggingFace URL support.

---

## v1.0.21 — 2026-06-16

### AI & Models
- Engaged real Snapdragon NPU via Qualcomm QNN HTP: context caching, performance mode, status indicator in UI.

---
