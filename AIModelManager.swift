import Foundation
import Combine
import FoundationModels

/// Provider for semantic ("did you mean…") search — only runs when a literal
/// search finds nothing.
enum EmbedProvider: String, CaseIterable, Identifiable, Sendable {
    case appleLLM          // Foundation Models — world knowledge, best quality (default)
    case appleContextual   // NLContextualEmbedding (Neural Engine) — fast vector similarity
    case appleNL           // legacy NLEmbedding word+sentence (threshold 1.15)
    case gguf              // llama.cpp GGUF embedder — Phase 2

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .appleLLM:        return "Smart (Apple Intelligence)"
        case .appleContextual: return "Fast (embeddings)"
        case .appleNL:         return "Basic"
        case .gguf:            return "Hybrid (downloaded + AI)"
        }
    }

    var detail: String {
        switch self {
        case .appleLLM:        return "Understands what products are — finds “Round Up” for “pesticide”. Slower; needs Apple Intelligence."
        case .appleContextual: return "Neural-Engine embeddings · fast, but limited world knowledge"
        case .appleNL:         return "Lightweight word match · always available"
        case .gguf:            return "Downloaded embedder shortlist + Apple Intelligence rerank — fast and accurate"
        }
    }
}

/// Generation provider for AI tag suggestions.
enum LLMProvider: String, CaseIterable, Identifiable, Sendable {
    case apple   // Foundation Models (Neural Engine) — default
    case gguf    // llama.cpp GGUF — Phase 2

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .apple: return "Apple Intelligence"
        case .gguf:  return "Downloaded model"
        }
    }

    var detail: String {
        switch self {
        case .apple: return "On-device Foundation Models (Neural Engine)"
        case .gguf:  return "Qwen3 / Llama GGUF via llama.cpp (Metal)"
        }
    }
}

/// Top-level on-device AI coordinator: owns the feature toggles, provider selection,
/// and the shared embedding / tag-suggestion services. Injected app-wide as an
/// `@EnvironmentObject` (alongside `HomeboxStore` and `ThemeManager`).
///
/// Defaults to Apple's Neural-Engine providers (no download). Downloadable GGUF
/// models via llama.cpp are added as an opt-in provider in Phase 2.
@MainActor
final class AIModelManager: ObservableObject {
    @Published var searchEnabled: Bool {
        didSet { defaults.set(searchEnabled, forKey: Keys.searchEnabled) }
    }
    @Published var tagsEnabled: Bool {
        didSet { defaults.set(tagsEnabled, forKey: Keys.tagsEnabled) }
    }
    @Published var embedProvider: EmbedProvider {
        didSet {
            defaults.set(embedProvider.rawValue, forKey: Keys.embedProvider)
            let p = embedProvider
            Task { await embedding.setProvider(p) }
            configureEmbedder()
        }
    }
    @Published var llmProvider: LLMProvider {
        didSet { defaults.set(llmProvider.rawValue, forKey: Keys.llmProvider) }
    }

    // GGUF model selection (Phase 2).
    @Published var embedModelId: String {
        didSet { defaults.set(embedModelId, forKey: Keys.embedModelId); configureEmbedder() }
    }
    @Published var genModelId: String? {
        didSet {
            if let genModelId { defaults.set(genModelId, forKey: Keys.genModelId) }
            else { defaults.removeObject(forKey: Keys.genModelId) }
            configureGenerator()
        }
    }
    @Published var unloadMinutes: Int {
        didSet { defaults.set(unloadMinutes, forKey: Keys.unloadMinutes); configureGenerator() }
    }
    @Published var hfToken: String {
        didSet { defaults.set(hfToken, forKey: Keys.hfToken) }
    }
    @Published var customModels: [ModelSpec] {
        didSet { persistCustomModels() }
    }
    @Published var modelBackends: [String: String] {
        didSet { persistModelBackends(); configureEmbedder(); configureGenerator() }
    }

    let embedding = EmbeddingService()
    let tagging = TagSuggestionService()

    // Live engine status for the AI & Models screen (loaded / backend / unload countdown).
    @Published var embedderStatus = EngineStatus()
    @Published var generatorStatus = EngineStatus()

    var appleIntelligenceReady: Bool { SystemLanguageModel.default.isAvailable }

    private let defaults = UserDefaults.standard
    private var cancellables = Set<AnyCancellable>()

    private enum Keys {
        static let searchEnabled = "ai_search_enabled"
        static let tagsEnabled   = "ai_tags_enabled"
        static let embedProvider = "ai_embed_provider"
        static let llmProvider   = "ai_llm_provider"
        static let embedModelId  = "ai_embed_model_id"
        static let genModelId    = "ai_gen_model_id"
        static let unloadMinutes = "ai_unload_minutes"
        static let hfToken       = "hf_token"
        static let customModels  = "ai_custom_models"
        static let modelBackends = "ai_model_backends"
    }

