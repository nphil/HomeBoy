import SwiftUI
import FoundationModels

/// "AI & Models" settings screen. Phase 1 surfaces the on-device Apple providers
/// (Foundation Models for tags, contextual embeddings for search). Phase 2 adds
/// downloadable GGUF models (llama.cpp / Metal) and a Hugging Face browser here.
struct AIManagementView: View {
    @EnvironmentObject var ai: AIModelManager
    @EnvironmentObject var theme: ThemeManager

    private var appleLLMAvailable: Bool { SystemLanguageModel.default.isAvailable }

    // Apple-only providers in Phase 1; GGUF appears once downloads land.
    private let embedChoices: [EmbedProvider] = [.appleContextual, .appleNL]

    var body: some View {
        Form {
            Section {
                Toggle(isOn: $ai.searchEnabled) {
                    Label("Semantic search", systemImage: "sparkle.magnifyingglass")
                }
                .tint(theme.current.accentColor)
                Text("Finds related items even when the words differ — searching “poison” can surface “rat bait”. Runs entirely on-device.")
                    .font(.caption).foregroundStyle(.secondary)

                if ai.searchEnabled {
                    ForEach(embedChoices) { p in
                        providerRow(title: p.displayName,
                                    detail: p.detail,
                                    selected: ai.embedProvider == p) {
                            ai.embedProvider = p
                        }
                    }
                }
            } header: {
                Text("Search")
            }

            Section {
                Toggle(isOn: $ai.tagsEnabled) {
                    Label("AI tag suggestions", systemImage: "tag")
                }
                .tint(theme.current.accentColor)
                Text("Suggests fitting tags from an item's name and description while you add it.")
                    .font(.caption).foregroundStyle(.secondary)

                if !appleLLMAvailable {
                    Label("Apple Intelligence isn’t ready on this device yet (unsupported, disabled, or still downloading).",
                          systemImage: "exclamationmark.triangle")
                        .font(.caption).foregroundStyle(.orange)
                }

                if ai.tagsEnabled {
                    providerRow(title: LLMProvider.apple.displayName,
                                detail: LLMProvider.apple.detail,
                                selected: ai.llmProvider == .apple) {
                        ai.llmProvider = .apple
                    }
                }
            } header: {
                Text("Tag suggestions")
            }

            Section {
                HStack {
                    Label("Download custom models", systemImage: "arrow.down.circle")
                    Spacer()
                    Text("Coming soon").font(.caption)
                }
                .foregroundStyle(.secondary)
                Text("Downloadable GGUF models (nomic / BGE embedders, Qwen3 / Llama) running via llama.cpp with Metal, plus Hugging Face downloads, arrive in a future update.")
                    .font(.caption).foregroundStyle(.secondary)
            } header: {
                Text("On-device models")
            } footer: {
                Text("On iPhone, llama.cpp runs on the GPU (Metal). Apple’s Foundation Models and contextual embeddings use the Neural Engine (ANE).")
            }
        }
        .scrollContentBackground(.hidden)
        .scrollIndicators(.hidden)
        .background(theme.current.backgroundColor.ignoresSafeArea())
        .navigationTitle("AI & Models")
        .navigationBarTitleDisplayMode(.inline)
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
