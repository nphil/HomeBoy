import SwiftUI

/// Multi-select tag picker. Caller passes a binding to a Set of selected ids;
/// the sheet fetches the full list lazily and lets the user create new tags inline.
struct TagPickerSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    @Binding var selectedIds: Set<String>

    @State private var tags: [HBTag] = []
    @State private var query: String = ""
    @State private var isLoading = false
    @State private var loadError: String?

    @State private var newTagName: String = ""
    @State private var isCreating = false

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                content
            }
            .navigationTitle("Tags")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }.bold()
                }
            }
            .task { await load() }
        }
    }

    @ViewBuilder
    private var content: some View {
        VStack(spacing: 0) {
            // Create-tag bar
            HStack(spacing: 8) {
                Image(systemName: "tag")
                    .foregroundStyle(theme.current.accentColor)
                TextField("New tag…", text: $newTagName)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.done)
                    .onSubmit { Task { await create() } }
                if isCreating { ProgressView().controlSize(.small) }
                Button {
                    Task { await create() }
                } label: {
                    Image(systemName: "plus.circle.fill").font(.title3)
                }
                .disabled(newTagName.trimmingCharacters(in: .whitespaces).isEmpty || isCreating)
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial))
            .padding(.horizontal, 16)
            .padding(.top, 12)

            if let loadError {
                Label(loadError, systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.red)
                    .padding(.horizontal, 16).padding(.top, 8)
            }

            if isLoading && tags.isEmpty {
                Spacer()
                ProgressView("Loading tags…")
                Spacer()
            } else {
                List {
                    ForEach(filteredTags) { tag in
                        Button { toggle(tag) } label: { row(tag) }
                            .listRowBackground(Color.clear)
                            .listRowSeparator(.hidden)
                            .buttonStyle(.plain)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .searchable(text: $query, prompt: "Search tags")
                .refreshable { await load() }
            }
        }
    }

    private func row(_ tag: HBTag) -> some View {
        let selected = selectedIds.contains(tag.id)
        return HStack(spacing: 10) {
            Circle()
                .fill(tagColor(tag))
                .frame(width: 14, height: 14)
                .overlay(Circle().stroke(Color.white.opacity(0.2), lineWidth: 1))
            VStack(alignment: .leading, spacing: 2) {
                Text(tag.name).font(.body.weight(.medium))
                if let d = tag.description, !d.isEmpty {
                    Text(d).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(selected ? .green : .secondary)
        }
        .padding(.vertical, 10).padding(.horizontal, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
            if selected {
                RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(0.10))
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 12).stroke(selected ? theme.current.accentColor.opacity(0.40) : Color.clear, lineWidth: 1)
        )
    }

    private var filteredTags: [HBTag] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let all = tags.sorted { $0.name.lowercased() < $1.name.lowercased() }
        guard !q.isEmpty else { return all }
        return all.filter { $0.name.lowercased().contains(q) }
    }

    private func tagColor(_ tag: HBTag) -> Color {
        if let hex = tag.color, !hex.isEmpty { return Color(hex: hex) }
        return theme.current.accentColor
    }

    private func toggle(_ tag: HBTag) {
        if selectedIds.contains(tag.id) { selectedIds.remove(tag.id) }
        else { selectedIds.insert(tag.id) }
    }

    private func load() async {
        guard let client = store.client else { return }
        isLoading = true; loadError = nil
        do { tags = try await client.listTags() }
        catch { loadError = error.localizedDescription }
        isLoading = false
    }

    private func create() async {
        let name = newTagName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty, let client = store.client else { return }
        isCreating = true
        do {
            let new = try await client.createTag(name: name)
            tags.append(new)
            selectedIds.insert(new.id)
            newTagName = ""
        } catch {
            loadError = error.localizedDescription
        }
        isCreating = false
    }
}

/// Inline chips strip for the currently-picked tags. Tap the row in a form
/// to open the picker.
struct TagChipsRow: View {
    @EnvironmentObject var theme: ThemeManager
    let tags: [HBTag]

    var body: some View {
        if tags.isEmpty {
            Text("No tags").font(.callout).foregroundStyle(.secondary)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(tags) { tag in
                        HStack(spacing: 4) {
                            Circle().fill(tagColor(tag)).frame(width: 8, height: 8)
                            Text(tag.name).font(.caption.weight(.medium))
                        }
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Capsule().fill(.ultraThinMaterial))
                        .overlay(Capsule().stroke(tagColor(tag).opacity(0.6), lineWidth: 1))
                    }
                }
            }
        }
    }

    private func tagColor(_ tag: HBTag) -> Color {
        if let hex = tag.color, !hex.isEmpty { return Color(hex: hex) }
        return theme.current.accentColor
    }
}
