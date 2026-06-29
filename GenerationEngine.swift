import Foundation

/// Shared on-device GGUF generation engine (llama.cpp / Metal). Lazily loads the
/// selected model, runs chat completions off the main thread, and auto-unloads after an
/// idle period to free the ~1 GB of RAM a generation model uses.
///
/// Singleton so the (env-decoupled) Add-Item screen and AIModelManager share one
/// resident model. Configured by `AIModelManager`.
actor GenerationEngine {
    static let shared = GenerationEngine()

    private var handle: UInt64?
    private var modelId: String?
    private var path: String?
    private var backend: LlamaBackend = .gpu   // generation prefers the Metal GPU on iOS
    private var unloadMinutes = 5
    private var useToken = 0

    /// Point the engine at a downloaded generation model. Unloads any previous model
    /// when the selection changes.
    func configure(modelId: String?, path: String?, backend: LlamaBackend, unloadMinutes: Int) {
        if modelId != self.modelId || path != self.path || backend != self.backend {
            unload()
            self.modelId = modelId
            self.path = path
            self.backend = backend
        }
        self.unloadMinutes = unloadMinutes
    }

    var isReady: Bool { handle != nil }

    /// Run a chat completion. Returns nil if no model is configured/loadable.
    func generate(system: String, user: String,
                  maxTokens: Int32 = 128, temperature: Float = 0.4, topK: Int32 = 20) async -> String? {
        guard let h = ensureLoaded() else { return nil }
        useToken += 1
        let token = useToken
        let out = LlmKit.chat(h, system: system, user: user,
                              maxTokens: maxTokens, temperature: temperature, topK: topK)
        scheduleUnload(token: token)
        return out
    }

    func unload() {
        if let h = handle { LlmKit.free(h); handle = nil }
    }

    private func ensureLoaded() -> UInt64? {
        if let h = handle { return h }
        guard let path, FileManager.default.fileExists(atPath: path) else { return nil }
        let order: [LlamaBackend] = (backend == .cpu) ? [.cpu] : [backend, .cpu]
        for b in order {
            if let h = LlmKit.loadModel(path: path, embeddings: false, backend: b) {
                handle = h
                return h
            }
        }
        return nil
    }

    private func scheduleUnload(token: Int) {
        guard unloadMinutes > 0 else { return }
        let minutes = unloadMinutes
        Task {
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60 * 1_000_000_000)
            await self.unloadIfIdle(token)
        }
    }

    private func unloadIfIdle(_ token: Int) {
        if token == useToken { unload() }   // no newer use since → free it
    }
}
