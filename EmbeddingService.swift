import Foundation
import NaturalLanguage

/// Provider-aware semantic embedding + ranking used by item search.
///
/// Providers (see `EmbedProvider` in AIModelManager.swift):
/// - `appleContextual` — `NLContextualEmbedding` (BERT, Apple Neural Engine). Default.
/// - `appleNL` — legacy `NLEmbedding` word+sentence cosine distance, threshold **1.15**
///   (preserved verbatim per CLAUDE.md — do not change).
/// - `gguf` — llama.cpp GGUF embedders (nomic / BGE). Wired in Phase 2; until then the
///   contextual vector path is used and falls back to `appleNL` if assets aren't ready.
///
/// An `actor` so the (CPU-heavy) NL distance loops and contextual inference run off the
/// main thread, and the per-item vector cache is mutated safely.
actor EmbeddingService {
    private var provider: EmbedProvider = .appleContextual

    // Contextual model (lazy; Apple Neural Engine).
    private var contextual: NLContextualEmbedding?
    private var contextualLoaded = false
    private var assetRequestStarted = false

    // Per-item vector cache for the contextual/gguf path: id -> (contentHash, normalized vector).
    // In-memory only (String.hashValue is per-process); rebuilt as needed.
    private var vectorCache: [String: (hash: Int, vec: [Float])] = [:]

    // Tuning. The 1.15 NLEmbedding distance is locked by CLAUDE.md; the contextual
    // cosine floor / cap below are independent and tunable on-device.
    private let contextualFloor: Float = 0.25
    private let maxResults = 25

    func setProvider(_ p: EmbedProvider) {
        guard p != provider else { return }
        provider = p
        vectorCache.removeAll()
    }

    func clearCache() { vectorCache.removeAll() }

    /// Rank `items` by semantic similarity to `query`, most-relevant first.
    /// Returns an empty array when nothing is similar (callers treat that as "no hits").
    func rank(query: String, items: [HBItem]) -> [HBItem] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !items.isEmpty else { return [] }

        switch provider {
        case .appleNL:
            return rankNL(query: trimmed.lowercased(), items: items)
        case .appleContextual, .gguf:
            // GGUF lands in Phase 2; until then the on-device vector path is contextual.
            if let ranked = rankVector(query: trimmed, items: items) { return ranked }
            // Assets not ready yet → fall back so search still works this session.
            return rankNL(query: trimmed.lowercased(), items: items)
        }
    }

    // MARK: - Contextual vector path

    private func rankVector(query: String, items: [HBItem]) -> [HBItem]? {
        guard let qVec = contextualVector(for: query) else { return nil }
        var scored: [(HBItem, Float)] = []
        scored.reserveCapacity(items.count)
        for item in items {
            if Task.isCancelled { return [] }
            let text = itemText(item)
            let hash = text.hashValue
            let vec: [Float]
            if let cached = vectorCache[item.id], cached.hash == hash {
                vec = cached.vec
            } else if let v = contextualVector(for: text) {
                vectorCache[item.id] = (hash, v)
                vec = v
            } else {
                continue
            }
            let sim = dot(qVec, vec)
            if sim >= contextualFloor { scored.append((item, sim)) }
        }
        scored.sort { $0.1 > $1.1 }
        return Array(scored.prefix(maxResults).map { $0.0 })
    }

    private func itemText(_ item: HBItem) -> String {
        if let d = item.description, !d.isEmpty { return item.name + ". " + d }
        return item.name
    }

    /// Mean-pool the contextual token vectors into one L2-normalized sentence vector.
    private func contextualVector(for text: String) -> [Float]? {
        guard let emb = ensureContextual() else { return nil }
        guard let result = try? emb.embeddingResult(for: text, language: .english) else { return nil }

        var sum: [Double] = []
        var count = 0
        result.enumerateTokenVectors(in: text.startIndex..<text.endIndex) { tokenVector, _ in
            if sum.isEmpty { sum = [Double](repeating: 0, count: tokenVector.count) }
            if sum.count == tokenVector.count {
                for i in 0..<tokenVector.count { sum[i] += Double(tokenVector[i]) }
                count += 1
            }
            return true
        }
        guard count > 0, !sum.isEmpty else { return nil }

        var norm = 0.0
        for i in 0..<sum.count { sum[i] /= Double(count); norm += sum[i] * sum[i] }
        norm = norm.squareRoot()
        guard norm > 0 else { return nil }
        return sum.map { Float($0 / norm) }
    }

    private func ensureContextual() -> NLContextualEmbedding? {
        if let emb = contextual, contextualLoaded { return emb }
        guard let emb = contextual ?? NLContextualEmbedding(language: .english) else { return nil }
        contextual = emb
        if emb.hasAvailableAssets {
            if (try? emb.load()) != nil {
                contextualLoaded = true
                return emb
            }
            return nil
        }
        // Assets missing — request a one-time background download so it's ready next launch.
        if !assetRequestStarted {
            assetRequestStarted = true
            emb.requestAssets { _, _ in }
        }
        return nil
    }

    private func dot(_ a: [Float], _ b: [Float]) -> Float {
        guard a.count == b.count else { return 0 }
        var s: Float = 0
        for i in 0..<a.count { s += a[i] * b[i] }
        return s
    }

    // MARK: - Legacy NLEmbedding path (threshold 1.15 — locked by CLAUDE.md)

    private func rankNL(query q: String, items: [HBItem]) -> [HBItem] {
        guard let sentEmbedding = NLEmbedding.sentenceEmbedding(for: .english),
              let wordEmbedding = NLEmbedding.wordEmbedding(for: .english) else { return [] }

        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = q
        let queryWords = tokenizer.tokens(for: q.startIndex..<q.endIndex).map { String(q[$0]) }

        let results = items.compactMap { item -> (HBItem, Double)? in
            if Task.isCancelled { return nil }
            let name = item.name.lowercased()
            let d1 = sentEmbedding.distance(between: q, and: name, distanceType: .cosine)

            tokenizer.string = name
            let targetWords = tokenizer.tokens(for: name.startIndex..<name.endIndex).map { String(name[$0]) }

            var minWordDist = 2.0
            for qw in queryWords {
                for tw in targetWords {
                    let wd = wordEmbedding.distance(between: qw, and: tw, distanceType: .cosine)
                    if wd < minWordDist { minWordDist = wd }
                }
            }

            let dist = min(d1, minWordDist)
            return dist < 1.15 ? (item, dist) : nil
        }
        .sorted { $0.1 < $1.1 }
        .map { $0.0 }

        return results
    }
}
