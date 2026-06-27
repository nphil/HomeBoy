package com.homeboy.llmkit

/**
 * Execution backend for the llama.cpp engine, in descending order of speed/efficiency.
 *
 * The engine tries [NPU] → [GPU] → [CPU] and reports which one actually engaged so the host app
 * can tell the user where inference is happening.
 *
 * - [AUTO]: let [LlmKit] pick the best available backend (NPU → GPU → CPU). Only used as a *request*
 *   hint when loading a model; a load never reports back `AUTO`.
 * - [NPU]: Qualcomm Hexagon (HTP) backend. Fastest + most power-efficient. Accepts quantized GGUF
 *   only, and the model must be small enough to fit the 32-bit cDSP (< ~4B params).
 * - [GPU]: Adreno via the OpenCL backend. Runs float/quantized GGUF the NPU can't; slower than NPU
 *   but far above CPU. Best with `Q4_0`-family quants; FP16 also works.
 * - [CPU]: llama.cpp's CPU backend. Universal fallback; works with any GGUF.
 */
enum class Backend(val ordinalHint: Int) {
    AUTO(-1),
    NPU(0),
    GPU(1),
    CPU(2);

    companion object {
        /** Map a native tier index (as returned by [LlmKit] load calls) back to a [Backend]. */
        fun fromNative(index: Int): Backend = when (index) {
            0 -> NPU
            1 -> GPU
            else -> CPU
        }
    }
}
