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

    // GGUF embedder (lazy; llama.cpp / Metal or CPU). Configured by AIModelManager.
    private var ggufModelId: String?
    private var ggufPath: String?
    private var ggufBackend: LlamaBackend = .cpu
    private var ggufHandle: UInt64?
    private var ggufTriedLoad = false
    private var ggufEngaged: LlamaBackend?
    private var ggufLastBackend: LlamaBackend?
    private var ggufUnloadAt: Date?
    private var ggufUnloadMinutes = 0
    private var ggufUseToken = 0
    private var reporter: (@Sendable (EngineStatus) -> Void)?

    // Per-item vector cache for the contextual/gguf path: id -> (contentHash, normalized vector).
    // In-memory only (String.hashValue is per-process); rebuilt as needed.
    private var vectorCache: [String: (hash: Int, vec: [Float])] = [:]

    // Tuning. The 1.15 NLEmbedding distance is locked by CLAUDE.md; the contextual
    // cosine floor / cap below are independent and tunable on-device.
    private let contextualFloor: Float = 0.25
    private let maxResults = 20

    func setProvider(_ p: EmbedProvider) {
        guard p != provider else { return }
        provider = p
        vectorCache.removeAll()
    }

    /// Point the GGUF path at a downloaded embedder (path nil = none). Embedders default
    /// to CPU — small + fast on A19, and avoids the Metal BERT NaN issue seen on some GPUs.
    func setReporter(_ r: @escaping @Sendable (EngineStatus) -> Void) {
        reporter = r
        reportStatus()
    }

    func setUnloadMinutes(_ m: Int) { ggufUnloadMinutes = m }

    func setGGUF(modelId: String?, path: String?, backend: LlamaBackend) {
        if modelId != ggufModelId || path != ggufPath || backend != ggufBackend {
            if let h = ggufHandle {
                LlmKit.free(h); ggufHandle = nil
                if let e = ggufEngaged { ggufLastBackend = e }
                ggufEngaged = nil; ggufUnloadAt = nil
            }
            ggufModelId = modelId
            ggufPath = path
            ggufBackend = backend
            ggufTriedLoad = false
            vectorCache.removeAll()
            reportStatus()
        }
    }

    func unloadGGUF() {
        if let h = ggufHandle { LlmKit.free(h); ggufHandle = nil }
        if let e = ggufEngaged { ggufLastBackend = e }
        ggufEngaged = nil
        ggufUnloadAt = nil
        ggufTriedLoad = false
        reportStatus()
    }

    var ggufReady: Bool { ggufHandle != nil }

    private func reportStatus() {
        reporter?(EngineStatus(modelId: ggufModelId,
                               loaded: ggufHandle != nil,
                               backend: ggufHandle != nil ? ggufEngaged : nil,
                               lastBackend: ggufEngaged ?? ggufLastBackend,
                               unloadAt: ggufHandle != nil ? ggufUnloadAt : nil))
    }

    /// Reset the idle-unload countdown after the embedder is used.
    private func touchGGUF() {
        guard ggufUnloadMinutes > 0 else { ggufUnloadAt = nil; return }
        ggufUseToken += 1
        ggufUnloadAt = Date().addingTimeInterval(Double(ggufUnloadMinutes) * 60)
        scheduleGGUFUnload(token: ggufUseToken)
        reportStatus()
    }

    private func scheduleGGUFUnload(token: Int) {
        let minutes = ggufUnloadMinutes
        Task {
            try? await Task.sleep(nanoseconds: UInt64(minutes) * 60 * 1_000_000_000)
            await self.unloadGGUFIfIdle(token)
        }
    }

    private func unloadGGUFIfIdle(_ token: Int) {
        if token == ggufUseToken { unloadGGUF() }
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
        case .gguf:
            if let ranked = rankGGUF(query: trimmed, items: items) { return ranked }
            // Embedder not ready → fall back so search still works.
            if let ranked = rankVector(query: trimmed, items: items) { return ranked }
            return rankNL(query: trimmed.lowercased(), items: items)
        case .appleContextual, .appleLLM:
            // Embedding path (also the fallback when the LLM provider can't run).
            if let ranked = rankVector(query: trimmed, items: items) { return ranked }
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
        var parts = [item.name]
        if let d = item.description, !d.isEmpty { parts.append(d) }
        if let labels = item.effectiveLabels, !labels.isEmpty {
            parts.append(labels.map { $0.name }.joined(separator: " "))
        }
        return parts.joined(separator: ". ")
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

    // MARK: - GGUF embedder path (llama.cpp / Metal or CPU)

    private func rankGGUF(query: String, items: [HBItem]) -> [HBItem]? {
        guard let h = ensureGGUF() else { return nil }
        touchGGUF()
        guard let qVec = ggufVector(handle: h, text: query, isQuery: true) else { return nil }
        var scored: [(HBItem, Float)] = []
        scored.reserveCapacity(items.count)
        for item in items {
            if Task.isCancelled { return [] }
            let text = itemText(item)
            let key = ("gguf:" + text).hashValue   // namespace cache so it doesn't collide with contextual
            let vec: [Float]
            if let cached = vectorCache[item.id], cached.hash == key {
                vec = cached.vec
            } else if let v = ggufVector(handle: h, text: text, isQuery: false) {
                vectorCache[item.id] = (key, v)
                vec = v
            } else {
                continue
            }
            scored.append((item, dot(qVec, vec)))
        }
        scored.sort { $0.1 > $1.1 }
        guard !scored.isEmpty else { return [] }
        // nomic/BGE have a high, query-dependent similarity baseline, so thresholds relative
        // to the single best match are unstable (one strong hit excludes other good ones; a
        // flat query keeps everything). Instead keep items clearly above the AVERAGE for this
        // query (mean + 0.75·σ), with a small floor so a few results always show.
        let sims = scored.map { $0.1 }
        let mean = sims.reduce(0, +) / Float(sims.count)
        let variance = sims.reduce(0) { $0 + ($1 - mean) * ($1 - mean) } / Float(sims.count)
        let cutoff = mean + 0.75 * variance.squareRoot()
        var kept = scored.filter { $0.1 >= cutoff }
        if kept.count < 3 { kept = Array(scored.prefix(min(5, scored.count))) }
        return Array(kept.prefix(maxResults).map { $0.0 })
    }

    /// On-device self-test: embed known pairs and report cosine similarities so we can see
    /// whether the embedder is actually discriminating on this device.
    func runDiagnostics() -> String {
        guard ggufPath != nil else { return "No downloaded embedder selected." }
        guard let h = ensureGGUF() else { return "Embedder failed to load (model not ready?)." }
        func cos(_ q: String, _ d: String) -> Float {
            guard let a = ggufVector(handle: h, text: q, isQuery: true),
                  let b = ggufVector(handle: h, text: d, isQuery: false) else { return .nan }
            return dot(a, b)
        }
        func fmt(_ f: Float) -> String { f.isNaN ? "NaN" : String(format: "%.3f", f) }
        let dim = (ggufVector(handle: h, text: "lubricant", isQuery: true) ?? []).count
        return """
        model: \(ggufModelId ?? "?") · \(ggufEngaged?.displayName ?? "?") · dim \(dim)
        lubricant ↔ engine oil:    \(fmt(cos("lubricant", "car engine oil")))  (should be highest)
        lubricant ↔ grease:        \(fmt(cos("lubricant", "grease")))  (should be high)
        lubricant ↔ aquarium sand: \(fmt(cos("lubricant", "aquarium sand")))
        lubricant ↔ interior paint:\(fmt(cos("lubricant", "interior paint")))
        """
    }

    private func ggufVector(handle: UInt64, text: String, isQuery: Bool) -> [Float]? {
        let v = LlmKit.embed(handle, text: ggufPrefix(isQuery: isQuery) + text)
        return v.isEmpty ? nil : v
    }

    /// Asymmetric retrieval prefixes (degrade quality badly if omitted for nomic/BGE).
    private func ggufPrefix(isQuery: Bool) -> String {
        let id = (ggufModelId ?? "").lowercased()
        if id.contains("nomic") { return isQuery ? "search_query: " : "search_document: " }
        if id.contains("bge") { return isQuery ? "Represent this sentence for searching relevant passages: " : "" }
        return ""
    }

    private func ensureGGUF() -> UInt64? {
        if let h = ggufHandle { return h }
        if ggufTriedLoad { return nil }
        ggufTriedLoad = true
        guard let path = ggufPath, FileManager.default.fileExists(atPath: path) else { return nil }
        // Metal embedding graphs CAN misbehave on some GPUs (garbage / non-deterministic
        // vectors that slip past a NaN check), so every GPU load must pass a strict probe
        // (finite + deterministic) or fall back to CPU. On healthy GPUs (e.g. A19) Metal is
        // correct AND much faster — especially for larger embedders — so .auto prefers it;
        // the probe is the safety net.
        let order: [LlamaBackend]
        switch ggufBackend {
        case .cpu:  order = [.cpu]
        case .gpu:  order = [.gpu, .cpu]
        case .auto: order = [.gpu, .cpu]
        }
        for backend in order {
            guard let h = LlmKit.loadModel(path: path, embeddings: true, backend: backend) else { continue }
            if probeIsHealthy(h) {
                ggufHandle = h
                ggufEngaged = backend
                reportStatus()
                return h
            }
            LlmKit.free(h)
        }
        return nil
    }

    /// A backend is healthy only if it returns finite, non-degenerate, and *deterministic*
    /// embeddings — embedding the same text twice must match. Catches Metal BERT garbage.
    private func probeIsHealthy(_ handle: UInt64) -> Bool {
        let a = LlmKit.embed(handle, text: "lubricant for the door hinge")
        guard a.count > 1, !a.contains(where: { $0.isNaN || $0.isInfinite }) else { return false }
        if a.allSatisfy({ $0 == 0 }) { return false }
        let b = LlmKit.embed(handle, text: "lubricant for the door hinge")
        guard b.count == a.count else { return false }
        var diff: Float = 0
        for i in 0..<a.count { diff += abs(a[i] - b[i]) }
        return diff < 1e-3
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