    init() {
        let d = UserDefaults.standard
        searchEnabled = d.object(forKey: Keys.searchEnabled) as? Bool ?? true
        tagsEnabled   = d.object(forKey: Keys.tagsEnabled) as? Bool ?? true
        embedProvider = d.string(forKey: Keys.embedProvider).flatMap(EmbedProvider.init(rawValue:)) ?? .appleLLM
        llmProvider   = d.string(forKey: Keys.llmProvider).flatMap(LLMProvider.init(rawValue:)) ?? .apple
        embedModelId  = d.string(forKey: Keys.embedModelId) ?? "nomic-embed-v1.5"
        genModelId    = d.string(forKey: Keys.genModelId)
        unloadMinutes = d.object(forKey: Keys.unloadMinutes) as? Int ?? 5
        hfToken       = d.string(forKey: Keys.hfToken) ?? ""
        customModels  = Self.loadCustomModels(d)
        modelBackends = Self.loadModelBackends(d)

        // Property observers don't fire during init — push the initial config manually.
        let p = embedProvider
        Task { await embedding.setProvider(p) }
        configureEmbedder()
        configureGenerator()

        // Surface live engine status (loaded / backend / unload countdown) to the UI.
        Task { await embedding.setReporter { [weak self] s in Task { @MainActor in self?.embedderStatus = s } } }
        Task { await GenerationEngine.shared.setReporter { [weak self] s in Task { @MainActor in self?.generatorStatus = s } } }

        // Re-point the engines whenever a download finishes (a model becomes ready).
        ModelDownloadManager.shared.$states
            .debounce(for: .milliseconds(300), scheduler: RunLoop.main)
            .sink { [weak self] _ in self?.configureEmbedder(); self?.configureGenerator() }
            .store(in: &cancellables)
    }

    // MARK: - Semantic search

    /// Find items matching a query when a literal search returned nothing.
    /// `appleLLM` uses Foundation Models' world knowledge and falls back to the
    /// embedding ranker if Apple Intelligence is unavailable or errors.
    func semanticSearch(query: String, items: [HBItem]) async -> [HBItem] {
        switch embedProvider {
        case .gguf:
            // Hybrid: a fast GGUF-embedder shortlist, then an LLM rerank for world knowledge.
            // The embedder does the heavy lifting once (cached); the LLM only sees ~30 items,
            // so this stays fast regardless of inventory size.
            let shortlist = await embedding.rank(query: query, items: items)
            let top = Array(shortlist.prefix(30))
            if top.count <= 1 { return top }
            if let reranked = await llmRerank(query: query, items: top) { return reranked }
            return top
        case .appleLLM:
            if let matched = await llmSearch(query: query, items: items) { return matched }
            return await embedding.rank(query: query, items: items)
        case .appleContextual, .appleNL:
            return await embedding.rank(query: query, items: items)
        }
    }

    private func llmRerank(query: String, items: [HBItem]) async -> [HBItem]? {
        // Prefer Apple Intelligence (fast); fall back to a downloaded GGUF model if it's off.
        if SystemLanguageModel.default.isAvailable, let m = await llmMatchChunk(query: query, items: items) {
            return m
        }
        return await ggufRerank(query: query, items: items)
    }

    private func ggufRerank(query: String, items: [HBItem]) async -> [HBItem]? {
        guard genModelId != nil else { return nil }
        let list = items.enumerated()
            .map { "\($0.offset + 1). \($0.element.name.prefix(60))" }
            .joined(separator: "\n")
        let system = "/no_think\nYou search a home inventory. Reply with ONLY the matching item numbers, comma-separated, most relevant first; or 'none'."
        let user = "Search: \(query)\nItems:\n\(list)"
        guard let raw = await GenerationEngine.shared.generate(
            system: system, user: user, maxTokens: 64, temperature: 0.2, topK: 20) else { return nil }
        return parseMatches(stripThink(raw), items: items)
    }

    private func stripThink(_ s: String) -> String {
        guard let start = s.range(of: "<think>") else { return s }
        if let end = s.range(of: "</think>", range: start.upperBound..<s.endIndex) {
            return String(s[..<start.lowerBound]) + String(s[end.upperBound...])
        }
        return String(s[..<start.lowerBound])
    }

    // MARK: - GGUF configuration + model management

    func allModels(_ purpose: ModelPurpose) -> [ModelSpec] {
        ModelCatalog.builtIn(purpose) + customModels.filter { $0.purpose == purpose }
    }

    func backend(for id: String) -> LlamaBackend {
        LlamaBackend(rawValue: modelBackends[id] ?? "") ?? .auto
    }

