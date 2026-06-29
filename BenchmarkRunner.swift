import Foundation

/// A single scored candidate (for the embedder ranking output).
struct ScoredText: Sendable {
    let text: String
    let score: Float
}

/// Runs head-to-head model benchmarks. Each model is loaded fresh, measured, then freed —
/// independent of the app's live embedding/generation engines, and bounding peak memory.
actor BenchmarkRunner {

    struct EmbedRow: Identifiable, Sendable {
        let id: String          // modelId
        let modelName: String
        let backend: String
        let dim: Int
        let loadMs: Double
        let msPerItem: Double
        let embedsPerSec: Double
        let count: Int
        let top: [ScoredText]
        let failed: Bool
    }

    struct LLMRow: Identifiable, Sendable {
        let id: String
        let modelName: String
        let backend: String
        let loadMs: Double
        let promptTokens: Int
        let genTokens: Int
        let tokensPerSec: Double
        let output: String
        let failed: Bool
    }

    func benchmarkEmbedder(spec: ModelSpec, path: String, backend: LlamaBackend,
                           query: String, candidates: [String], topN: Int = 12) -> EmbedRow {
        let (handle, engaged, loadMs) = load(path: path, embeddings: true, backend: backend, probe: true)
        guard let h = handle else {
            return EmbedRow(id: spec.id, modelName: spec.displayName, backend: "—", dim: 0,
                            loadMs: loadMs, msPerItem: 0, embedsPerSec: 0, count: 0, top: [], failed: true)
        }
        defer { LlmKit.free(h) }

        let pq = prefix(spec.id, isQuery: true)
        let pd = prefix(spec.id, isQuery: false)
        let qv = LlmKit.embed(h, text: pq + query)
        let dim = qv.count

        let t0 = Date()
        var scored: [ScoredText] = []
        for c in candidates where !c.trimmingCharacters(in: .whitespaces).isEmpty {
            let v = LlmKit.embed(h, text: pd + c)
            scored.append(ScoredText(text: c, score: dot(qv, v)))
        }
        let elapsed = Date().timeIntervalSince(t0)
        let count = scored.count
        scored.sort { $0.score > $1.score }

        return EmbedRow(
            id: spec.id, modelName: spec.displayName, backend: engaged.displayName, dim: dim,
            loadMs: loadMs,
            msPerItem: count > 0 ? elapsed * 1000 / Double(count) : 0,
            embedsPerSec: elapsed > 0 ? Double(count) / elapsed : 0,
            count: count,
            top: Array(scored.prefix(topN)),
            failed: false)
    }

    func benchmarkLLM(spec: ModelSpec, path: String, backend: LlamaBackend,
                      system: String, user: String) -> LLMRow {
        let (handle, engaged, loadMs) = load(path: path, embeddings: false, backend: backend, probe: false)
        guard let h = handle else {
            return LLMRow(id: spec.id, modelName: spec.displayName, backend: "—",
                          loadMs: loadMs, promptTokens: 0, genTokens: 0, tokensPerSec: 0, output: "", failed: true)
        }
        defer { LlmKit.free(h) }

        let b = LlmKit.chatBenchmark(h, system: system, user: user, maxTokens: 96, temperature: 0.4, topK: 20)
        return LLMRow(id: spec.id, modelName: spec.displayName, backend: engaged.displayName,
                      loadMs: loadMs, promptTokens: b.promptTokens, genTokens: b.genTokens,
                      tokensPerSec: b.tokensPerSec, output: b.text, failed: b.genTokens == 0)
    }

    // MARK: - Helpers

    private func load(path: String, embeddings: Bool, backend: LlamaBackend, probe: Bool)
        -> (handle: UInt64?, engaged: LlamaBackend, loadMs: Double) {
        let order: [LlamaBackend] = (backend == .cpu) ? [.cpu] : [backend == .auto ? .gpu : backend, .cpu]
        let t0 = Date()
        for b in order {
            guard let h = LlmKit.loadModel(path: path, embeddings: embeddings, backend: b) else { continue }
            if probe {
                let p = LlmKit.embed(h, text: "ok")
                if p.isEmpty || p.contains(where: { $0.isNaN }) { LlmKit.free(h); continue }
            }
            return (h, b, Date().timeIntervalSince(t0) * 1000)
        }
        return (nil, .cpu, Date().timeIntervalSince(t0) * 1000)
    }

    private func prefix(_ id: String, isQuery: Bool) -> String {
        let l = id.lowercased()
        if l.contains("nomic") { return isQuery ? "search_query: " : "search_document: " }
        if l.contains("bge") { return isQuery ? "Represent this sentence for searching relevant passages: " : "" }
        return ""
    }

    private func dot(_ a: [Float], _ b: [Float]) -> Float {
        guard a.count == b.count else { return 0 }
        var s: Float = 0
        for i in 0..<a.count { s += a[i] * b[i] }
        return s
    }
}
