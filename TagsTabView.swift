import SwiftUI
import UIKit

struct TagDetailRoute: Hashable { let id: String }

// Standard Homebox tag palette — matches the web app's color picker.
let HomeboxTagPalette: [String] = [
    "#ef4444", "#f97316", "#f59e0b", "#eab308",
    "#84cc16", "#10b981", "#06b6d4", "#3b82f6",
    "#6366f1", "#8b5cf6", "#ec4899", "#71717a"
]

struct TagsTabView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var tags: [HBTag] = []
    @State private var isLoading = false
    @State private var query = ""
    @State private var showCreate = false

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                VStack(spacing: 0) {
                    // Custom header
                    HStack(spacing: 12) {
                        BrandMark()
                        Spacer()
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 4)


                    content
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if store.isAuthenticated {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            Button {
                                showCreate = true
                            } label: {
                                Image(systemName: "plus")
                                    .font(.title2.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .frame(width: 56, height: 56)
                                    .background(theme.current.accentColor)
                                    .clipShape(Circle())
                                    .shadow(color: theme.current.accentColor.opacity(0.4), radius: 6, x: 0, y: 4)
                            }
                            .padding()
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search tags")
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackgroundVisibility(.hidden, for: .navigationBar)
            .task { await load() }
            .sheet(isPresented: $showCreate) {
                TagEditSheet(mode: .create) { await load() }
                    .environmentObject(store).environmentObject(theme)
            }
            .navigationDestination(for: TagDetailRoute.self) { route in
                TagDetailView(tagId: route.id, onChange: { Task { await load() } })
                    .environmentObject(store).environmentObject(theme)
            }
            .navigationDestination(for: ItemDetailRoute.self) { route in
                ItemDetailView(itemId: route.id)
                    .environmentObject(store).environmentObject(theme)
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !store.isAuthenticated {
            VStack(spacing: 12) {
                Image(systemName: "link.circle").font(.system(size: 48)).foregroundStyle(.secondary)
                Text("Not connected").font(.title3.weight(.semibold))
                Text("Open Settings to sign in.")
                    .font(.callout).foregroundStyle(.secondary)
            }
        } else if tags.isEmpty && isLoading {
            ProgressView("Loading tags…")
        } else if tags.isEmpty {
            VStack(spacing: 12) {
                Image(systemName: "tag").font(.system(size: 48)).foregroundStyle(.secondary)
                Text("No tags yet").font(.title3.weight(.semibold))
                Text("Tap + to create your first tag.").font(.callout).foregroundStyle(.secondary)
            }
        } else {
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(filteredTags) { tag in
                        NavigationLink(value: TagDetailRoute(id: tag.id)) {
                            TagRow(tag: tag)
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 8).padding(.bottom, 60)
            }
            .scrollIndicators(.hidden)
            .refreshable { await load() }
        }
    }

    private var filteredTags: [HBTag] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let base = q.isEmpty ? tags : tags.filter { $0.name.lowercased().contains(q) }
        return base.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    private func load() async {
        guard let client = store.client else { return }
        isLoading = true
        if let fetched = try? await client.listTags() { tags = fetched }
        isLoading = false
    }
}

// MARK: - Row

private struct TagRow: View {
    @EnvironmentObject var theme: ThemeManager
    let tag: HBTag

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color(hex: tag.color ?? ""))
                .frame(width: 18, height: 18)
                .overlay(Circle().stroke(Color.primary.opacity(0.15), lineWidth: 0.5))

            VStack(alignment: .leading, spacing: 2) {
                Text(tag.name).font(.body.weight(.medium)).foregroundStyle(.primary).lineLimit(1)
                if let d = tag.description, !d.isEmpty {
                    Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right").foregroundStyle(.tertiary).font(.caption)
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.06))
        }
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
    }
}

// MARK: - Detail

