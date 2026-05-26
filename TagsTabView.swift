import SwiftUI
import UIKit

struct TagDetailRoute: Hashable { let id: String; let name: String; let color: String? }

// Standard Homebox tag palette — matches the web app's color picker.
let HomeboxTagPalette: [String] = [
    "#ef4444", "#f97316", "#f59e0b", "#eab308",
    "#84cc16", "#10b981", "#06b6d4", "#3b82f6",
    "#6366f1", "#8b5cf6", "#ec4899", "#71717a"
]

struct TagsTabView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.showSiteMenu) var showSiteMenu

    @Binding var globalSearchQuery: String

    @State private var tags: [HBTag] = []
    @State private var isLoading = false
    @State private var showCreate = false
    @State private var isSearchActive = false

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                content
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                if store.isAuthenticated {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            Button {
                                withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
                                    showCreate = true
                                }
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
            .toolbar {
                ToolbarItemGroup(placement: .topBarLeading) {
                    Button {
                        withAnimation(.spring(duration: 0.25, bounce: 0.22)) {
                            showSiteMenu.wrappedValue.toggle()
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "shippingbox.fill")
                                .foregroundStyle(theme.current.accentColor)
                            Text("HomeBoy")
                                .font(.headline)
                                .foregroundStyle(.primary)
                            Image(systemName: "chevron.down")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        isSearchActive = true
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                }
            }
            .modifier(ConditionalSearchable(text: $globalSearchQuery, isPresented: $isSearchActive, prompt: "Search tags…"))
            .task { await load() }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                Task { await load() }
            }
            .onChange(of: store.activeGroupId) { _, _ in
                tags = []
                Task { await load() }
            }

            .navigationDestination(for: TagDetailRoute.self) { route in
                TagDetailView(tagId: route.id, initialName: route.name, initialColor: route.color, onChange: { Task { await load() } })
                    .environmentObject(store).environmentObject(theme)
            }
            .navigationDestination(for: ItemDetailRoute.self) { route in
                ItemDetailView(itemId: route.id)
                    .environmentObject(store).environmentObject(theme)
            }
            .toolbar(showCreate ? .hidden : .visible, for: .tabBar)
        }
        .floatingCardCover(isPresented: $showCreate) {
            TagEditSheet(mode: .create, onSave: {
                await load()
            }, onDismiss: {
                showCreate = false
            })
            .environmentObject(store)
            .environmentObject(theme)
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
                        NavigationLink(value: TagDetailRoute(id: tag.id, name: tag.name, color: tag.color)) {
                            TagRow(tag: tag)
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 8)
                .padding(.bottom, 80)
            }
            .coordinateSpace(name: "pullToSearch")
            .scrollDismissesKeyboard(.interactively)
            .scrollIndicators(.hidden)
            .refreshable { await load() }
        }
    }

    private var filteredTags: [HBTag] {
        let q = globalSearchQuery.trimmingCharacters(in: .whitespaces).lowercased()
        let base = q.isEmpty ? tags : tags.filter { $0.name.lowercased().contains(q) }
        return base.sorted { $0.name.lowercased() < $1.name.lowercased() }
    }

    private func load() async {
        guard let client = store.client else { return }
        isLoading = true
        do {
            async let tagsTask = client.listTags()
            async let itemsTask = client.listItems(pageSize: 1000)
            let (fetchedTags, fetchedItems) = try await (tagsTask, itemsTask)
            
            var counts: [String: Int] = [:]
            for item in fetchedItems.items {
                if let labels = item.effectiveLabels {
                    for label in labels {
                        counts[label.id, default: 0] += 1
                    }
                }
            }
            
            self.tags = fetchedTags.map { tag in
                var t = tag
                t.itemCount = Double(counts[tag.id] ?? 0)
                return t
            }
        } catch {
            if let fetched = try? await client.listTags() { tags = fetched }
        }
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
            Spacer(minLength: 8)
            if let count = tag.itemCount {
                Text("\(Int(count))")
                    .font(.caption.weight(.medium).monospacedDigit())
                    .foregroundStyle(.secondary)
                    .padding(.trailing, 4)
                    .layoutPriority(1)
            }
            Image(systemName: "chevron.right")
                .foregroundStyle(.tertiary)
                .font(.caption)
                .layoutPriority(1)
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
    var initialName: String = "Tag"
    var initialColor: String? = nil
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
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: 7) {
                    Circle()
                        .fill(Color(hex: tag?.color ?? initialColor ?? ""))
                        .frame(width: 14, height: 14)
                        .overlay(Circle().stroke(Color.primary.opacity(0.15), lineWidth: 0.5))
                    Text(tag?.name ?? initialName)
                        .font(.headline)
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
                            showEdit = true
                        }
                    } label: { Label("Edit", systemImage: "pencil") }
                    Button(role: .destructive) { showDelete = true } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .task { await load() }
        .floatingCardCover(isPresented: $showEdit) {
            if let tag = tag {
                TagEditSheet(mode: .edit(tag), onSave: {
                    onChange()
                    await load()
                }, onDismiss: {
                    showEdit = false
                })
                .environmentObject(store)
                .environmentObject(theme)
            }
        }
        .alert("Delete tag?", isPresented: $showDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await deleteTag() } }
        } message: {
            Text("This removes the tag from all items. The items themselves are kept.")
        }
        .toolbar(showEdit ? .hidden : .visible, for: .tabBar)
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

    enum Mode { case create, edit(HBTag) }
    let mode: Mode
    var onSave: () async -> Void = {}
    var onDismiss: () -> Void = {}

    @State private var name = ""
    @State private var description = ""
    @State private var colorHex = "#3b82f6"
    @State private var isSaving = false
    @State private var errorMsg: String?
    @FocusState private var nameFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            // Grabber indicator
            Capsule()
                .fill(Color.secondary.opacity(0.5))
                .frame(width: 36, height: 5)
                .padding(.top, 8)
                .padding(.bottom, 4)

            VStack(spacing: 0) {
                // Header
                HStack {
                    HStack(spacing: 8) {
                        Image(systemName: isEditing ? "tag.fill" : "tag.circle.fill")
                            .foregroundStyle(theme.current.accentColor)
                            .font(.headline)
                        Text(isEditing ? "Edit Tag" : "New Tag")
                            .font(.headline.weight(.semibold))
                    }
                    Spacer()
                    Button {
                        onDismiss()
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)
                .padding(.bottom, 12)

                // Content
                ScrollView(.vertical) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 12) {
                            Circle()
                                .fill(Color(hex: colorHex))
                                .frame(width: 22, height: 22)
                                .overlay(Circle().stroke(.white.opacity(0.3), lineWidth: 1))
                            TextField("Tag name", text: $name)
                                .font(.title3.weight(.semibold))
                                .focused($nameFocused)
                                .textInputAutocapitalization(.never)
                                .submitLabel(.done)
                        }
                        .padding(.horizontal, 18)
                        .frame(height: 56)
                        .glassEffect(in: RoundedRectangle(cornerRadius: 16))

                        VStack(alignment: .leading, spacing: 8) {
                            Text("COLOR")
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 4)
                            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6), spacing: 8) {
                                ForEach(HomeboxTagPalette, id: \.self) { hex in
                                    Circle()
                                        .fill(Color(hex: hex))
                                        .frame(width: 32, height: 32)
                                        .overlay(
                                            Circle()
                                                .stroke(colorHex == hex ? Color.white : Color.clear, lineWidth: 2)
                                        )
                                        .shadow(color: colorHex == hex ? Color(hex: hex).opacity(0.6) : Color.clear, radius: 4)
                                        .onTapGesture {
                                            colorHex = hex
                                            UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                        }
                                }
                            }
                            .padding(14)
                            .glassEffect(in: RoundedRectangle(cornerRadius: 16))
                        }

                        DescriptionField(text: $description, placeholder: "Description (optional)", title: "Description")

                        if let errorMsg {
                            HStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                Text(errorMsg).font(.callout)
                            }
                            .padding(.horizontal, 14).padding(.vertical, 10)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial))
                            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.red.opacity(0.5), lineWidth: 1))
                            .foregroundStyle(.primary)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.vertical, 4)
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollIndicators(.hidden)
                .frame(maxHeight: .infinity)

                // Action buttons
                actionButtons
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 16)
            }
        }
        .onAppear {
            if case .edit(let tag) = mode {
                name = tag.name
                description = tag.description ?? ""
                colorHex = tag.color ?? "#3b82f6"
            }
        }
    }

    private var isEditing: Bool { if case .edit = mode { return true } else { return false } }

    private var actionButtons: some View {
        Button {
            Task { await save() }
        } label: {
            HStack {
                if isSaving {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: isEditing ? "checkmark.circle.fill" : "plus.circle.fill")
                        .font(.body.weight(.semibold))
                }
                Text(isEditing ? "Save Tag" : "Create Tag")
                    .font(.body.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
        }
        .buttonStyle(.glassProminent)
        .disabled(isSaving || name.trimmingCharacters(in: .whitespaces).isEmpty)
    }

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
            onDismiss()
        } catch {
            errorMsg = error.localizedDescription
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
        isSaving = false
    }
}
