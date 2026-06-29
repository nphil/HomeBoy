import SwiftUI

/// Head-to-head benchmarking: pick the downloaded models to compare, run them on your real
/// items (embedder) or a sample item (tag generator), and see quality + performance side by side.
struct AIBenchmarkView: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    enum Mode: String, CaseIterable, Identifiable {
        case embedder = "Embedder"
        case tags = "Tag generator"
        var id: String { rawValue }
    }
    @State private var mode: Mode = .embedder
    @State private var selected: Set<String> = []

    enum BenchBackend: String, CaseIterable, Identifiable {
        case gpu = "GPU", cpu = "CPU", both = "Both"
        var id: String { rawValue }
    }
    @State private var benchBackend: BenchBackend = .gpu

    // Embedder input
    @State private var query = ""
    @State private var useMyItems = true
    @State private var customText = ""

    // Tag input
    @State private var tagName = ""
    @State private var tagDesc = ""

    @State private var running = false
    @State private var embedRows: [BenchmarkRunner.EmbedRow] = []
    @State private var llmRows: [BenchmarkRunner.LLMRow] = []
    @State private var note: String?

    private let runner = BenchmarkRunner()

    private var purpose: ModelPurpose { mode == .embedder ? .embedding : .generation }
    private var availableModels: [ModelSpec] {
        ai.allModels(purpose).filter { ModelDownloadManager.shared.isReady($0.id) }
    }
    private var inputValid: Bool {
        mode == .embedder
            ? !query.trimmingCharacters(in: .whitespaces).isEmpty
            : tagName.trimmingCharacters(in: .whitespaces).count >= 3
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Picker("Mode", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .onChange(of: mode) { _, _ in resetSelection() }

                modelChips
                backendPicker
                inputCard
                runButton

                if let note {
                    Text(note).font(.caption).foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                resultsView
                if hasResults { saveRunButton }
                savedRunsSection
            }
            .padding(16)
        }
        .scrollIndicators(.hidden)
        .background(theme.current.backgroundColor.ignoresSafeArea())
        .navigationTitle("Benchmarking")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { if selected.isEmpty { resetSelection() } }
    }

    private func resetSelection() {
        selected = Set(availableModels.map { $0.id })
        embedRows = []; llmRows = []; note = nil
    }

    // MARK: - Model selection

    private var modelChips: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("MODELS TO COMPARE")
                .font(.caption2.weight(.semibold)).tracking(0.5)
                .foregroundStyle(theme.current.accentColor.opacity(0.8))
            if availableModels.isEmpty {
                Text("No downloaded \(mode == .embedder ? "embedders" : "language models"). Add one in AI & Models.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(availableModels) { chip($0) }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func chip(_ spec: ModelSpec) -> some View {
        let on = selected.contains(spec.id)
        return Button {
            if on { selected.remove(spec.id) } else { selected.insert(spec.id) }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: on ? "checkmark.circle.fill" : "circle").font(.caption)
                Text(spec.displayName).font(.caption.weight(.medium))
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(Capsule().fill(on ? theme.current.accentColor.opacity(0.18) : Color.secondary.opacity(0.12)))
            .overlay(Capsule().stroke(theme.current.accentColor.opacity(on ? 0.5 : 0.15), lineWidth: 1))
            .foregroundStyle(on ? theme.current.accentColor : .secondary)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Input

    @ViewBuilder
    private var inputCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            if mode == .embedder {
                TextField("Query (e.g. lubricant)", text: $query)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                Toggle("Use my items (\(store.localDB.items.count))", isOn: $useMyItems)
                    .font(.callout).tint(theme.current.accentColor)
                if !useMyItems {
                    TextEditor(text: $customText)
                        .frame(minHeight: 90).font(.callout)
                        .overlay(alignment: .topLeading) {
                            if customText.isEmpty {
                                Text("One candidate per line…")
                                    .foregroundStyle(.secondary).padding(6).allowsHitTesting(false)
                            }
                        }
                }
            } else {
                TextField("Item name (e.g. cordless drill)", text: $tagName)
                TextField("Description (optional)", text: $tagDesc, axis: .vertical).lineLimit(1...3)
            }
        }
        .padding(14)
        .background(cardBG)
    }

    private var runButton: some View {
        Button { run() } label: {
            HStack(spacing: 8) {
                if running { ProgressView().controlSize(.small) }
                Text(running ? "Running…" : "Run benchmark").fontWeight(.semibold)
            }
            .frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.glassProminent)
        .tint(theme.current.accentColor)
        .disabled(running || selected.isEmpty || !inputValid)
    }

    private var backendPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("BACKEND")
                .font(.caption2.weight(.semibold)).tracking(0.5)
                .foregroundStyle(theme.current.accentColor.opacity(0.8))
            Picker("Backend", selection: $benchBackend) {
                ForEach(BenchBackend.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var hasResults: Bool {
        mode == .embedder ? !embedRows.isEmpty : !llmRows.isEmpty
    }

    private var saveRunButton: some View {
        Button { saveCurrentRun() } label: {
            Label("Save this run", systemImage: "square.and.arrow.down")
                .frame(maxWidth: .infinity).padding(.vertical, 4)
        }
        .buttonStyle(.bordered)
        .tint(theme.current.accentColor)
    }

    @ViewBuilder
    private var savedRunsSection: some View {
        if !ai.savedRuns.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("SAVED RUNS")
                    .font(.caption2.weight(.semibold)).tracking(0.5)
                    .foregroundStyle(theme.current.accentColor.opacity(0.8))
                ForEach(ai.savedRuns) { run in
                    DisclosureGroup {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Source: \(run.source)").font(.caption2).foregroundStyle(.secondary)
                            ForEach(run.lines.indices, id: \.self) { i in
                                Text(run.lines[i]).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            }
                            Button(role: .destructive) { ai.deleteRun(run.id) } label: {
                                Label("Delete", systemImage: "trash").font(.caption)
                            }
                            .padding(.top, 2)
                        }
                        .padding(.top, 4)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(run.mode) · \(run.backendMode)").font(.caption.weight(.medium))
                            Text("\(run.date) · \(run.input)").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                        }
                    }
                    .padding(12).background(cardBG)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func saveCurrentRun() {
        let df = DateFormatter(); df.dateStyle = .short; df.timeStyle = .short
        let dateStr = df.string(from: Date())
        var lines: [String] = []
        let input: String
        let source: String
        if mode == .embedder {
            input = "Query: \(query)"
            source = useMyItems ? "My items (\(min(store.localDB.items.count, 200)))" : "Custom text"
            for r in embedRows {
                if r.failed {
                    lines.append("\(r.modelName) [\(r.backend)] — \(r.error ?? "failed")")
                } else {
                    let top = r.top.prefix(3).map { "\($0.text.prefix(18)) \(String(format: "%.2f", $0.score))" }.joined(separator: ", ")
                    lines.append("\(r.modelName) [\(r.backend)] — \(String(format: "%.0f", r.embedsPerSec)) emb/s, load \(Int(r.loadMs))ms · \(top)")
                }
            }
        } else {
            input = "Item: \(tagName)" + (tagDesc.isEmpty ? "" : " / \(tagDesc)")
            source = "—"
            for r in llmRows {
                if r.failed {
                    lines.append("\(r.modelName) [\(r.backend)] — \(r.error ?? "failed")")
                } else {
                    lines.append("\(r.modelName) [\(r.backend)] — \(String(format: "%.1f", r.tokensPerSec)) tok/s, load \(Int(r.loadMs))ms · \(r.output.prefix(40))")
                }
            }
        }
        ai.saveRun(SavedRun(id: UUID().uuidString, date: dateStr, mode: mode.rawValue,
                            input: input, source: source, backendMode: benchBackend.rawValue, lines: lines))
    }

    // MARK: - Results

    @ViewBuilder
    private var resultsView: some View {
        if mode == .embedder, !embedRows.isEmpty {
            embedTable
            ForEach(embedRows) { embedOutput($0) }
        } else if mode == .tags, !llmRows.isEmpty {
            llmTable
            ForEach(llmRows) { llmOutput($0) }
        }
    }

    private var embedTable: some View {
        VStack(alignment: .leading, spacing: 6) {
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                GridRow {
                    Text("Model"); Text("On"); Text("Load"); Text("Emb/s")
                }
                .font(.caption2.weight(.bold)).foregroundStyle(theme.current.accentColor)
                Divider()
                ForEach(embedRows) { r in
                    GridRow {
                        Text(r.modelName).font(.caption.weight(.medium)).lineLimit(1)
                        Text(r.failed ? "—" : r.backend).font(.caption2)
                        Text(r.failed ? "—" : "\(Int(r.loadMs))").font(.caption2.monospacedDigit())
                        Text(r.failed ? "fail" : String(format: "%.0f", r.embedsPerSec))
                            .font(.caption2.monospacedDigit().weight(.semibold))
                    }
                }
            }
            Text("Load = ms to load · Emb/s = embeddings/second")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .padding(14).background(cardBG)
    }

    private var llmTable: some View {
        VStack(alignment: .leading, spacing: 6) {
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 8) {
                GridRow {
                    Text("Model"); Text("On"); Text("Load"); Text("Tok/s")
                }
                .font(.caption2.weight(.bold)).foregroundStyle(theme.current.accentColor)
                Divider()
                ForEach(llmRows) { r in
                    GridRow {
                        Text(r.modelName).font(.caption.weight(.medium)).lineLimit(1)
                        Text(r.failed ? "—" : r.backend).font(.caption2)
                        Text(r.failed ? "—" : "\(Int(r.loadMs))").font(.caption2.monospacedDigit())
                        Text(r.failed ? "fail" : String(format: "%.1f", r.tokensPerSec))
                            .font(.caption2.monospacedDigit().weight(.semibold))
                    }
                }
            }
            Text("Load = ms to load · Tok/s = generated tokens/second")
                .font(.caption2).foregroundStyle(.secondary)
        }
        .padding(14).background(cardBG)
    }

    private func embedOutput(_ r: BenchmarkRunner.EmbedRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("\(r.modelName) · \(r.backend)").font(.subheadline.weight(.semibold))
                Spacer()
                if !r.failed { Text("dim \(r.dim)").font(.caption2).foregroundStyle(.secondary) }
            }
            if r.failed {
                Text(r.error ?? "Failed to load").font(.caption).foregroundStyle(.orange)
            } else {
                ForEach(r.top.indices, id: \.self) { i in
                    let s = r.top[i]
                    HStack {
                        Text(s.text).font(.caption).lineLimit(1)
                        Spacer()
                        Text(String(format: "%.3f", s.score))
                            .font(.caption2.monospacedDigit().weight(.semibold))
                            .foregroundStyle(scoreColor(s.score, best: r.top.first?.score ?? 0))
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12).background(cardBG)
    }

    private func llmOutput(_ r: BenchmarkRunner.LLMRow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("\(r.modelName) · \(r.backend)").font(.subheadline.weight(.semibold))
                Spacer()
            }
            if r.failed {
                Text(r.error ?? "Failed to load or generate").font(.caption).foregroundStyle(.orange)
            } else {
                Text(r.output.isEmpty ? "(empty)" : r.output).font(.caption)
                Text(String(format: "%.1f tok/s · %d tokens · load %dms", r.tokensPerSec, r.genTokens, Int(r.loadMs)))
                    .font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12).background(cardBG)
    }

    private var cardBG: some View {
        RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.15), lineWidth: 1))
    }

    private func scoreColor(_ s: Float, best: Float) -> Color {
        if s >= best - 0.03 { return .green }
        if s >= best - 0.08 { return theme.current.accentColor }
        return .secondary
    }

    // MARK: - Run

    private func run() {
        let specs = availableModels.filter { selected.contains($0.id) }
        guard !specs.isEmpty, inputValid else { return }
        running = true; note = nil; embedRows = []; llmRows = []

        let paths = Dictionary(uniqueKeysWithValues: specs.map { ($0.id, ModelDownloadManager.modelPath($0.id).path) })
        let backends: [LlamaBackend] = benchBackend == .both ? [.gpu, .cpu] : (benchBackend == .cpu ? [.cpu] : [.gpu])

        if mode == .embedder {
            let q = query.trimmingCharacters(in: .whitespaces)
            let candidates: [String] = useMyItems
                ? Array(store.localDB.items.prefix(200)).map(candidateText)
                : customText.split(whereSeparator: \.isNewline).map(String.init)
            Task { @MainActor in
                for spec in specs {
                    for b in backends {
                        let row = await runner.benchmarkEmbedder(
                            spec: spec, path: paths[spec.id] ?? "", backend: b,
                            query: q, candidates: candidates)
                        embedRows.append(row)
                    }
                }
                if candidates.isEmpty { note = "No candidates to test." }
                running = false
            }
        } else {
            let name = tagName.trimmingCharacters(in: .whitespaces)
            let desc = tagDesc
            Task { @MainActor in
                for spec in specs {
                    for b in backends {
                        let row = await runner.benchmarkLLM(
                            spec: spec, path: paths[spec.id] ?? "", backend: b,
                            system: tagSystem, user: tagUserPrompt(name, desc))
                        llmRows.append(row)
                    }
                }
                running = false
            }
        }
    }

    private func candidateText(_ item: HBItem) -> String {
        if let d = item.description, !d.isEmpty { return item.name + ". " + d }
        return item.name
    }

    private let tagSystem = "/no_think\nYou label home-inventory items with short tags. Reply with ONLY a comma-separated list of 3 to 6 tags, each 1 or 2 words, and nothing else."

    private func tagUserPrompt(_ name: String, _ desc: String) -> String {
        var u = "Item: \(name)"
        let d = desc.trimmingCharacters(in: .whitespaces)
        if !d.isEmpty { u += "\nDescription: \(d)" }
        u += "\nTags:"
        return u
    }
}
