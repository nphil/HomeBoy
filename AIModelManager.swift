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
        case .gguf:            return "Downloaded model"
        }
    }

    var detail: String {
        switch self {
        case .appleLLM:        return "Understands what products are — finds “Round Up” for “pesticide”. Slower; needs Apple Intelligence."
        case .appleContextual: return "Neural-Engine embeddings · fast, but limited world knowledge"
        case .appleNL:         return "Lightweight word match · always available"
        case .gguf:            return "nomic / BGE GGUF via llama.cpp (Metal)"
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
        }
    }
    @Published var llmProvider: LLMProvider {
        didSet { defaults.set(llmProvider.rawValue, forKey: Keys.llmProvider) }
    }

    let embedding = EmbeddingService()
    let tagging = TagSuggestionService()

    private let defaults = UserDefaults.standard

    private enum Keys {
        static let searchEnabled = "ai_search_enabled"
        static let tagsEnabled   = "ai_tags_enabled"
        static let embedProvider = "ai_embed_provider"
        static let llmProvider   = "ai_llm_provider"
    }

    init() {
        let d = UserDefaults.standard
        searchEnabled = d.object(forKey: Keys.searchEnabled) as? Bool ?? true
        tagsEnabled   = d.object(forKey: Keys.tagsEnabled) as? Bool ?? true
        embedProvider = d.string(forKey: Keys.embedProvider).flatMap(EmbedProvider.init(rawValue:)) ?? .appleLLM
        llmProvider   = d.string(forKey: Keys.llmProvider).flatMap(LLMProvider.init(rawValue:)) ?? .apple

        // Property observers don't fire during init — push the initial provider manually.
        let p = embedProvider
        Task { await embedding.setProvider(p) }
    }

    // MARK: - Semantic search

    /// Find items matching a query when a literal search returned nothing.
    /// `appleLLM` uses Foundation Models' world knowledge and falls back to the
    /// embedding ranker if Apple Intelligence is unavailable or errors.
    func semanticSearch(query: String, items: [HBItem]) async -> [HBItem] {
        switch embedProvider {
        case .appleLLM:
            if let matched = await llmSearch(query: query, items: items) { return matched }
            return await embedding.rank(query: query, items: items)
        case .appleContextual, .appleNL, .gguf:
            return await embedding.rank(query: query, items: items)
        }
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
