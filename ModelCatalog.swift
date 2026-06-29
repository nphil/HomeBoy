import Foundation

/// What an on-device GGUF model is used for.
enum ModelPurpose: String, Codable, Sendable {
    case embedding   // semantic search (nomic / BGE)
    case generation  // tag suggestions (Qwen3 / Llama)
}

/// A downloadable GGUF model. Built-ins live in `ModelCatalog.builtIn`; user-added
/// ones (via the Hugging Face browser) are persisted as custom specs.
struct ModelSpec: Identifiable, Codable, Sendable, Hashable {
    let id: String
    let displayName: String
    let detail: String
    let purpose: ModelPurpose
    let approxBytes: Int64
    let repo: String        // Hugging Face repo, e.g. "nomic-ai/nomic-embed-text-v1.5-GGUF"
    let fileName: String    // GGUF file within the repo
    var isCustom: Bool = false

    /// Hugging Face direct-download URL (same scheme as the Android client).
    var downloadURL: URL? {
        URL(string: "https://huggingface.co/\(repo)/resolve/main/\(fileName)")
    }

    var approxSizeText: String {
        ByteCountFormatter.string(fromByteCount: approxBytes, countStyle: .file)
    }
}

enum ModelCatalog {
    /// Curated, llama.cpp-compatible GGUF models (same set verified on Android).
    /// Embedders are small; the generation models are optional — Apple Foundation
    /// Models remains the default tag/rerank engine and needs no download.
    static let builtIn: [ModelSpec] = [
        // --- Embedders (semantic search) ---
        ModelSpec(
            id: "nomic-embed-v1.5",
            displayName: "Nomic Embed v1.5",
            detail: "Best general-purpose embedder",
            purpose: .embedding,
            approxBytes: 145_000_000,
            repo: "nomic-ai/nomic-embed-text-v1.5-GGUF",
            fileName: "nomic-embed-text-v1.5.Q8_0.gguf"
        ),
        ModelSpec(
            id: "bge-small-en-v1.5",
            displayName: "BGE Small EN v1.5",
            detail: "Smallest · fast",
            purpose: .embedding,
            approxBytes: 35_000_000,
            repo: "CompendiumLabs/bge-small-en-v1.5-gguf",
            fileName: "bge-small-en-v1.5-q8_0.gguf"
        ),
        ModelSpec(
            id: "bge-large-en-v1.5",
            displayName: "BGE Large EN v1.5",
            detail: "Highest quality embedder",
            purpose: .embedding,
            approxBytes: 358_000_000,
            repo: "CompendiumLabs/bge-large-en-v1.5-gguf",
            fileName: "bge-large-en-v1.5-q8_0.gguf"
        ),
        // --- Generation (tag suggestions / hybrid rerank) ---
        ModelSpec(
            id: "qwen3-1.7b",
            displayName: "Qwen3 1.7B",
            detail: "Capable · ~1.3 GB",
            purpose: .generation,
            approxBytes: 1_280_000_000,
            repo: "bartowski/Qwen_Qwen3-1.7B-GGUF",
            fileName: "Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        ),
        ModelSpec(
            id: "llama-3.2-1b",
            displayName: "Llama 3.2 1B Instruct",
            detail: "Lightweight · ~0.8 GB",
            purpose: .generation,
            approxBytes: 808_000_000,
            repo: "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName: "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
    ]

    static func builtIn(_ purpose: ModelPurpose) -> [ModelSpec] {
        builtIn.filter { $0.purpose == purpose }
    }

    static func spec(id: String, customs: [ModelSpec]) -> ModelSpec? {
        builtIn.first { $0.id == id } ?? customs.first { $0.id == id }
    }
}
