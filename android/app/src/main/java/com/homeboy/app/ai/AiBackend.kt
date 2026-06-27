package com.homeboy.app.ai

/**
 * Which hardware tier an ONNX Runtime session ended up running on, in descending order of
 * speed/efficiency. The engines try NPU → GPU → CPU and report back which one engaged so the
 * UI can tell the user honestly where inference is happening.
 *
 * - [NPU]: Qualcomm Hexagon (QNN HTP backend). Fastest + most power-efficient, but only accepts
 *   quantized (QDQ INT8/INT4) models. A float model silently won't load here.
 * - [GPU]: Adreno via the QNN GPU backend. Runs FP16/FP32 models that the NPU can't, ~2–3× slower
 *   than NPU but still far above CPU. Broadens the set of HuggingFace models that work.
 * - [CPU]: ONNX Runtime's default CPU provider. Universal fallback; works with any ONNX model.
 */
enum class AiBackend(val label: String, val shortLabel: String) {
    NPU("NPU (Hexagon)", "NPU"),
    GPU("GPU (Adreno)", "GPU"),
    CPU("CPU", "CPU");

    /** Map to the [com.homeboy.llmkit.Backend] hint used when loading a model. */
    fun toKitBackend(): com.homeboy.llmkit.Backend = when (this) {
        NPU -> com.homeboy.llmkit.Backend.NPU
        GPU -> com.homeboy.llmkit.Backend.GPU
        CPU -> com.homeboy.llmkit.Backend.CPU
    }

    companion object {
        /** Parse a stored override token ("NPU"/"GPU"/"CPU"); null/unknown → null. */
        fun fromToken(token: String?): AiBackend? = when (token) {
            "NPU" -> NPU
            "GPU" -> GPU
            "CPU" -> CPU
            else -> null
        }
    }
}
