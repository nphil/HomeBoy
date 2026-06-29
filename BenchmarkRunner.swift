import Foundation

/// A single scored candidate (for the embedder ranking output).
struct ScoredText: Sendable {
    let text: String
    let score: Float
}

/// Runs head-to-head model benchmarks. Each model is loaded fresh on a SPECIFIC backend
/// (no silent fallback, so GPU vs CPU are measured honestly), timed, then freed.
actor BenchmarkRunner {

    struct EmbedRow: Identifiable, Sendable {
        let id: String          // "modelId|backend" — unique per model+backend
        let modelName: String
        let backend: String
        let dim: Int
        let loadMs: Double
        let msPerItem: Double
        let embedsPerSec: Double
        let count: Int
        let top: [ScoredText]
        let failed: Bool
        let error: String?
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
        let error: String?
    }

    func benchmarkEmbedder(spec: ModelSpec, path: String, backend: LlamaBackend,
                           query: String, candidates: [String], topN: Int = 12) -> EmbedRow {
        let rowId = "\(spec.id)|\(backend.rawValue)"
        let (handle, loadMs, err) = load(path: path, embeddings: true, backend: backend, probe: true)
        guard let h = handle else {
            return EmbedRow(id: rowId, modelName: spec.displayName, backend: backend.displayName, dim: 0,
                            loadMs: loadMs, msPerItem: 0, embedsPerSec: 0, count: 0, top: [], failed: true, error: err)
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
            id: rowId, modelName: spec.displayName, backend: backend.displayName, dim: dim,
            loadMs: loadMs,
            msPerItem: count > 0 ? elapsed * 1000 / Double(count) : 0,
            embedsPerSec: elapsed > 0 ? Double(count) / elapsed : 0,
            count: count,
            top: Array(scored.prefix(topN)),
            failed: false, error: nil)
    }

    func benchmarkLLM(spec: ModelSpec, path: String, backend: LlamaBackend,
                      system: String, user: String) -> LLMRow {
        let rowId = "\(spec.id)|\(backend.rawValue)"
        let (handle, loadMs, err) = load(path: path, embeddings: false, backend: backend, probe: false)
        guard let h = handle else {
            return LLMRow(id: rowId, modelName: spec.displayName, backend: backend.displayName,
                          loadMs: loadMs, promptTokens: 0, genTokens: 0, tokensPerSec: 0, output: "", failed: true, error: err)
        }
        defer { LlmKit.free(h) }

        let b = LlmKit.chatBenchmark(h, system: system, user: user, maxTokens: 96, temperature: 0.4, topK: 20)
        let failed = b.genTokens == 0
        return LLMRow(id: rowId, modelName: spec.displayName, backend: backend.displayName,
                      loadMs: loadMs, promptTokens: b.promptTokens, genTokens: b.genTokens,
                      tokensPerSec: b.tokensPerSec, output: stripThink(b.text),
                      failed: failed, error: failed ? "Loaded but generated 0 tokens (decode failed)." : nil)
    }

    // MARK: - Helpers

    /// Strict load on one backend (no fallback). Captures llama.cpp's own log on failure.
    private func load(path: String, embeddings: Bool, backend: LlamaBackend, probe: Bool)
        -> (handle: UInt64?, loadMs: Double, error: String?) {
        LlmKit.clearLog()
        let t0 = Date()
        guard let h = LlmKit.loadModel(path: path, embeddings: embeddings, backend: backend) else {
            return (nil, Date().timeIntervalSince(t0) * 1000, loadErrorMessage())
        }
        if probe {
            let p = LlmKit.embed(h, text: "ok")
            if p.isEmpty || p.contains(where: { $0.isNaN }) {
                LlmKit.free(h)
                return (nil, Date().timeIntervalSince(t0) * 1000,
                        "Produced invalid (NaN/empty) embeddings on \(backend.displayName) — try CPU.")
            }
        }
        return (h, Date().timeIntervalSince(t0) * 1000, nil)
    }

    private func loadErrorMessage() -> String {
        let lines = LlmKit.recentLog()
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        // Prefer the SPECIFIC diagnostic over the generic "failed to load model" trailer.
        let specific = ["unknown model architecture", "unsupported", "architecture", "tensor",
                        "error loading model", "wrong number", "not found", "missing", "split",
                        "shard", "n_expert", "vocab", "incompatible", "expected"]
        let hits = lines.filter { l in
            let lo = l.lowercased()
            return specific.contains { lo.contains($0) } && !lo.hasSuffix("failed to load model")
        }
        if let best = hits.last { return best }
        if let any = lines.last(where: { $0.lowercased().contains("error") || $0.lowercased().contains("fail") }) {
            return any
        }
        return "Failed to load — likely an unsupported architecture, the wrong GGUF file (e.g. an mmproj projector or one shard of a split model), or out of memory."
    }

    private func stripThink(_ s: String) -> String {
        var out = s
        while let start = out.range(of: "<think>") {
            if let end = out.range(of: "</think>", range: start.upperBound..<out.endIndex) {
                out.removeSubrange(start.lowerBound..<end.upperBound)
            } else {
                out.removeSubrange(start.lowerBound..<out.endIndex)
                break
            }
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
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
