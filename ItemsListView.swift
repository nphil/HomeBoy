import SwiftUI
import UIKit

// MARK: - Thumbnail cache (plain class — rows update via local @State, not @Published)

@MainActor
class ThumbnailStore {
    private var cache: [String: String] = [:]   // itemId → attId or "" (no thumb)
    private var inFlight: [String: Task<String?, Never>] = [:]

    func load(itemId: String, client: HomeboxClient) async -> String? {
        if let cached = cache[itemId] { return cached.isEmpty ? nil : cached }
        if let task = inFlight[itemId] { return await task.value }
        let task = Task<String?, Never> {
            if let detail = try? await client.getItem(id: itemId),
               let att = (detail.attachments ?? []).first(where: { $0.primary == true })
                       ?? (detail.attachments ?? []).first(where: { $0.type.lowercased() == "photo" }) {
                return att.id
            }
            return nil
        }
        inFlight[itemId] = task
        let result = await task.value
        cache[itemId] = result ?? ""
        inFlight[itemId] = nil
        return result
    }
}

// MARK: - Section model

private struct ItemSection: Identifiable {
    let letter: String
    var id: String { letter }
    let items: [HBItem]
}

// MARK: - ItemsListView

struct ItemsListView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var allItems: [HBItem] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var query: String = ""
    @State private var lastLoadedAt: Date? = nil

    // View options
    @State private var viewMode: ViewMode = .list
    @State private var tileColumns = 2

    // Filters
    @State private var showFilters = false
    @State private var filterLocationId: String?
    @State private var filterTagIds: Set<String> = []
    @State private var showLocationFilterPicker = false
    @State private var showTagFilterPicker = false

    // Multi-select
    @State private var selectMode = false
    @State private var selectedIds: Set<String> = []
    @State private var showBulkEdit = false

    @State private var thumbStore = ThumbnailStore()
    @State private var indexLetter: String? = nil

    enum ViewMode { case list, tile }

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                VStack(spacing: 0) {
                    if showFilters {
                        filterPanel
                            .padding(.horizontal, 16)
                            .padding(.top, 8)
                            .padding(.bottom, 4)
                    }
                    contentArea
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .task { await load() }
            .onAppear { Task { await load() } }
            .onChange(of: filterTagIds) { _, _ in Task { await load(force: true) } }
            .navigationDestination(for: ItemDetailRoute.self) { route in
                ItemDetailView(itemId: route.id, onChange: { Task { await load(force: true) } })
                    .environmentObject(store)
                    .environmentObject(theme)
            }
            .navigationDestination(for: LocationDetailRoute.self) { route in
                LocationDetailView(locationId: route.id)
                    .environmentObject(store)
                    .environmentObject(theme)
            }
            .sheet(isPresented: $showLocationFilterPicker) {
                LocationPickerSheet(selectedId: $filterLocationId)
                    .environmentObject(store).environmentObject(theme)
            }
            .sheet(isPresented: $showTagFilterPicker) {
                TagPickerSheet(selectedIds: $filterTagIds)
                    .environmentObject(store).environmentObject(theme)
            }
            .sheet(isPresented: $showBulkEdit) {
                BulkEditSheet(itemIds: Array(selectedIds)) {
                    selectMode = false; selectedIds = []
                    Task { await load(force: true) }
                }
                .environmentObject(store).environmentObject(theme)
            }
            .safeAreaInset(edge: .bottom) {
                if selectMode && !selectedIds.isEmpty { bulkActionBar }
            }
        }
    }

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .principal) { BrandMark() }
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { showFilters.toggle() }
            } label: {
                Image(systemName: hasActiveFilters
                      ? "line.3.horizontal.decrease.circle.fill"
                      : "line.3.horizontal.decrease.circle")
            }

            Menu {
                Picker("View", selection: $viewMode) {
                    Label("List", systemImage: "list.bullet").tag(ViewMode.list)
                    Label("Tiles", systemImage: "square.grid.2x2").tag(ViewMode.tile)
                }
                .pickerStyle(.inline)

                if viewMode == .tile {
                    Divider()
                    Picker("Columns", selection: $tileColumns) {
                        Text("2 columns").tag(2)
                        Text("3 columns").tag(3)
                        Text("4 columns").tag(4)
                    }
                    .pickerStyle(.inline)
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }

            Button {
                withAnimation { selectMode.toggle(); if !selectMode { selectedIds = [] } }
            } label: {
                Text(selectMode ? "Done" : "Select").font(.callout)
            }
        }
    }

    // MARK: - Filter panel

    private var hasActiveFilters: Bool { filterLocationId != nil || !filterTagIds.isEmpty }

    private var filterPanel: some View {
        HStack(spacing: 8) {
            filterChip(
                label: filterLocationId.flatMap { store.pathString(forLocationId: $0) } ?? "Location",
                icon: "mappin.circle.fill",
                isActive: filterLocationId != nil,
                onTap: { if filterLocationId != nil { filterLocationId = nil } else { showLocationFilterPicker = true } },
                onLongPress: { showLocationFilterPicker = true }
            )
            filterChip(
                label: filterTagIds.isEmpty ? "Tags" : "\(filterTagIds.count) tag\(filterTagIds.count == 1 ? "" : "s")",
                icon: "tag.fill",
                isActive: !filterTagIds.isEmpty,
                onTap: { if !filterTagIds.isEmpty { filterTagIds = [] } else { showTagFilterPicker = true } },
                onLongPress: { showTagFilterPicker = true }
            )
            Spacer()
            if hasActiveFilters {
                Button("Clear") { filterLocationId = nil; filterTagIds = [] }
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func filterChip(label: String, icon: String, isActive: Bool,
                            onTap: @escaping () -> Void, onLongPress: @escaping () -> Void) -> some View {
        Button { onTap() } label: {
            HStack(spacing: 4) {
                Image(systemName: icon).foregroundStyle(isActive ? .white : theme.current.accentColor).font(.caption)
                Text(label).font(.caption.weight(.medium)).lineLimit(1)
                if isActive { Image(systemName: "xmark").font(.caption2) }
            }
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(isActive ? theme.current.accentColor : Color.secondary.opacity(0.15))
            .foregroundStyle(isActive ? .white : .primary)
            .clipShape(Capsule())
        }
        .buttonStyle(.plain)
        .simultaneousGesture(LongPressGesture().onEnded { _ in onLongPress() })
    }

    // MARK: - Content

    @ViewBuilder
    private var contentArea: some View {
        if !store.isAuthenticated {
            notSignedIn
        } else if isLoading && allItems.isEmpty {
            Spacer(); ProgressView("Loading items…"); Spacer()
        } else if let loadError, allItems.isEmpty {
            errorState(loadError)
        } else if allItems.isEmpty {
            emptyState
        } else if filteredItems.isEmpty {
            noResultsState
        } else {
            switch viewMode {
            case .list: listView
            case .tile: tileView
            }
        }
    }

    // MARK: - List view (A-Z sections + index bar)

    private var listView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6, pinnedViews: .sectionHeaders) {
                    ForEach(itemSections) { section in
                        Section {
                            ForEach(section.items) { item in
                                itemListRow(item)
                                    .padding(.horizontal, 16)
                            }
                        } header: {
                            HStack {
                                Text(section.letter)
                                    .font(.caption.weight(.bold))
                                    .tracking(0.5)
                                    .foregroundStyle(.secondary)
                                Spacer()
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 4)
                            .background(.ultraThinMaterial)
                            .id(section.letter)
                        }
                    }
                }
                .padding(.top, 4)
                .padding(.bottom, 80)
            }
            .scrollIndicators(.hidden)
            .searchable(text: $query, prompt: "Search items")
            .refreshable { await load(force: true) }
            .overlay(alignment: .trailing) {
                if !sectionLetters.isEmpty {
                    AlphabetIndexBar(letters: sectionLetters, currentLetter: $indexLetter) { letter in
                        withAnimation { proxy.scrollTo(letter, anchor: .center) }
                    }
                    .padding(.trailing, 2)
                    .padding(.vertical, 16)
                }
            }
            .overlay {
                if let letter = indexLetter {
                    LetterPopupBox(letter: letter, accent: theme.current.accentColor)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }
            }
        }
    }

    // MARK: - Tile view

    private var tileView: some View {
        ScrollView {
            LazyVGrid(
                columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: tileColumns),
                spacing: 10
            ) {
                ForEach(filteredItems.sorted { $0.name.lowercased() < $1.name.lowercased() }) { item in
                    itemTile(item)
                }
            }
            .padding(12)
            .padding(.bottom, 80)
        }
        .scrollIndicators(.hidden)
        .scrollContentBackground(.hidden)
        .background(theme.current.backgroundColor)
        .searchable(text: $query, prompt: "Search items")
        .refreshable { await load(force: true) }
    }

    // MARK: - Row

    @ViewBuilder
    private func itemListRow(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        ZStack(alignment: .topTrailing) {
            Group {
                if selectMode {
                    ItemListRowContent(item: item, thumbStore: thumbStore)
                        .contentShape(Rectangle()).onTapGesture { toggleSelection(item) }
                } else {
                    NavigationLink(value: ItemDetailRoute(id: item.id)) {
                        ItemListRowContent(item: item, thumbStore: thumbStore)
                    }.buttonStyle(.plain)
                }
            }
            .background {
                RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
            }
            .overlay(RoundedRectangle(cornerRadius: 14)
                .stroke(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                        lineWidth: isSelected ? 2 : 1))
            if selectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary.opacity(0.5))
                    .font(.title3).padding(10)
            }
        }
    }

    // MARK: - Tile

    @ViewBuilder
    private func itemTile(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        ZStack(alignment: .topTrailing) {
            Group {
                if selectMode {
                    ItemTileContent(item: item, thumbStore: thumbStore, columns: tileColumns)
                        .contentShape(Rectangle()).onTapGesture { toggleSelection(item) }
                } else {
                    NavigationLink(value: ItemDetailRoute(id: item.id)) {
                        ItemTileContent(item: item, thumbStore: thumbStore, columns: tileColumns)
                    }.buttonStyle(.plain)
                }
            }
            .background {
                RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
            }
            .overlay(RoundedRectangle(cornerRadius: 12)
                .stroke(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                        lineWidth: isSelected ? 2 : 1))
            .clipShape(RoundedRectangle(cornerRadius: 12))
            if selectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary.opacity(0.5))
                    .font(.body).padding(6)
            }
        }
    }

    // MARK: - Bulk bar

    private var bulkActionBar: some View {
        HStack(spacing: 12) {
            Text("\(selectedIds.count) selected").font(.callout.weight(.medium))
            Spacer()
            Button("Edit") { showBulkEdit = true }.buttonStyle(.glassProminent).controlSize(.small)
            Button("Deselect All") { selectedIds = [] }.buttonStyle(.glass).controlSize(.small)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .overlay(Rectangle().frame(height: 0.5).foregroundStyle(Color.primary.opacity(0.1)), alignment: .top)
    }

    // MARK: - Empty / error states

    private var notSignedIn: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.circle").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("Not connected").font(.title3.weight(.semibold))
            Text("Open Settings to enter your Homebox server URL and sign in.")
                .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 32)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "shippingbox").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("No items yet").font(.title3.weight(.semibold))
            Text("Add your first item from the Add tab.").font(.callout).foregroundStyle(.secondary)
        }
    }

    private var noResultsState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "magnifyingglass").font(.system(size: 40)).foregroundStyle(.secondary)
            Text("No matches").font(.title3.weight(.semibold))
            Text("Try adjusting your search or filters.").font(.callout).foregroundStyle(.secondary)
            Button("Clear filters") { filterLocationId = nil; filterTagIds = []; query = "" }.buttonStyle(.glass)
            Spacer()
        }
    }

    private func errorState(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 40)).foregroundStyle(.orange)
            Text("Couldn't load items").font(.title3.weight(.semibold))
            Text(message).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 24)
            Button("Try again") { Task { await load(force: true) } }.buttonStyle(.glass)
        }
    }

    // MARK: - Filtering & sections

    private var filteredItems: [HBItem] {
        var items = allItems
        if let locId = filterLocationId {
            items = items.filter { $0.location?.id == locId }
        }
        // Client-side tag filter — works if the summary includes labels/tags.
        // If the summary omits them, trust the server-side ?labels= filter and don't reject.
        if !filterTagIds.isEmpty {
            items = items.filter { item in
                guard let labels = item.effectiveLabels else { return true }
                return !Set(labels.map { $0.id }).isDisjoint(with: filterTagIds)
            }
        }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if !q.isEmpty {
            items = items.filter {
                $0.name.lowercased().contains(q) || ($0.description ?? "").lowercased().contains(q)
            }
        }
        return items
    }

    private var itemSections: [ItemSection] {
        var groups: [String: [HBItem]] = [:]
        for item in filteredItems {
            let key: String
            if let c = item.name.first, c.isLetter { key = String(c).uppercased() } else { key = "#" }
            groups[key, default: []].append(item)
        }
        return groups.keys.sorted { a, b in
            if a == "#" { return false }
            if b == "#" { return true }
            return a < b
        }.map { key in
            ItemSection(letter: key, items: groups[key]!.sorted { $0.name.lowercased() < $1.name.lowercased() })
        }
    }

    private var sectionLetters: [String] { itemSections.map { $0.letter } }

    private func toggleSelection(_ item: HBItem) {
        if selectedIds.contains(item.id) { selectedIds.remove(item.id) } else { selectedIds.insert(item.id) }
    }

    private func load(force: Bool = false) async {
        guard let client = store.client else { return }
        let stale = lastLoadedAt.map { Date().timeIntervalSince($0) > 60 } ?? true
        if !force && !allItems.isEmpty && !stale { return }
        isLoading = true; loadError = nil
        do {
            // Pass labelIds so server can pre-filter; client-side filter below catches cases where server ignores it
            let resp = try await client.listItems(labelIds: Array(filterTagIds), pageSize: 1000)
            allItems = resp.items
            store.updateCachedItemTotal(resp.total ?? resp.items.count)
            lastLoadedAt = Date()
        } catch { loadError = error.localizedDescription }
        isLoading = false
    }
}

