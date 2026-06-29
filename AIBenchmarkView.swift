import SwiftUI

/// Hands-on benchmarking: test the embedder and the tag generator against your real items
/// or custom text, so you can judge quality directly (scores shown, no hidden cutoff).
struct AIBenchmarkView: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    enum Mode { case embedder, tags }
    @State private var mode: Mode = .embedder

    // Embedder test
    @State private var query = ""
    @State private var useMyItems = true
    @State private var customText = ""
    @State private var embedResults: [(text: String, score: Float)] = []
    @State private var embedError: String?
    @State private var runningEmbed = false

    // Tag generator test
    @State private var tagName = ""
    @State private var tagDesc = ""
    @State private var tagResults: [String] = []
    @State private var tagError: String?
    @State private var runningTags = false

    var body: some View {
        Form {
            Section {
                Picker("Test", selection: $mode) {
                    Text("Embedder").tag(Mode.embedder)
                    Text("Tag generator").tag(Mode.tags)
                }
                .pickerStyle(.segmented)
            }
            if mode == .embedder { embedderTest } else { tagTest }
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background(theme.current.backgroundColor.ignoresSafeArea())
        .navigationTitle("Benchmarking")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Embedder

    @ViewBuilder
    private var embedderTest: some View {
        Section {
            TextField("Search query (e.g. lubricant)", text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Toggle("Use my items (\(store.localDB.items.count))", isOn: $useMyItems)
                .tint(theme.current.accentColor)
            if !useMyItems {
                TextEditor(text: $customText)
                    .frame(minHeight: 110)
                    .font(.callout)
                    .overlay(alignment: .topLeading) {
                        if customText.isEmpty {
                            Text("One candidate per line…")
                                .foregroundStyle(.secondary)
                                .padding(.top, 8).padding(.leading, 5)
                                .allowsHitTesting(false)
                        }
                    }
            }
            Button {
                runEmbed()
            } label: {
                HStack {
                    if runningEmbed { ProgressView().controlSize(.small) }
                    Text(runningEmbed ? "Running…" : "Run embedder test")
                }
            }
            .disabled(runningEmbed || query.trimmingCharacters(in: .whitespaces).isEmpty)
        } header: {
            Text("Embedder · \(ai.embedModelId)")
        } footer: {
            Text("Embeds your query against each candidate and shows the cosine score (higher = more related). You see the full ranking, so you can judge quality and where the cutoff should fall.")
        }

        if let embedError {
            Section { Text(embedError).font(.caption).foregroundStyle(.orange) }
        }
        if !embedResults.isEmpty {
            Section("Results (\(embedResults.count))") {
                ForEach(embedResults.indices, id: \.self) { i in
                    let r = embedResults[i]
                    HStack(spacing: 10) {
                        Text(r.text).font(.callout).lineLimit(2)
                        Spacer()
                        Text(String(format: "%.3f", r.score))
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(scoreColor(r.score))
                    }
                }
            }
        }
    }

    private func runEmbed() {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return }
        runningEmbed = true; embedError = nil; embedResults = []
        let useItems = useMyItems
        let items = store.localDB.items
        let custom = customText.split(whereSeparator: \.isNewline).map(String.init)
        Task {
            await ai.prepareEmbedder()
            let results: [(text: String, score: Float)]?
            if useItems {
                results = await ai.embedding.benchmarkItems(query: q, items: items)?
                    .map { (text: $0.item.name, score: $0.score) }
            } else {
                results = await ai.embedding.benchmarkTexts(query: q, candidates: custom)
            }
            if let results {
                embedResults = results
                if results.isEmpty { embedError = "No candidates to test." }
            } else {
                embedError = "Embedder not ready — pick a downloaded embedding model in AI & Models."
            }
            runningEmbed = false
        }
    }

    private func scoreColor(_ s: Float) -> Color {
        guard let best = embedResults.first?.score else { return .secondary }
        if s >= best - 0.03 { return .green }
        if s >= best - 0.08 { return theme.current.accentColor }
        return .secondary
    }

    // MARK: - Tag generator

    @ViewBuilder
    private var tagTest: some View {
        Section {
            TextField("Item name (e.g. cordless drill)", text: $tagName)
            TextField("Description (optional)", text: $tagDesc, axis: .vertical)
                .lineLimit(1...4)
            Button {
                runTags()
            } label: {
                HStack {
                    if runningTags { ProgressView().controlSize(.small) }
                    Text(runningTags ? "Generating…" : "Generate tags")
                }
            }
            .disabled(runningTags || tagName.trimmingCharacters(in: .whitespaces).count < 3)
        } header: {
            Text("Tag generator · \(ai.llmProvider.displayName)")
        } footer: {
            Text("Runs the tag generator on the name + description and shows the tags it proposes.")
        }

        if let tagError {
            Section { Text(tagError).font(.caption).foregroundStyle(.orange) }
        }
        if !tagResults.isEmpty {
            Section("Suggested tags (\(tagResults.count))") {
                ForEach(tagResults, id: \.self) { t in
                    Label(t, systemImage: "tag")
                }
            }
        }
    }

    private func runTags() {
        let name = tagName.trimmingCharacters(in: .whitespaces)
        guard name.count >= 3 else { return }
        runningTags = true; tagError = nil; tagResults = []
        let desc = tagDesc
        let provider = ai.llmProvider
        Task {
            ai.configureGenerator()
            let existing = store.localDB.tags
            let result = await ai.tagging.suggest(name: name, description: desc, existing: existing, provider: provider)
            if let result {
                let matched = result.matchedIds.compactMap { id in existing.first { $0.id == id }?.name }
                tagResults = matched + result.novel
                if tagResults.isEmpty { tagError = "No tags suggested." }
            } else {
                tagError = provider == .apple
                    ? "Apple Intelligence isn’t available on this device."
                    : "Generation model not ready — download and select one in AI & Models."
            }
            runningTags = false
        }
    }
}
