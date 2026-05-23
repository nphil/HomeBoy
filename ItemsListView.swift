import SwiftUI
import UIKit

// MARK: - Thumbnail cache

/// Shared, observable cache for primary attachment IDs.
/// Each item's ID maps to its primary attachment ID once fetched.
/// An empty string means "confirmed no photo."
@MainActor
class ThumbnailStore: ObservableObject {
    @Published private(set) var cache: [String: String] = [:]
    private var inFlight: Set<String> = []

    func loadIfNeeded(_ itemId: String, client: HomeboxClient) {
        guard cache[itemId] == nil, !inFlight.contains(itemId) else { return }
        inFlight.insert(itemId)
        Task {
            if let detail = try? await client.getItem(id: itemId),
               let att = (detail.attachments ?? []).first(where: { $0.primary == true })
                       ?? (detail.attachments ?? []).first(where: { $0.type.lowercased() == "photo" }) {
                cache[itemId] = att.id
            } else {
                cache[itemId] = ""
            }
            inFlight.remove(itemId)
        }
    }

    func attachmentId(for itemId: String) -> String? {
        guard let v = cache[itemId], !v.isEmpty else { return nil }
        return v
    }

    func isLoaded(for itemId: String) -> Bool { cache[itemId] != nil }
}

// MARK: - ItemsListView