// MARK: - List row content (local @State for thumbnail — no shared observable updates)

private struct ItemListRowContent: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    let item: HBItem
    let thumbStore: ThumbnailStore

    @State private var thumbAttId: String? = nil
    @State private var thumbLoaded = false

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            thumbnailView
                .frame(width: 48, height: 48)
                .clipShape(RoundedRectangle(cornerRadius: 9))

            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(.body.weight(.medium)).lineLimit(1)
                if let path = breadcrumb {
                    HStack(spacing: 3) {
                        Image(systemName: "mappin.and.ellipse").font(.caption2)
                        Text(path).font(.caption).monospaced().lineLimit(1)
                    }
                    .foregroundStyle(.secondary)
                }
                if let d = item.description, !d.isEmpty {
                    Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            Text("×\(item.quantityInt)").font(.caption.monospacedDigit().weight(.medium)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 10).padding(.vertical, 8)
        .task(id: item.id) {
            guard let client = store.client else { return }
            let attId = await thumbStore.load(itemId: item.id, client: client)
            thumbAttId = attId
            thumbLoaded = true
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if !thumbLoaded {
            ZStack {
                RoundedRectangle(cornerRadius: 9).fill(theme.current.accentColor.opacity(0.10))
                ProgressView().controlSize(.small)
            }
        } else if let attId = thumbAttId {
            AuthImage(itemId: item.id, attachmentId: attId).scaledToFill()
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 9).fill(theme.current.accentColor.opacity(0.12))
                Text("\(item.quantityInt)").font(.callout.weight(.semibold).monospacedDigit())
                    .foregroundStyle(theme.current.accentColor)
            }
        }
    }

    private var breadcrumb: String? {
        if let id = item.location?.id { let p = store.pathString(forLocationId: id); if !p.isEmpty { return p } }
        return item.location?.name
    }
}

