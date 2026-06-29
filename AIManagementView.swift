import SwiftUI
import FoundationModels

/// "AI & Models" settings screen. Defaults to Apple's on-device AI; lets the user opt
/// into downloadable GGUF models (llama.cpp / Metal) for hybrid search and tag suggestions.
struct AIManagementView: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var theme: ThemeManager
    @EnvironmentObject var store: HomeboxStore

    @State private var browsePurpose: ModelPurpose?
    @State private var showToken = false

    private var appleLLMAvailable: Bool { SystemLanguageModel.default.isAvailable }
    private let searchChoices: [EmbedProvider] = [.appleLLM, .gguf, .appleContextual, .appleNL]
    private let tagChoices: [LLMProvider] = [.apple, .gguf]

    var body: some View {
        Form {
            searchSection
            if ai.searchEnabled && ai.embedProvider == .gguf { embedderModelsSection }
            tagSection
            if ai.tagsEnabled && ai.llmProvider == .gguf { genModelsSection }
            benchmarkSection
            tokenSection
            noteSection
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background(theme.current.backgroundColor.ignoresSafeArea())
        .navigationTitle("AI & Models")
        .navigationBarTitleDisplayMode(.inline)
        .floatingCardCover(isPresented: Binding(
            get: { browsePurpose != nil },
            set: { if !$0 { browsePurpose = nil } })
        ) {
            if let purpose = browsePurpose {
                HuggingFaceSearchView(purpose: purpose)
                    .environmentObject(ai)
                    .environmentObject(theme)
            }
        }
    }

    // MARK: Sections

    private var searchSection: some View {
        Section {
            Toggle(isOn: $ai.searchEnabled) {
                Label("Semantic search", systemImage: "sparkle.magnifyingglass")
            }
            .tint(theme.current.accentColor)
            Text("Finds related items when the words differ — only kicks in when an exact match isn't found. Runs on-device.")
                .font(.caption).foregroundStyle(.secondary)
            if ai.searchEnabled {
                ForEach(searchChoices) { p in
                    providerRow(title: p.displayName, detail: p.detail, selected: ai.embedProvider == p) {
                        ai.embedProvider = p
                    }
                }
            }
        } header: {
            Text("Search")
        }
    }

    private var embedderModelsSection: some View {
        Section {
            ForEach(ai.allModels(.embedding)) { spec in
                ModelRow(spec: spec, isSelected: ai.embedModelId == spec.id) { ai.embedModelId = spec.id }
            }
            Button { browsePurpose = .embedding } label: {
                Label("Browse Hugging Face", systemImage: "magnifyingglass")
            }
        } header: {
            Text("Embedding model")
        } footer: {
            Text("Pure on-device embedding search (nomic / BGE), same as the Android app. BGE‑large gives the best quality.")
        }
    }

    private var benchmarkSection: some View {
        Section {
            NavigationLink {
                AIBenchmarkView()
                    .environmentObject(ai)
                    .environmentObject(theme)
                    .environmentObject(store)
            } label: {
                Label("Benchmarking", systemImage: "chart.bar.xaxis")
            }
        } footer: {
            Text("Test the embedder and tag generator against your own items or custom text.")
        }
    }

    private var tagSection: some View {
        Section {
            Toggle(isOn: $ai.tagsEnabled) {
                Label("AI tag suggestions", systemImage: "tag")
            }
            .tint(theme.current.accentColor)
            Text("Suggests fitting tags from an item's name and description as you add it.")
                .font(.caption).foregroundStyle(.secondary)
            if ai.llmProvider == .apple && !appleLLMAvailable {
                Label("Apple Intelligence isn’t ready on this device yet (unsupported, disabled, or still downloading).",
                      systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.orange)
            }
            if ai.tagsEnabled {
                ForEach(tagChoices) { p in
                    providerRow(title: p.displayName, detail: p.detail, selected: ai.llmProvider == p) {
                        ai.llmProvider = p
                    }
                }
            }
        } header: {
            Text("Tag suggestions")
        }
    }

    private var genModelsSection: some View {
        Section {
            ForEach(ai.allModels(.generation)) { spec in
                ModelRow(spec: spec, isSelected: ai.genModelId == spec.id) { ai.genModelId = spec.id }
            }
            Button { browsePurpose = .generation } label: {
                Label("Browse Hugging Face", systemImage: "magnifyingglass")
            }
            Picker("Unload when idle", selection: $ai.unloadMinutes) {
                Text("After 2 min").tag(2)
                Text("After 5 min").tag(5)
                Text("Keep loaded").tag(0)
            }
        } header: {
            Text("Language model")
        } footer: {
            Text("Loaded on demand and freed after idle to save memory. A 0.5–2B model is recommended on iPhone.")
        }
    }

    private var tokenSection: some View {
        Section {
            HStack {
                if showToken {
                    TextField("hf_…", text: $ai.hfToken)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                } else {
                    SecureField("hf_…", text: $ai.hfToken)
                }
                Button { showToken.toggle() } label: {
                    Image(systemName: showToken ? "eye.slash" : "eye")
                }
                .buttonStyle(.plain).foregroundStyle(.secondary)
            }
        } header: {
            Text("Hugging Face token (optional)")
        } footer: {
            Text("A free access token raises download limits and reaches gated models.")
        }
    }

    private var noteSection: some View {
        Section {
            HStack {
                Text("Apple Intelligence").font(.caption)
                Spacer()
                Text(appleLLMAvailable ? "Available" : "Unavailable")
                    .font(.caption.weight(.medium))
                    .foregroundStyle(appleLLMAvailable ? .green : .orange)
            }
            Text("On iPhone, downloaded GGUF models run on the GPU (Metal). Apple’s Foundation Models and contextual embeddings use the Neural Engine (ANE).")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func providerRow(title: String, detail: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.callout.weight(.medium)).foregroundStyle(.primary)
                    Text(detail).font(.caption2).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selected ? theme.current.accentColor : Color.secondary.opacity(0.5))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Model row

private struct ModelRow: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var theme: ThemeManager
    @ObservedObject private var downloads = ModelDownloadManager.shared

    let spec: ModelSpec
    let isSelected: Bool
    let onSelect: () -> Void

    var body: some View {
        let state = downloads.state(for: spec.id)
        HStack(spacing: 12) {
            leading(state)
            VStack(alignment: .leading, spacing: 3) {
                Text(spec.displayName).font(.callout.weight(.medium))
                statusLine(state)
                loadedStatus()
                if case .ready = state { BackendChip(modelId: spec.id) }
            }
            Spacer()
            trailing(state)
        }
        .contentShape(Rectangle())
        .onTapGesture { if case .ready = state { onSelect() } }
    }

    @ViewBuilder
    private func leading(_ state: ModelDownloadManager.DownloadState) -> some View {
        if case .ready = state {
            Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary)
        } else {
            Image(systemName: "shippingbox").foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func statusLine(_ state: ModelDownloadManager.DownloadState) -> some View {
        switch state {
        case .notDownloaded:
            Text("\(spec.detail) · \(spec.approxSizeText)").font(.caption2).foregroundStyle(.secondary)
        case .downloading(let p):
            Text(p < 0 ? "Downloading…" : "Downloading \(Int(p * 100))%")
                .font(.caption2).foregroundStyle(.secondary)
        case .ready:
            Text(isSelected ? "Selected" : "Ready")
                .font(.caption2)
                .foregroundStyle(isSelected ? theme.current.accentColor : .secondary)
        case .failed(let msg):
            Text("Failed: \(msg)").font(.caption2).foregroundStyle(.red)
        }
    }

    /// Live "Loaded · GPU/CPU · unloads in m:ss" badge when this model is resident.
    @ViewBuilder
    private func loadedStatus() -> some View {
        let status = spec.purpose == .embedding ? ai.embedderStatus : ai.generatorStatus
        if status.modelId == spec.id {
            if status.loaded {
                HStack(spacing: 4) {
                    Circle().fill(.green).frame(width: 6, height: 6)
                    Text("Loaded" + (status.backend.map { " · \($0.displayName)" } ?? ""))
                    if let unloadAt = status.unloadAt {
                        TimelineView(.periodic(from: .now, by: 1)) { ctx in
                            let remaining = Int(unloadAt.timeIntervalSince(ctx.date))
                            if remaining > 0 {
                                Text("· unloads in \(remaining / 60):\(String(format: "%02d", remaining % 60))")
                            }
                        }
                    }
                }
                .font(.caption2).foregroundStyle(.green)
            } else if let last = status.lastBackend {
                Text("Idle · was on \(last.displayName)").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private func trailing(_ state: ModelDownloadManager.DownloadState) -> some View {
        switch state {
        case .notDownloaded, .failed:
            Button { ai.download(spec) } label: { Image(systemName: "arrow.down.circle") }
                .buttonStyle(.plain).foregroundStyle(theme.current.accentColor)
        case .downloading:
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Button { ModelDownloadManager.shared.cancel(spec.id) } label: {
                    Image(systemName: "xmark.circle.fill")
                }
                .buttonStyle(.plain).foregroundStyle(.secondary)
            }
        case .ready:
            Button(role: .destructive) { ai.deleteModel(spec.id) } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.plain).foregroundStyle(.red)
        }
    }
}

// MARK: - Backend chip (Auto / GPU / CPU)

private struct BackendChip: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var theme: ThemeManager
    let modelId: String

    var body: some View {
        Menu {
            ForEach(LlamaBackend.allCases) { b in
                Button { ai.setBackend(b, for: modelId) } label: {
                    if ai.backend(for: modelId) == b {
                        Label(b.displayName, systemImage: "checkmark")
                    } else {
                        Text(b.displayName)
                    }
                }
            }
        } label: {
            HStack(spacing: 3) {
                Image(systemName: "cpu").font(.caption2)
                Text(ai.backend(for: modelId).displayName).font(.caption2.weight(.medium))
            }
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Capsule().fill(theme.current.accentColor.opacity(0.12)))
            .overlay(Capsule().stroke(theme.current.accentColor.opacity(0.3), lineWidth: 0.5))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Hugging Face browse sheet

private struct HuggingFaceSearchView: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) private var dismiss

    let purpose: ModelPurpose

    @State private var query = ""
    @State private var results: [HFModel] = []
    @State private var searching = false
    @State private var addingId: String?
    @State private var searchTask: Task<Void, Never>?
    @State private var sort: HFSort = .downloads
    @State private var sizes: [String: Int64] = [:]

    var body: some View {
        VStack(spacing: 0) {
            Capsule().fill(Color.secondary.opacity(0.5))
                .frame(width: 36, height: 5).padding(.top, 8)

            HStack {
                Text(purpose == .embedding ? "Embedding models" : "Language models")
                    .font(.headline.weight(.semibold))
                Spacer()
                Button { dismiss() } label: {
                    Image(systemName: "xmark.circle.fill").font(.title3).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal).padding(.vertical, 10)

            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search Hugging Face…", text: $query)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .onSubmit { runSearch() }
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(Capsule().fill(.ultraThinMaterial))
            .padding(.horizontal)

            Picker("Sort", selection: $sort) {
                ForEach(HFSort.allCases) { s in Text(s.label).tag(s) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal).padding(.top, 8)
            .onChange(of: sort) { _, _ in runSearch() }

            if searching {
                ProgressView().padding(.top, 20)
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(results) { resultRow($0) }
                    }
                    .padding()
                }
                .scrollIndicators(.hidden)
            }
        }
        .task { runSearch() }
    }

    @ViewBuilder
    private func resultRow(_ m: HFModel) -> some View {
        let compat = HuggingFaceRepository.classifyRepo(m.id)
        Button { add(m) } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(m.name).font(.callout.weight(.medium)).lineLimit(1)
                    Spacer()
                    if addingId == m.id {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "plus.circle").foregroundStyle(theme.current.accentColor)
                    }
                }
                Text(m.author).font(.caption2).foregroundStyle(.secondary)
                if let w = compat.warning {
                    Label(w, systemImage: "exclamationmark.triangle")
                        .font(.caption2).foregroundStyle(.orange)
                }
                HStack(spacing: 8) {
                    if let size = sizes[m.id] {
                        Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                            .font(.caption2.weight(.semibold)).foregroundStyle(theme.current.accentColor)
                    }
                    if let d = m.downloads {
                        Text("\(d) downloads").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial))
        }
        .buttonStyle(.plain)
        .task(id: m.id) {
            guard sizes[m.id] == nil else { return }
            let token = ai.hfToken
            let repo = HuggingFaceRepository(token: token.isEmpty ? nil : token)
            if let s = await repo.smallestGGUFSize(m.id) { sizes[m.id] = s }
        }
    }

    private func runSearch() {
        searchTask?.cancel()
        let q = query
        let p = purpose
        let token = ai.hfToken
        searching = true
        searchTask = Task {
            let repo = HuggingFaceRepository(token: token.isEmpty ? nil : token)
            let r = await repo.search(q, purpose: p, sort: sort)
            if !Task.isCancelled {
                results = r
                searching = false
            }
        }
    }

    private func add(_ m: HFModel) {
        guard addingId == nil else { return }
        addingId = m.id
        let p = purpose
        let token = ai.hfToken
        Task {
            let repo = HuggingFaceRepository(token: token.isEmpty ? nil : token)
            let files = await repo.files(m.id)
            guard let file = files.first else { addingId = nil; return }
            let spec = HuggingFaceRepository.customSpec(repoId: m.id, file: file, purpose: p)
            ai.addCustomModel(spec)
            addingId = nil
            dismiss()
        }
    }
}