struct ItemsListView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var allItems: [HBItem] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var query: String = ""

    // View mode
    @State private var viewMode: ViewMode = .list

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

    @StateObject private var thumbStore = ThumbnailStore()

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
                    selectMode = false
                    selectedIds = []
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
                .foregroundStyle(hasActiveFilters ? theme.current.accentColor : .primary)
            }
            Button {
                withAnimation(.easeInOut(duration: 0.2)) {
                    viewMode = viewMode == .list ? .tile : .list
                }
            } label: {
                Image(systemName: viewMode == .list ? "square.grid.2x2" : "list.bullet")
            }
            Button {
                withAnimation {
                    selectMode.toggle()
                    if !selectMode { selectedIds = [] }
                }
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
                onTap: {
                    if filterLocationId != nil { filterLocationId = nil }
                    else { showLocationFilterPicker = true }
                },
                onLongPress: { showLocationFilterPicker = true }
            )
            filterChip(
                label: filterTagIds.isEmpty ? "Tags" : "\(filterTagIds.count) tag\(filterTagIds.count == 1 ? "" : "s")",
                icon: "tag.fill",
                isActive: !filterTagIds.isEmpty,
                onTap: {
                    if !filterTagIds.isEmpty { filterTagIds = [] }
                    else { showTagFilterPicker = true }
                },
                onLongPress: { showTagFilterPicker = true }
            )
            Spacer()
            if hasActiveFilters {
                Button("Clear") {
                    filterLocationId = nil
                    filterTagIds = []
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }

    private func filterChip(label: String, icon: String, isActive: Bool,
                            onTap: @escaping () -> Void,
                            onLongPress: @escaping () -> Void) -> some View {
        Button { onTap() } label: {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .foregroundStyle(isActive ? .white : theme.current.accentColor)
                    .font(.caption)
                Text(label)
                    .font(.caption.weight(.medium))
                    .lineLimit(1)
                if isActive {
                    Image(systemName: "xmark").font(.caption2)
                }
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
            Spacer()
            ProgressView("Loading items…")
            Spacer()
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

    private var listView: some View {
        List {
            ForEach(filteredItems) { item in
                itemListRow(item)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .searchable(text: $query, prompt: "Search items")
        .refreshable { await load(force: true) }
    }

    private var tileView: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 155, maximum: 195), spacing: 12)],
                spacing: 12
            ) {
                ForEach(filteredItems) { item in
                    itemTile(item)
                }
            }
            .padding(16)
            .padding(.bottom, 80)
        }
        .scrollContentBackground(.hidden)
        .background(theme.current.backgroundColor)
        .searchable(text: $query, prompt: "Search items")
        .refreshable { await load(force: true) }
    }

    // MARK: - Row (list view)

    @ViewBuilder
    private func itemListRow(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        ZStack(alignment: .topTrailing) {
            Group {
                if selectMode {
                    ItemListRowContent(item: item, thumbStore: thumbStore)
                        .contentShape(Rectangle())
                        .onTapGesture { toggleSelection(item) }
                } else {
                    NavigationLink(value: ItemDetailRoute(id: item.id)) {
                        ItemListRowContent(item: item, thumbStore: thumbStore)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background {
                RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
            }
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                            lineWidth: isSelected ? 2 : 1)
            )

            if selectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary.opacity(0.5))
                    .font(.title3)
                    .padding(10)
            }
        }
    }

    // MARK: - Tile (tile view)

    @ViewBuilder
    private func itemTile(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        ZStack(alignment: .topTrailing) {
            Group {
                if selectMode {
                    ItemTileContent(item: item, thumbStore: thumbStore)
                        .contentShape(Rectangle())
                        .onTapGesture { toggleSelection(item) }
                } else {
                    NavigationLink(value: ItemDetailRoute(id: item.id)) {
                        ItemTileContent(item: item, thumbStore: thumbStore)
                    }
                    .buttonStyle(.plain)
                }
            }
            .background {
                RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
                RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
            }
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                            lineWidth: isSelected ? 2 : 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 14))

            if selectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary.opacity(0.5))
                    .font(.title3)
                    .padding(8)
            }
        }
    }

    // MARK: - Bulk action bar

    private var bulkActionBar: some View {
        HStack(spacing: 12) {
            Text("\(selectedIds.count) selected")
                .font(.callout.weight(.medium))
            Spacer()
            Button("Edit") { showBulkEdit = true }
                .buttonStyle(.glassProminent)
                .controlSize(.small)
            Button("Deselect All") { selectedIds = [] }
                .buttonStyle(.glass)
                .controlSize(.small)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .overlay(Rectangle().frame(height: 0.5).foregroundStyle(Color.primary.opacity(0.1)), alignment: .top)
    }

    // MARK: - Empty / error states

    private var notSignedIn: some View {
        VStack(spacing: 12) {
            Image(systemName: "link.circle").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("Not connected").font(.title3.weight(.semibold))
            Text("Open Settings to enter your Homebox server URL and sign in.")
                .font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 32)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "shippingbox").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("No items yet").font(.title3.weight(.semibold))
            Text("Add your first item from the Add tab.")
                .font(.callout).foregroundStyle(.secondary)
        }
    }

    private var noResultsState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "magnifyingglass").font(.system(size: 40)).foregroundStyle(.secondary)
            Text("No matches").font(.title3.weight(.semibold))
            Text("Try adjusting your search or filters.")
                .font(.callout).foregroundStyle(.secondary)
            Button("Clear filters") {
                filterLocationId = nil
                filterTagIds = []
                query = ""
            }
            .buttonStyle(.glass)
            Spacer()
        }
    }

    private func errorState(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle").font(.system(size: 40)).foregroundStyle(.orange)
            Text("Couldn't load items").font(.title3.weight(.semibold))
            Text(message).font(.callout).foregroundStyle(.secondary)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            Button("Try again") { Task { await load(force: true) } }
                .buttonStyle(.glass)
        }
    }

    // MARK: - Filtering

    private var filteredItems: [HBItem] {
        var items = allItems
        if let locId = filterLocationId {
            items = items.filter { $0.location?.id == locId }
        }
        if !filterTagIds.isEmpty {
            items = items.filter { item in
                guard let labels = item.labels else { return false }
                return !Set(labels.map { $0.id }).isDisjoint(with: filterTagIds)
            }
        }
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if !q.isEmpty {
            items = items.filter {
                $0.name.lowercased().contains(q) ||
                ($0.description ?? "").lowercased().contains(q)
            }
        }
        return items
    }

    private func toggleSelection(_ item: HBItem) {
        if selectedIds.contains(item.id) { selectedIds.remove(item.id) }
        else { selectedIds.insert(item.id) }
    }

    private func load(force: Bool = false) async {
        guard let client = store.client else { return }
        if !force && !allItems.isEmpty { return }
        isLoading = true; loadError = nil
        do {
            let resp = try await client.listItems(pageSize: 1000)
            let pulled = resp.items.sorted { ($0.createdAt ?? "") > ($1.createdAt ?? "") }
            allItems = pulled
            store.updateCachedItemTotal(resp.total ?? pulled.count)
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }
}

// MARK: - List row content

private struct ItemListRowContent: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    let item: HBItem
    @ObservedObject var thumbStore: ThumbnailStore

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            thumbnailView
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).font(.body.weight(.medium)).lineLimit(2)
                if let path = breadcrumb {
                    HStack(spacing: 4) {
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
            Text("×\(item.quantityInt)")
                .font(.caption.monospacedDigit().weight(.medium))
                .foregroundStyle(.secondary)
        }
        .padding(12)
        .task(id: item.id) {
            guard let client = store.client else { return }
            thumbStore.loadIfNeeded(item.id, client: client)
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if !thumbStore.isLoaded(for: item.id) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(theme.current.accentColor.opacity(0.10))
                ProgressView().controlSize(.small)
            }
        } else if let attId = thumbStore.attachmentId(for: item.id) {
            AuthImage(itemId: item.id, attachmentId: attId)
                .scaledToFill()
        } else {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(theme.current.accentColor.opacity(0.12))
                Text("\(item.quantityInt)")
                    .font(.title3.weight(.semibold).monospacedDigit())
                    .foregroundStyle(theme.current.accentColor)
            }
        }
    }

    private var breadcrumb: String? {
        if let id = item.location?.id {
            let path = store.pathString(forLocationId: id)
            if !path.isEmpty { return path }
        }
        return item.location?.name
    }
}