    func setBackend(_ backend: LlamaBackend, for id: String) {
        modelBackends[id] = backend.rawValue
    }

    func download(_ spec: ModelSpec) {
        ModelDownloadManager.shared.download(spec, hfToken: hfToken)
    }

    func addCustomModel(_ spec: ModelSpec) {
        if !customModels.contains(where: { $0.id == spec.id }) { customModels.append(spec) }
        download(spec)
    }

    func deleteModel(_ id: String) {
        ModelDownloadManager.shared.delete(id)
        customModels.removeAll { $0.id == id }
        if embedModelId == id { embedModelId = "nomic-embed-v1.5" }
        if genModelId == id { genModelId = nil }
        configureEmbedder()
        configureGenerator()
    }

    func configureEmbedder() {
        let minutes = unloadMinutes
        guard embedProvider == .gguf else {
            Task { await embedding.setUnloadMinutes(minutes); await embedding.setGGUF(modelId: nil, path: nil, backend: .cpu) }
            return
        }
        let id = embedModelId
        let path = ModelDownloadManager.shared.isReady(id) ? ModelDownloadManager.modelPath(id).path : nil
        let b = backend(for: id)
        Task { await embedding.setUnloadMinutes(minutes); await embedding.setGGUF(modelId: id, path: path, backend: b) }
    }

    func configureGenerator() {
        let id = genModelId
        var path: String?
        if let id, ModelDownloadManager.shared.isReady(id) { path = ModelDownloadManager.modelPath(id).path }
        let b = id.map { backend(for: $0) } ?? .gpu
        let minutes = unloadMinutes
        Task { await GenerationEngine.shared.configure(modelId: id, path: path, backend: b, unloadMinutes: minutes) }
    }

    private func persistCustomModels() {
        if let data = try? JSONEncoder().encode(customModels) { defaults.set(data, forKey: Keys.customModels) }
    }

    private func persistModelBackends() {
        if let data = try? JSONEncoder().encode(modelBackends) { defaults.set(data, forKey: Keys.modelBackends) }
    }

    private static func loadCustomModels(_ d: UserDefaults) -> [ModelSpec] {
        guard let data = d.data(forKey: Keys.customModels) else { return [] }
        return (try? JSONDecoder().decode([ModelSpec].self, from: data)) ?? []
    }

    private static func loadModelBackends(_ d: UserDefaults) -> [String: String] {
        guard let data = d.data(forKey: Keys.modelBackends) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }

    /// Ask the on-device LLM which items match the query. Returns nil if the model
    /// is unavailable / errors on the first chunk (so the caller can fall back).
    private func llmSearch(query: String, items: [HBItem]) async -> [HBItem]? {
        guard SystemLanguageModel.default.isAvailable else { return nil }
        guard !items.isEmpty else { return [] }

        let chunkSize = 100
        let maxItems = chunkSize * 5   // bound latency / context on large inventories (~500 items)
        let count = min(items.count, maxItems)
        var results: [HBItem] = []
        var start = 0
        while start < count {
            if Task.isCancelled { break }
            let end = min(start + chunkSize, count)
            if let matched = await llmMatchChunk(query: query, items: Array(items[start..<end])) {
                results.append(contentsOf: matched)
            } else if start == 0 {
                return nil   // model errored on the first chunk → fall back to embeddings
            }
            start = end
        }
        return results
    }

    private func llmMatchChunk(query: String, items: [HBItem]) async -> [HBItem]? {
        let list = items.enumerated()
            .map { "\($0.offset + 1). \($0.element.name.prefix(60))" }
            .joined(separator: "\n")
        let prompt = """
        You help search a home inventory. The user searched: "\(query)".
        Items (numbered):
        \(list)

        List the numbers of the items that match the search intent. Include products whose \
        brand, type, or typical use fits the query — for example a weed killer such as \
        "Round Up" matches "pesticide", and "WD-40" matches "lubricant". Reply with ONLY the \
        matching numbers, comma-separated, most relevant first. If nothing matches, reply: none
        """
        do {
            let session = LanguageModelSession()
            let response = try await session.respond(to: prompt)
            return parseMatches("\(response.content)", items: items)
        } catch {
            return nil
        }
    }

    /// Pull 1-based item numbers out of the model's reply, in order, deduped.
    private func parseMatches(_ text: String, items: [HBItem]) -> [HBItem] {
        var picked: [HBItem] = []
        var seen = Set<Int>()
        var digits = ""
        func flush() {
            if let n = Int(digits), n >= 1, n <= items.count, !seen.contains(n) {
                seen.insert(n)
                picked.append(items[n - 1])
            }
            digits = ""
        }
        for ch in text {
            if ch.isNumber { digits.append(ch) } else { flush() }
        }
        flush()
        return picked
    }
}