// MARK: - Tile content (same local @State pattern)

private struct ItemTileContent: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    let item: HBItem
    let thumbStore: ThumbnailStore
    let columns: Int

    @State private var thumbAttId: String? = nil
    @State private var thumbLoaded = false

    private var thumbHeight: CGFloat { columns <= 2 ? 108 : columns == 3 ? 80 : 62 }
    private var namePad: CGFloat { columns <= 3 ? 8 : 6 }
    private var nameFont: Font { columns <= 2 ? .callout.weight(.semibold) : .caption.weight(.semibold) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                thumbnailView.frame(maxWidth: .infinity).frame(height: thumbHeight).clipped()
                Text("×\(item.quantityInt)")
                    .font(.caption2.monospacedDigit().weight(.semibold))
                    .padding(.horizontal, 5).padding(.vertical, 2)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .padding(5)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(nameFont).lineLimit(columns <= 3 ? 2 : 1)
                    .fixedSize(horizontal: false, vertical: true)
                if columns <= 3, let path = breadcrumb {
                    Text(path).font(.caption2).foregroundStyle(.secondary).lineLimit(1).monospaced()
                }
            }
            .padding(namePad)
        }
        .task(id: item.id) {
            guard let client = store.client else { return }
            let attId = await thumbStore.load(itemId: item.id, client: client)
            thumbAttId = attId
            thumbLoaded = true
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if !thumbLoaded {
            ZStack { theme.current.accentColor.opacity(0.10); ProgressView().controlSize(.small) }
        } else if let attId = thumbAttId {
            AuthImage(itemId: item.id, attachmentId: attId).scaledToFill()
        } else {
            ZStack {
                theme.current.accentColor.opacity(0.10)
                Image(systemName: "shippingbox.fill")
                    .font(.system(size: columns <= 2 ? 30 : columns == 3 ? 22 : 16))
                    .foregroundStyle(theme.current.accentColor.opacity(0.35))
            }
        }
    }

    private var breadcrumb: String? {
        if let id = item.location?.id { let p = store.pathString(forLocationId: id); if !p.isEmpty { return p } }
        return item.location?.name
    }
}