// MARK: - Tile content

private struct ItemTileContent: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    let item: HBItem
    @ObservedObject var thumbStore: ThumbnailStore

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack(alignment: .topTrailing) {
                thumbnailView
                    .frame(maxWidth: .infinity)
                    .frame(height: 115)
                    .clipped()

                Text("×\(item.quantityInt)")
                    .font(.caption2.monospacedDigit().weight(.semibold))
                    .padding(.horizontal, 6).padding(.vertical, 3)
                    .background(Capsule().fill(.ultraThinMaterial))
                    .padding(6)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(.callout.weight(.semibold))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                if let path = breadcrumb {
                    Text(path)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .monospaced()
                }
            }
            .padding(10)
        }
        .task(id: item.id) {
            guard let client = store.client else { return }
            thumbStore.loadIfNeeded(item.id, client: client)
        }
    }

    @ViewBuilder
    private var thumbnailView: some View {
        if !thumbStore.isLoaded(for: item.id) {
            ZStack {
                theme.current.accentColor.opacity(0.10)
                ProgressView().controlSize(.small)
            }
        } else if let attId = thumbStore.attachmentId(for: item.id) {
            AuthImage(itemId: item.id, attachmentId: attId)
                .scaledToFill()
        } else {
            ZStack {
                theme.current.accentColor.opacity(0.10)
                Image(systemName: "shippingbox.fill")
                    .font(.system(size: 34))
                    .foregroundStyle(theme.current.accentColor.opacity(0.35))
            }
        }
    }

    private var breadcrumb: String? {
        if let id = item.location?.id {
            let path = store.pathString(forLocationId: id)
            if !path.isEmpty { return path }
        }
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
                Section {
                    Text("\(itemIds.count) item\(itemIds.count == 1 ? "" : "s") selected")
                        .foregroundStyle(.secondary)
                }

                Section("Change location") {
                    Toggle("Apply to all selected", isOn: $applyLocation)
                        .tint(theme.current.accentColor)
                    if applyLocation {
                        Button {
                            showLocationPicker = true
                        } label: {
                            HStack {
                                Image(systemName: "mappin.and.ellipse")
                                Text(locationId.flatMap { store.pathString(forLocationId: $0) } ?? "Pick location")
                                    .foregroundStyle(locationId == nil ? .secondary : .primary)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                Section("Change tags") {
                    Toggle("Apply to all selected", isOn: $applyTags)
                        .tint(theme.current.accentColor)
                    if applyTags {
                        Button {
                            showTagPicker = true
                        } label: {
                            HStack {
                                Image(systemName: "tag")
                                Text(tagIds.isEmpty ? "Pick tags" : "\(tagIds.count) tag\(tagIds.count == 1 ? "" : "s")")
                                    .foregroundStyle(tagIds.isEmpty ? .secondary : .primary)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                if isSaving {
                    Section {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("Updating \(progress) of \(itemIds.count)…").font(.callout)
                        }
                    }
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
            .navigationTitle("Bulk Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { Task { await save() } }
                        .bold()
                        .disabled(isSaving || (!applyLocation && !applyTags)
                                  || (applyLocation && locationId == nil))
                }
            }
            .sheet(isPresented: $showLocationPicker) {
                LocationPickerSheet(selectedId: $locationId)
                    .environmentObject(store).environmentObject(theme)
            }
            .sheet(isPresented: $showTagPicker) {
                TagPickerSheet(selectedIds: $tagIds)
                    .environmentObject(store).environmentObject(theme)
            }
        }
    }

    private func save() async {
        guard let client = store.client else { return }
        isSaving = true; errorMsg = nil; progress = 0
        for id in itemIds {
            do {
                let detail = try await client.getItem(id: id)
                var update = HBItemUpdate(
                    from: detail,
                    overrideLocationId: applyLocation ? locationId : nil,
                    overrideTagIds: applyTags ? Array(tagIds) : nil
                )
                if applyLocation, let locId = locationId { update.locationId = locId }
                try await client.updateItem(update)
                progress += 1
            } catch {
                errorMsg = "Error on item \(progress + 1): \(error.localizedDescription)"
                isSaving = false
                return
            }
        }
        isSaving = false
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onComplete()
        dismiss()
    }
}
