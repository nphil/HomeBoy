import Foundation

/// Execution backend for llama.cpp on iOS. There is **no NPU** path — llama.cpp runs
/// on the Metal GPU or the CPU (the Neural Engine is reachable only via Apple frameworks).
enum LlamaBackend: String, CaseIterable, Identifiable, Sendable {
    case auto   // prefer Metal GPU
    case gpu
    case cpu

    var id: String { rawValue }
    var gpuLayers: Int32 { self == .cpu ? 0 : 99 }

    var displayName: String {
        switch self {
        case .auto: return "Auto"
        case .gpu:  return "GPU (Metal)"
        case .cpu:  return "CPU"
        }
    }
}

/// Swift facade over the Objective-C++ `LlamaBridge`. Handles are opaque `UInt64`
/// values; the caller owns the lifecycle (load → use → free). Every call blocks, so
/// invoke from an actor/background task, never the main thread.
enum LlmKit {
    static func probeBackends() -> [String] {
        LlamaBridge.initBackend()
        return LlamaBridge.probeBackends()
    }

    /// Load a GGUF model. Returns a non-zero handle, or nil on failure.
    static func loadModel(path: String, embeddings: Bool, backend: LlamaBackend) -> UInt64? {
        LlamaBridge.initBackend()
        let h = LlamaBridge.loadModel(atPath: path, embeddings: embeddings, gpuLayers: backend.gpuLayers)
        return h == 0 ? nil : h
    }

    static func generate(_ handle: UInt64, prompt: String,
                         maxTokens: Int32 = 128, temperature: Float = 0.7, topK: Int32 = 40) -> String {
        LlamaBridge.generate(withHandle: handle, prompt: prompt,
                             maxTokens: maxTokens, temperature: temperature, topK: topK)
    }

    static func chat(_ handle: UInt64, system: String, user: String,
                     maxTokens: Int32 = 128, temperature: Float = 0.4, topK: Int32 = 20) -> String {
        LlamaBridge.chat(withHandle: handle, system: system, user: user,
                         maxTokens: maxTokens, temperature: temperature, topK: topK)
    }

    static func embed(_ handle: UInt64, text: String) -> [Float] {
        LlamaBridge.embed(withHandle: handle, text: text).map { $0.floatValue }
    }

    static func free(_ handle: UInt64) {
        LlamaBridge.freeHandle(handle)
    }
}
