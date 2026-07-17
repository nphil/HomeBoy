# Changelog

All notable changes to HomeBoy Android are listed here. Versions are auto-assigned by CI on each push to main.

---

## v1.0.37 — 2026-07-17

### Themes
- **30 new themes — 15 light + 15 dark** — replacing the old web-ported set. Curated, distinct palettes (Indigo Dawn, Porcelain Teal, Rosewater, Amber Grove, Meadow, Sky Harbor, Lavender Mist, Coral Reef, Sandstone, Sakura, Glacier, Olive Grove, Copper Slate, Cobalt, Graphite / Midnight Indigo, Obsidian Teal, Ember, Deep Forest, Midnight Ocean, Velvet Grape, Carbon Rose, Nordic Night, Espresso, Neon Noir, Abyss, Pitch Black, Aurora, Honey Amber, Steel), shared 1:1 with the iOS app so both platforms can match. Material You stays available.
- Theme picker: scrollable with System / Light / Dark sections, two-tone swatches showing each theme's real background + primary, and a selection check that stays legible on light palettes.

---

## v1.0.36 — 2026-07-17

### App icon
- **Fixed clipped launcher icon**: the hexagon glyph extended past the adaptive-icon safe zone (the central 66 dp circle of the 108 dp canvas), so circular and squircle launcher masks cut off its points. The glyph is redrawn to fit every mask shape with proper breathing room.
- **Modern theming**: subtle indigo gradient background (vector, not a flat color) and a monochrome layer so Android 13+ "themed icons" render it in your wallpaper's Material You palette. All layers are vector drawables — crisp at every size.

---

## v1.0.35 — 2026-07-17

### Offline photos
- **Photo uploads now work offline**: a photo added while the server is unreachable is stored on-device (`pending_photos/`), counted in the status badge, and uploaded automatically when the connection returns — including photos attached to items that were themselves created offline. Previously an offline photo upload failed silently and the photo was lost.
- **Pending photos are visible immediately**: they appear in the item's detail gallery, the fullscreen viewer, and as the list thumbnail (served from the local file) until the upload syncs.
- **Photos render offline**: every sync now caches the item details the list view doesn't carry and warms Coil's disk cache with all known photos, so galleries and thumbnails work with no connection — not just for photos you happened to view before.

---

## v1.0.34 — 2026-07-16

### Offline mode & sync
- **Fixed items list going blank after login**: a transient server error (e.g. HTTP 500/502 while Homebox restarts) threw a plain exception that bypassed the offline cache fallback, leaving every list empty. HTTP errors are now typed; transient ones (5xx/408/429 and network failures) retry with backoff and then fall back to locally cached data.
- **Full offline mode**: items, locations, tags, item details, maintenance logs, and statistics are all served from the on-device cache when the server is unreachable. Creates/edits/deletes of items, locations, tags, *and maintenance entries* made offline are queued locally and replayed automatically when the connection returns.
- **Automatic reconnect + sync**: the app listens for network changes and probes an unreachable server every 30 s. When the connection returns it replays queued changes, refreshes all caches, and every screen reloads by itself.
- **Connection status indicator** in the top bar of every tab: green cloud = connected, red cloud with a badge = offline with N unsynced local changes, spinning amber = syncing. Tap it for details and a manual "Sync now".
- Statistics screen keeps showing its last data instead of zeroing out when a fetch fails; replaying mutations is now serialized so parallel screen loads can't double-apply queued changes.

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
