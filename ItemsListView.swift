import SwiftUI
import UIKit
import NaturalLanguage



// MARK: - Section model

private struct ItemSection: Identifiable {
    let letter: String
    var id: String { letter }
    let items: [HBItem]
}

// MARK: - SortOption

enum SortOption: String, CaseIterable, Identifiable {
    case nameAZ = "nameAZ"
    case nameZA = "nameZA"
    case dateNewest = "dateNewest"
    case dateOldest = "dateOldest"
    case quantityHighToLow = "quantityHighToLow"
    case quantityLowToHigh = "quantityLowToHigh"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .nameAZ: return "Name: A-Z"
        case .nameZA: return "Name: Z-A"
        case .dateNewest: return "Date: Newest"
        case .dateOldest: return "Date: Oldest"
        case .quantityHighToLow: return "Quantity: High to Low"
        case .quantityLowToHigh: return "Quantity: Low to High"
        }
    }

    var shortLabel: String {
        switch self {
        case .nameAZ: return "Sort: A-Z"
        case .nameZA: return "Sort: Z-A"
        case .dateNewest: return "Sort: Newest"
        case .dateOldest: return "Sort: Oldest"
        case .quantityHighToLow: return "Sort: Qty High-Low"
        case .quantityLowToHigh: return "Sort: Qty Low-High"
        }
    }

    var iconName: String {
        switch self {
        case .nameAZ: return "text.sort.ascending"
        case .nameZA: return "text.sort.descending"
        case .dateNewest: return "clock.fill"
        case .dateOldest: return "clock"
        case .quantityHighToLow: return "arrow.up.circle.fill"
        case .quantityLowToHigh: return "arrow.down.circle.fill"
        }
    }
}

// MARK: - ItemsListView

