import Foundation
import Combine

/// Embedding provider for semantic search.
enum EmbedProvider: String, CaseIterable, Identifiable, Sendable {
    case appleContextual   // NLContextualEmbedding (Neural Engine) — default
    case appleNL           // legacy NLEmbedding word+sentence (threshold 1.15)
    case gguf              // llama.cpp GGUF embedder — Phase 2

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .appleContextual: return "On-device (contextual)"
        case .appleNL:         return "On-device (lite)"
        case .gguf:            return "Downloaded model"
        }
    }

    var detail: String {
        switch self {
        case .appleContextual: return "Apple Neural Engine · best quality, no download"
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
        embedProvider = d.string(forKey: Keys.embedProvider).flatMap(EmbedProvider.init(rawValue:)) ?? .appleContextual
        llmProvider   = d.string(forKey: Keys.llmProvider).flatMap(LLMProvider.init(rawValue:)) ?? .apple

        // Property observers don't fire during init — push the initial provider manually.
        let p = embedProvider
        Task { await embedding.setProvider(p) }
    }
}