// MARK: - Bulk edit sheet

struct BulkEditSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let itemIds: [String]
    var onComplete: () -> Void = {}

    @State private var applyLocation = false
    @State private var locationId: String?
    @State private var applyTags = false
    @State private var tagIds: Set<String> = []
    @State private var showLocationPicker = false
    @State private var showTagPicker = false
    @State private var isSaving = false
    @State private var progress = 0
    @State private var errorMsg: String?

    var body: some View {
        NavigationStack {
            Form {
                Section { Text("\(itemIds.count) item\(itemIds.count == 1 ? "" : "s") selected").foregroundStyle(.secondary) }

                Section("Change location") {
                    Toggle("Apply to all selected", isOn: $applyLocation).tint(theme.current.accentColor)
                    if applyLocation {
                        Button { showLocationPicker = true } label: {
                            HStack {
                                Image(systemName: "mappin.and.ellipse")
                                Text(locationId.flatMap { store.pathString(forLocationId: $0) } ?? "Pick location")
                                    .foregroundStyle(locationId == nil ? .secondary : .primary)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }

                Section("Change tags") {
                    Toggle("Apply to all selected", isOn: $applyTags).tint(theme.current.accentColor)
                    if applyTags {
                        Button { showTagPicker = true } label: {
                            HStack {
                                Image(systemName: "tag")
                                Text(tagIds.isEmpty ? "Pick tags" : "\(tagIds.count) tag\(tagIds.count == 1 ? "" : "s")")
                                    .foregroundStyle(tagIds.isEmpty ? .secondary : .primary)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                    }
                }

                if isSaving {
                    Section { HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Updating \(progress) of \(itemIds.count)…").font(.callout) } }
                }
                if let errorMsg {
                    Section { Label(errorMsg, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.callout) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("Bulk Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { Task { await save() } }.bold()
                        .disabled(isSaving || (!applyLocation && !applyTags) || (applyLocation && locationId == nil))
                }
            }
            .sheet(isPresented: $showLocationPicker) { LocationPickerSheet(selectedId: $locationId).environmentObject(store).environmentObject(theme) }
            .sheet(isPresented: $showTagPicker) { TagPickerSheet(selectedIds: $tagIds).environmentObject(store).environmentObject(theme) }
        }
    }

    private func save() async {
        guard let client = store.client else { return }
        isSaving = true; errorMsg = nil; progress = 0
        for id in itemIds {
            do {
                let detail = try await client.getItem(id: id)
                var update = HBItemUpdate(from: detail, overrideLocationId: applyLocation ? locationId : nil,
                                         overrideTagIds: applyTags ? Array(tagIds) : nil)
                if applyLocation, let locId = locationId { update.locationId = locId }
                try await client.updateItem(update)
                progress += 1
            } catch {
                errorMsg = "Error on item \(progress + 1): \(error.localizedDescription)"
                isSaving = false; return
            }
        }
        isSaving = false
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onComplete(); dismiss()
    }
}