struct ItemsListView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.showSiteMenu) var showSiteMenu
    
    @Binding var globalSearchQuery: String

    @State private var allItems: [HBItem] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var query: String = ""
    @State private var lastLoadedAt: Date? = nil

    // View options
    @AppStorage("itemsViewMode") private var viewMode: ViewMode = .list
    @AppStorage("itemsSortOption") private var sortOption: SortOption = .nameAZ
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
    @State private var showAddSheet = false

    @State private var thumbStore = ThumbnailStore()
    @State private var indexLetter: String? = nil
    @State private var semanticResults: [HBItem]? = nil
    @State private var semanticSearchTask: Task<Void, Never>? = nil
    @State private var isSearchActive = false

    @AppStorage("showQRScannerFAB") private var showQRScannerFAB = true
    @State private var showQRScanner = false
    @State private var qrFoundItemId: String? = nil

    enum ViewMode: String { case list, tile }

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                contentArea
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                if store.isAuthenticated && !selectMode {
                    VStack {
                        Spacer()
                        HStack {
                            Spacer()
                            VStack(spacing: 12) {
                                if showQRScannerFAB {
                                    Button { showQRScanner = true } label: {
                                        Image(systemName: "qrcode.viewfinder")
                                            .font(.title3.weight(.semibold))
                                            .foregroundStyle(.white)
                                            .frame(width: 46, height: 46)
                                            .background(theme.current.accentColor.opacity(0.85))
                                            .clipShape(Circle())
                                            .shadow(color: theme.current.accentColor.opacity(0.3), radius: 4, x: 0, y: 3)
                                    }
                                }
                                Button {
                                    withAnimation(.spring(response: 0.28, dampingFraction: 0.8)) {
                                        showAddSheet = true
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
                            }
                            .padding()
                        }
                    }
                }
            }
            .toolbar {
                if selectMode {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Archive") {
                            Task { await archiveSelected() }
                        }
                        .foregroundStyle(.orange)
                        .disabled(selectedIds.isEmpty)
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("Done") {
                            withAnimation {
                                selectMode = false
                                selectedIds = []
                            }
                        }
                        .font(.body.bold())
                        .foregroundStyle(theme.current.accentColor)
                    }
                    ToolbarItemGroup(placement: .bottomBar) {
                        Button("Select All") {
                            selectedIds = Set(filteredItems.map { $0.id })
                        }
                        
                        Spacer()
                        
                        Button("Deselect All") {
                            selectedIds = []
                        }
                        .disabled(selectedIds.isEmpty)
                        
                        Spacer()
                        
                        Button("Edit") {
                            showBulkEdit = true
                        }
                        .bold()
                        .disabled(selectedIds.isEmpty)
                    }
                } else {
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
                    if store.isAuthenticated {
                        ToolbarItemGroup(placement: .topBarTrailing) {
                            Button {
                                isSearchActive = true
                            } label: {
                                Image(systemName: "magnifyingglass")
                            }

                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) { showFilters.toggle() }
                            } label: {
                                Image(systemName: hasActiveFilters
                                      ? "line.3.horizontal.decrease.circle.fill"
                                      : "line.3.horizontal.decrease.circle")
                            }

                            Button {
                                withAnimation(.easeInOut(duration: 0.2)) {
                                    viewMode = viewMode == .list ? .tile : .list
                                }
                            } label: {
                                Image(systemName: viewMode == .list ? "square.grid.2x2" : "list.bullet")
                            }
                        }
                    }
                }
            }
            .task { await load() }
            .onAppear { Task { await load() } }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                Task { await load(force: true) }
            }
            .onChange(of: filterTagIds) { _, _ in Task { await load(force: true) } }
            .onChange(of: globalSearchQuery) { _, newQuery in updateSemanticSearch(for: newQuery) }
            .modifier(ConditionalSearchable(text: $globalSearchQuery, isPresented: $isSearchActive, prompt: "Search items…"))
            .onChange(of: store.activeGroupId) { _, _ in
                // Collection switched — wipe local caches and re-fetch with the new tenant
                allItems = []
                selectedIds = []
                selectMode = false
                filterLocationId = nil
                filterTagIds = []
                Task { await load(force: true) }
            }
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
                BulkEditSheet(items: allItems.filter { selectedIds.contains($0.id) }) {
                    selectMode = false; selectedIds = []
                    Task { await load(force: true) }
                }
                .environmentObject(store).environmentObject(theme)
            }

            .sheet(isPresented: $showQRScanner) {
                BarcodeScannerSheet(mode: .qr) { code in
                    showQRScanner = false
                    Task { await lookupAsset(code) }
                }
                .ignoresSafeArea()
            }
            .sheet(isPresented: Binding(
                get: { qrFoundItemId != nil },
                set: { if !$0 { qrFoundItemId = nil } }
            )) {
                if let id = qrFoundItemId {
                    NavigationStack {
                        ItemDetailView(itemId: id) { Task { await load(force: true) } }
                            .environmentObject(store).environmentObject(theme)
                    }
                }
            }
            .toolbar(selectMode ? .hidden : .visible, for: .tabBar)
            .navigationTitle(selectMode ? (selectedIds.isEmpty ? "Select Items" : "\(selectedIds.count) Selected") : "")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $showAddSheet) {
                AddItemView(onDismiss: {
                    showAddSheet = false
                    Task { await load(force: true) }
                })
                .presentationDetents([.fraction(0.85)])
                .presentationDragIndicator(.hidden)
                .presentationBackground {
                    ZStack {
                        UnevenRoundedRectangle(topLeadingRadius: 28, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: 28)
                            .fill(.ultraThinMaterial)
                        UnevenRoundedRectangle(topLeadingRadius: 28, bottomLeadingRadius: 0, bottomTrailingRadius: 0, topTrailingRadius: 28)
                            .stroke(theme.current.accentColor.opacity(0.20), lineWidth: 1.5)
                    }
                }
                .presentationCornerRadius(28)
                .environmentObject(store)
                .environmentObject(theme)
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
            
            Menu {
                Picker("Sort By", selection: $sortOption) {
                    ForEach(SortOption.allCases) { option in
                        Label(option.displayName, systemImage: option.iconName)
                            .tag(option)
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Image(systemName: sortOption.iconName)
                        .foregroundStyle(sortOption != .nameAZ ? .white : theme.current.accentColor)
                        .font(.caption)
                    Text(sortOption.shortLabel)
                        .font(.caption.weight(.medium))
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2)
                        .foregroundStyle(sortOption != .nameAZ ? .white : .secondary)
                }
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(sortOption != .nameAZ ? theme.current.accentColor : Color.secondary.opacity(0.15))
                .foregroundStyle(sortOption != .nameAZ ? .white : .primary)
                .clipShape(Capsule())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)

            Spacer()
            if hasActiveFilters {
                Button("Clear") { filterLocationId = nil; filterTagIds = [] }
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func filterChip(label: String, icon: String, isActive: Bool,
                            onTap: @escaping () -> Void, onLongPress: @escaping () -> Void) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon).foregroundStyle(isActive ? .white : theme.current.accentColor).font(.caption)
            Text(label).font(.caption.weight(.medium)).lineLimit(1)
            if isActive { Image(systemName: "xmark").font(.caption2) }
        }
        .padding(.horizontal, 10).padding(.vertical, 6)
        .background(isActive ? theme.current.accentColor : Color.secondary.opacity(0.15))
        .foregroundStyle(isActive ? .white : .primary)
        .clipShape(Capsule())
        .contentShape(Capsule())
        .onTapGesture {
            onTap()
        }
        .onLongPressGesture(minimumDuration: 0.5) {
            onLongPress()
        }
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
        } else {
            ZStack(alignment: .top) {
                if filteredItems.isEmpty {
                    noResultsState
                        .padding(.top, showFilters ? 50 : 0)
                } else {
                    switch viewMode {
                    case .list: listView
                    case .tile: tileView
                    }
                }

                if showFilters {
                    filterPanel
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 8)
                        .background(
                            LinearGradient(
                                colors: [
                                    theme.current.backgroundColor,
                                    theme.current.backgroundColor.opacity(0.95),
                                    theme.current.backgroundColor.opacity(0)
                                ],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .transition(.move(edge: .top).combined(with: .opacity))
                }
            }
        }
    }

    // MARK: - List view (A-Z sections + index bar)

    private var listView: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6, pinnedViews: .sectionHeaders) {
                    if isSortedAlphabetically {
                        ForEach(itemSections) { section in
                            Section {
                                ForEach(section.items) { item in
                                    itemListRow(item)
                                        .padding(.horizontal, 16)
                                }
                            } header: {
                                HStack(spacing: 8) {
                                    Text(section.letter)
                                        .font(.caption.weight(.bold))
                                        .tracking(0.5)
                                        .foregroundStyle(theme.current.accentColor)
                                    
                                    Rectangle()
                                        .fill(theme.current.accentColor.opacity(0.4))
                                        .frame(height: 1)
                                    
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 20)
                                .padding(.vertical, 8)
                                .background(Color.clear)
                                .id(section.letter)
                            }
                        }
                    } else {
                        ForEach(sortedItems) { item in
                            itemListRow(item)
                                .padding(.horizontal, 16)
                        }
                    }
                }
                .padding(.top, 4)
                .padding(.bottom, 80)
            }
            .coordinateSpace(name: "pullToSearch")
            .scrollDismissesKeyboard(.interactively)
            .scrollIndicators(.hidden)
            .refreshable { await load(force: true) }
            .safeAreaPadding(.top, showFilters ? 50 : 0)
            .overlay(alignment: .trailing) {
                if isSortedAlphabetically && !sectionLetters.isEmpty {
                    AlphabetIndexBar(letters: sectionLetters, currentLetter: $indexLetter) { letter in
                        withAnimation { proxy.scrollTo(letter, anchor: .center) }
                    }
                    .padding(.trailing, 2)
                    .padding(.vertical, 16)
                }
            }
            .overlay {
                if isSortedAlphabetically, let letter = indexLetter {
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
                ForEach(sortedItems) { item in
                    itemTile(item)
                }
            }
            .padding(12)
            .padding(.bottom, 80)
        }
        .coordinateSpace(name: "pullToSearch")
        .scrollDismissesKeyboard(.interactively)
        .scrollIndicators(.hidden)
        .scrollContentBackground(.hidden)
        .background(theme.current.backgroundColor)
        .refreshable { await load(force: true) }
        .safeAreaPadding(.top, showFilters ? 50 : 0)
    }

    // MARK: - Row

    @ViewBuilder
    private func itemListRow(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        HStack(spacing: 0) {
            if selectMode {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? theme.current.accentColor : Color.secondary.opacity(0.5))
                    .font(.title3)
                    .padding(.leading, 12)
                    .transition(.move(edge: .leading).combined(with: .opacity))
            }
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
        }
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(isSelected ? 0.15 : 0.06))
        }
        .overlay(RoundedRectangle(cornerRadius: 14)
            .stroke(isSelected ? theme.current.accentColor.opacity(0.6) : theme.current.accentColor.opacity(0.18),
                    lineWidth: isSelected ? 2 : 1))
        .highPriorityGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
            if !selectMode {
                withAnimation { selectMode = true; selectedIds.insert(item.id) }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        })
    }

    // MARK: - Tile

    @ViewBuilder
    private func itemTile(_ item: HBItem) -> some View {
        let isSelected = selectedIds.contains(item.id)
        ZStack(alignment: .topLeading) {
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
                    .font(.body)
                    .padding(6)
                    .background(Circle().fill(.ultraThinMaterial).frame(width: 24, height: 24))
                    .padding(6)
            }
        }
        .highPriorityGesture(LongPressGesture(minimumDuration: 0.4).onEnded { _ in
            if !selectMode {
                withAnimation { selectMode = true; selectedIds.insert(item.id) }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            }
        })
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
            Button("Clear filters") { filterLocationId = nil; filterTagIds = []; globalSearchQuery = "" }.buttonStyle(.glass)
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
        let q = globalSearchQuery.trimmingCharacters(in: .whitespaces).lowercased()
        var items = allItems
        if let locId = filterLocationId {
            items = items.filter { $0.location?.id == locId }
        }
        if !filterTagIds.isEmpty {
            items = items.filter { item in
                guard let labels = item.effectiveLabels else { return true }
                return !Set(labels.map { $0.id }).isDisjoint(with: filterTagIds)
            }
        }
        
        if !q.isEmpty {
            let textMatches = items.filter {
                $0.name.lowercased().contains(q) || ($0.description ?? "").lowercased().contains(q)
            }
            if textMatches.isEmpty, let semantic = semanticResults {
                return semantic
            }
            return textMatches
        }
        return items
    }

    private var isSortedAlphabetically: Bool {
        sortOption == .nameAZ || sortOption == .nameZA
    }

    private var sortedItems: [HBItem] {
        let items = filteredItems
        switch sortOption {
        case .nameAZ:
            return items.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        case .nameZA:
            return items.sorted { $0.name.localizedStandardCompare($1.name) == .orderedDescending }
        case .dateNewest:
            return items.sorted { a, b in
                guard let da = a.createdAt else { return false }
                guard let db = b.createdAt else { return true }
                return da > db
            }
        case .dateOldest:
            return items.sorted { a, b in
                guard let da = a.createdAt else { return false }
                guard let db = b.createdAt else { return true }
                return da < db
            }
        case .quantityHighToLow:
            return items.sorted { a, b in
                if a.quantityInt == b.quantityInt {
                    return a.name.localizedStandardCompare(b.name) == .orderedAscending
                }
                return a.quantityInt > b.quantityInt
            }
        case .quantityLowToHigh:
            return items.sorted { a, b in
                if a.quantityInt == b.quantityInt {
                    return a.name.localizedStandardCompare(b.name) == .orderedAscending
                }
                return a.quantityInt < b.quantityInt
            }
        }
    }

    private var itemSections: [ItemSection] {
        var groups: [String: [HBItem]] = [:]
        for item in filteredItems {
            let key: String
            if let c = item.name.first, c.isLetter { key = String(c).uppercased() } else { key = "#" }
            groups[key, default: []].append(item)
        }
        
        let isZA = sortOption == .nameZA
        
        let sortedKeys = groups.keys.sorted { a, b in
            if isZA {
                if a == "#" { return true }
                if b == "#" { return false }
                return a > b
            } else {
                if a == "#" { return false }
                if b == "#" { return true }
                return a < b
            }
        }
        
        return sortedKeys.map { key in
            let sortedSectionItems = groups[key]!.sorted { a, b in
                if isZA {
                    return a.name.localizedStandardCompare(b.name) == .orderedDescending
                } else {
                    return a.name.localizedStandardCompare(b.name) == .orderedAscending
                }
            }
            return ItemSection(letter: key, items: sortedSectionItems)
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

    private func archiveItem(_ item: HBItem) async {
        guard let client = store.client else { return }
        do {
            let detail = try await client.getItem(id: item.id)
            var update = HBItemUpdate(from: detail)
            update.archived = true
            try await client.updateItem(update)
            await MainActor.run {
                withAnimation { allItems.removeAll { $0.id == item.id } }
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil,
                                            userInfo: ["message": "Archive failed"])
        }
    }

    private func archiveSelected() async {
        guard let client = store.client else { return }
        let ids = Array(selectedIds)
        var archived: [String] = []
        var lastError: String? = nil
        for id in ids {
            do {
                let detail = try await client.getItem(id: id)
                var update = HBItemUpdate(from: detail)
                update.archived = true
                try await client.updateItem(update)
                archived.append(id)
            } catch {
                lastError = error.localizedDescription
            }
        }
        let archivedCount = archived.count
        let totalCount = ids.count
        let failureMsg = lastError
        await MainActor.run {
            withAnimation {
                allItems.removeAll { archived.contains($0.id) }
                selectedIds = []
                selectMode = false
            }
            if archivedCount == totalCount {
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "Archived \(archivedCount) item\(archivedCount == 1 ? "" : "s")"])
            } else if archivedCount > 0 {
                UINotificationFeedbackGenerator().notificationOccurred(.warning)
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "Archived \(archivedCount) of \(totalCount). Last error: \(failureMsg ?? "unknown")"])
            } else {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "Archive failed: \(failureMsg ?? "unknown")"])
            }
        }
    }

    private func lookupAsset(_ code: String) async {
        guard let client = store.client else { return }
        do {
            if let item = try await client.getAsset(assetId: code) {
                await MainActor.run { qrFoundItemId = item.id }
            } else {
                NotificationCenter.default.post(name: .showToast, object: nil,
                                                userInfo: ["message": "No item found for that QR code"])
            }
        } catch {
            NotificationCenter.default.post(name: .showToast, object: nil,
                                            userInfo: ["message": "Couldn't look up QR code"])
        }
    }

    private func updateSemanticSearch(for newQuery: String) {
        let q = newQuery.trimmingCharacters(in: .whitespaces).lowercased()
        if q.isEmpty {
            semanticSearchTask?.cancel()
            semanticResults = nil
            return
        }
        
        let textMatches = allItems.filter {
            $0.name.lowercased().contains(q) || ($0.description ?? "").lowercased().contains(q)
        }
        
        if !textMatches.isEmpty || q.count < 3 {
            semanticSearchTask?.cancel()
            semanticResults = nil
            return
        }
        
        semanticSearchTask?.cancel()
        var baseItems = allItems
        if let locId = filterLocationId { baseItems = baseItems.filter { $0.location?.id == locId } }
        if !filterTagIds.isEmpty {
            baseItems = baseItems.filter { item in
                guard let labels = item.effectiveLabels else { return true }
                return !Set(labels.map { $0.id }).isDisjoint(with: filterTagIds)
            }
        }
        
        semanticSearchTask = Task.detached(priority: .userInitiated) {
            guard let sentEmbedding = NLEmbedding.sentenceEmbedding(for: .english),
                  let wordEmbedding = NLEmbedding.wordEmbedding(for: .english) else { return }
            
            let tokenizer = NLTokenizer(unit: .word)
            tokenizer.string = q
            let queryWords = tokenizer.tokens(for: q.startIndex..<q.endIndex).map { String(q[$0]) }
            
            let results = baseItems.compactMap { item -> (HBItem, Double)? in
                if Task.isCancelled { return nil }
                let name = item.name.lowercased()
                let d1 = sentEmbedding.distance(between: q, and: name, distanceType: .cosine)
                
                tokenizer.string = name
                let targetWords = tokenizer.tokens(for: name.startIndex..<name.endIndex).map { String(name[$0]) }
                
                var minWordDist = 2.0
                for qw in queryWords {
                    for tw in targetWords {
                        let wd = wordEmbedding.distance(between: qw, and: tw, distanceType: .cosine)
                        if wd < minWordDist { minWordDist = wd }
                    }
                }
                
                let dist = min(d1, minWordDist)
                return dist < 1.15 ? (item, dist) : nil
            }
            .sorted { $0.1 < $1.1 }
            .map { $0.0 }
            
            if !Task.isCancelled {
                await MainActor.run { self.semanticResults = results }
            }
        }
    }
}