struct TagDetailView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let tagId: String
    var onChange: () -> Void = {}

    @State private var tag: HBTag? = nil
    @State private var items: [HBItem] = []
    @State private var isLoading = false
    @State private var showEdit = false
    @State private var showDelete = false
    @State private var thumbStore = ThumbnailStore()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let tag { headerCard(tag) }

                HStack {
                    Text("ITEMS").font(.caption.weight(.semibold)).tracking(0.6)
                        .foregroundStyle(theme.current.accentColor.opacity(0.75))
                    Spacer()
                    Text("\(items.count)").font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 8).padding(.horizontal, 4)

                if isLoading && items.isEmpty {
                    ProgressView().frame(maxWidth: .infinity).padding()
                } else if items.isEmpty {
                    Text("No items with this tag")
                        .font(.callout).foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity).padding(.vertical, 24)
                } else {
                    ForEach(items) { item in
                        NavigationLink(value: ItemDetailRoute(id: item.id)) {
                            ItemListRowContent(item: item, thumbStore: thumbStore)
                                .background {
                                    RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
                                    RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(0.05))
                                }
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.current.accentColor.opacity(0.15), lineWidth: 1))
                        }.buttonStyle(.plain)
                    }
                }
            }
            .padding(16)
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(theme.current.backgroundColor.ignoresSafeArea())
        .navigationTitle(tag?.name ?? "Tag")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { showEdit = true } label: { Label("Edit", systemImage: "pencil") }
                    Button(role: .destructive) { showDelete = true } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .task { await load() }
        .sheet(isPresented: $showEdit) {
            if let tag {
                TagEditSheet(mode: .edit(tag)) {
                    onChange()
                    await load()
                }
                .environmentObject(store).environmentObject(theme)
            }
        }
        .alert("Delete tag?", isPresented: $showDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await deleteTag() } }
        } message: {
            Text("This removes the tag from all items. The items themselves are kept.")
        }
    }

    private func headerCard(_ tag: HBTag) -> some View {
        HStack(spacing: 14) {
            Circle()
                .fill(Color(hex: tag.color ?? ""))
                .frame(width: 44, height: 44)
                .overlay(Circle().stroke(Color.primary.opacity(0.15), lineWidth: 0.5))
            VStack(alignment: .leading, spacing: 2) {
                Text(tag.name).font(.title3.weight(.semibold))
                if let d = tag.description, !d.isEmpty {
                    Text(d).font(.callout).foregroundStyle(.secondary)
                }
            }
            Spacer()
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.07))
        }
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.2), lineWidth: 1))
    }



    private func load() async {
        guard let client = store.client else { return }
        isLoading = true
        async let tagsTask = client.listTags()
        async let itemsTask = client.listItems(labelIds: [tagId], pageSize: 1000)
        if let allTags = try? await tagsTask { tag = allTags.first { $0.id == tagId } }
        if let resp = try? await itemsTask {
            items = resp.items.sorted { $0.name.lowercased() < $1.name.lowercased() }
        }
        isLoading = false
    }

    private func deleteTag() async {
        guard let client = store.client else { return }
        do {
            try await client.deleteTag(id: tagId)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onChange()
            dismiss()
        } catch {
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }
}

// MARK: - Create / edit sheet

struct TagEditSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    enum Mode { case create, edit(HBTag) }
    let mode: Mode
    var onSave: () async -> Void = {}

    @State private var name = ""
    @State private var description = ""
    @State private var colorHex = "#3b82f6"
    @State private var isSaving = false
    @State private var errorMsg: String?
    @FocusState private var nameFocused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. fragile", text: $name)
                        .focused($nameFocused)
                        .textInputAutocapitalization(.never)
                }

                Section("Color") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 6), spacing: 10) {
                        ForEach(HomeboxTagPalette, id: \.self) { hex in
                            Button { colorHex = hex } label: {
                                Circle()
                                    .fill(Color(hex: hex))
                                    .frame(height: 36)
                                    .overlay(
                                        Circle().stroke(colorHex == hex ? Color.primary : Color.primary.opacity(0.1),
                                                        lineWidth: colorHex == hex ? 3 : 1)
                                    )
                                    .overlay(
                                        Image(systemName: "checkmark")
                                            .font(.caption.weight(.bold))
                                            .foregroundStyle(.white)
                                            .opacity(colorHex == hex ? 1 : 0)
                                    )
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Description (optional)") {
                    TextField("Notes about this tag", text: $description, axis: .vertical)
                        .lineLimit(1...3)
                }

                if let errorMsg {
                    Section {
                        Label(errorMsg, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red).font(.callout)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle(isEditing ? "Edit tag" : "New tag")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await save() }
                    } label: {
                        if isSaving { ProgressView().controlSize(.small) }
                        else { Text("Save").bold() }
                    }
                    .disabled(isSaving || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear {
                if case .edit(let tag) = mode {
                    name = tag.name
                    description = tag.description ?? ""
                    colorHex = tag.color ?? "#3b82f6"
                } else {
                    nameFocused = true
                }
            }
        }
    }

    private var isEditing: Bool { if case .edit = mode { return true } else { return false } }

    private func save() async {
        guard let client = store.client else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isSaving = true; errorMsg = nil
        do {
            switch mode {
            case .create:
                _ = try await client.createTag(name: trimmed, description: description, color: colorHex)
            case .edit(let tag):
                try await client.updateTag(id: tag.id, name: trimmed, description: description, color: colorHex)
            }
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            await onSave()
            dismiss()
        } catch {
            errorMsg = error.localizedDescription
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
        isSaving = false
    }
}