// MARK: - List row content (local @State for thumbnail — no shared observable updates)



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
            AuthImage(itemId: item.id, attachmentId: attId, allowsFullScreen: false).scaledToFill()
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

    let items: [HBItem]
    var onComplete: () -> Void = {}

    private var itemIds: [String] { items.map { $0.id } }

    @State private var applyLocation = false
    @State private var locationId: String?
    @State private var applyTags = false
    @State private var tagIds: Set<String> = []
    @State private var showLocationPicker = false
    @State private var showTagPicker = false
    @State private var isSaving = false
    @State private var progress = 0
    @State private var errorMsg: String?
    @State private var showDeleteAlert = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DisclosureGroup {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 8) {
                                ForEach(items) { item in
                                    HStack(spacing: 8) {
                                        Image(systemName: "shippingbox.fill")
                                            .font(.caption)
                                            .foregroundStyle(theme.current.accentColor)
                                        Text(item.name)
                                            .font(.callout)
                                            .foregroundColor(.primary)
                                            .lineLimit(1)
                                        Spacer()
                                        if item.quantityInt > 1 {
                                            Text("×\(item.quantityInt)")
                                                .font(.caption.monospacedDigit())
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    if item.id != items.last?.id {
                                        Divider()
                                    }
                                }
                            }
                            .padding(.vertical, 4)
                        }
                        .scrollIndicators(.hidden)
                        .frame(maxHeight: 120)
                    } label: {
                        HStack {
                            Text("Selected Items")
                            Spacer()
                            Text("\(items.count)")
                                .font(.callout.bold())
                                .foregroundStyle(theme.current.accentColor)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(theme.current.accentColor.opacity(0.1))
                                .clipShape(Capsule())
                        }
                    }
                }

                Section("Change location") {
                    if !applyLocation {
                        Button { applyLocation = true; showLocationPicker = true } label: {
                            Label("Set Location", systemImage: "mappin.and.ellipse").foregroundStyle(theme.current.accentColor)
                        }
                    } else {
                        Button { showLocationPicker = true } label: {
                            HStack {
                                Image(systemName: "mappin.and.ellipse")
                                Text(locationId.flatMap { store.pathString(forLocationId: $0) } ?? "No Location (Clear)")
                                    .foregroundStyle(.primary)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        Button("Cancel Location Change", role: .destructive) { applyLocation = false; locationId = nil }
                    }
                }

                Section("Change tags") {
                    if !applyTags {
                        Button { applyTags = true; showTagPicker = true } label: {
                            Label("Set Tags", systemImage: "tag").foregroundStyle(theme.current.accentColor)
                        }
                    } else {
                        Button { showTagPicker = true } label: {
                            HStack {
                                Image(systemName: "tag")
                                Text(tagIds.isEmpty ? "No Tags (Clear)" : "\(tagIds.count) tag\(tagIds.count == 1 ? "" : "s")")
                                    .foregroundStyle(.primary)
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }.contentShape(Rectangle())
                        }.buttonStyle(.plain)
                        Button("Cancel Tags Change", role: .destructive) { applyTags = false; tagIds = [] }
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showDeleteAlert = true
                    } label: {
                        HStack {
                            Spacer()
                            Text("Delete Selected Items")
                                .bold()
                            Spacer()
                        }
                    }
                }

                if isSaving {
                    Section { HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Updating \(progress) of \(items.count)…").font(.callout) } }
                }
                if let errorMsg {
                    Section { Label(errorMsg, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.callout) }
                }
            }
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("Bulk Edit")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { Task { await save() } }.bold()
                        .disabled(isSaving || (!applyLocation && !applyTags))
                }
            }
            .sheet(isPresented: $showLocationPicker) { LocationPickerSheet(selectedId: $locationId).environmentObject(store).environmentObject(theme) }
            .sheet(isPresented: $showTagPicker) { TagPickerSheet(selectedIds: $tagIds).environmentObject(store).environmentObject(theme) }
            .alert("Delete \(items.count) item\(items.count == 1 ? "" : "s")?", isPresented: $showDeleteAlert) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) { Task { await deleteItems() } }
            } message: {
                Text("Are you sure you want to permanently delete these items from Homebox?")
            }
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

    private func deleteItems() async {
        guard let client = store.client else { return }
        isSaving = true; errorMsg = nil; progress = 0
        for id in itemIds {
            do {
                try await client.deleteItem(id: id)
                progress += 1
            } catch {
                errorMsg = "Error deleting item \(progress + 1): \(error.localizedDescription)"
                isSaving = false; return
            }
        }
        isSaving = false
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onComplete(); dismiss()
    }
}
