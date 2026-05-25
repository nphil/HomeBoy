import SwiftUI

/// Browse the Homebox location tree and create new locations (optionally under a parent).
struct LocationsTabView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.showSiteMenu) var showSiteMenu
    
    @Binding var globalSearchQuery: String

    @State private var showCreate = false
    @State private var collapsedIds: Set<String> = []
    @State private var didInitializeCollapse = false
    @AppStorage("locationsViewMode") private var viewMode: LocViewMode = .list
    @State private var indexLetter: String? = nil
    @State private var isSearchActive = false

    enum LocViewMode: String { case list, tile }

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
                if store.isAuthenticated {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button {
                            isSearchActive = true
                        } label: {
                            Image(systemName: "magnifyingglass")
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
            .sheet(isPresented: $showCreate) {
                CreateLocationSheet()
                    .environmentObject(store)
                    .environmentObject(theme)
            }
            .task {
                if store.locationsFlat.isEmpty && store.isAuthenticated {
                    try? await store.refreshLocations()
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                if store.isAuthenticated {
                    Task { try? await store.refreshLocations() }
                }
            }
            .onChange(of: store.locationsFlat) { _, flat in
                guard !didInitializeCollapse, !flat.isEmpty else { return }
                collapsedIds = Set(flat.compactMap { $0.parentId })
                didInitializeCollapse = true
            }
            .onAppear {
                if !didInitializeCollapse && !store.locationsFlat.isEmpty {
                    collapsedIds = Set(store.locationsFlat.compactMap { $0.parentId })
                    didInitializeCollapse = true
                }
            }
            .onChange(of: store.activeGroupId) { _, _ in
                // Collection switched — reset collapse state so the new tree
                // re-initialises cleanly (store.locationsFlat is refreshed by setActiveGroup).
                collapsedIds = []
                didInitializeCollapse = false
            }
            .modifier(ConditionalSearchable(text: $globalSearchQuery, isPresented: $isSearchActive, prompt: "Search locations…"))
            .navigationDestination(for: LocationDetailRoute.self) { route in
                LocationDetailView(locationId: route.id,
                                   onChange: { Task { try? await store.refreshLocations() } })
                    .environmentObject(store)
                    .environmentObject(theme)
            }
            .navigationDestination(for: ItemDetailRoute.self) { route in
                ItemDetailView(itemId: route.id)
                    .environmentObject(store)
                    .environmentObject(theme)
            }
        }
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if !store.isAuthenticated {
            notSignedIn
        } else if store.locationsFlat.isEmpty && store.isLoadingLocations {
            ProgressView("Loading locations…")
        } else if store.locationsFlat.isEmpty {
            emptyState
        } else {
            switch viewMode {
            case .list: listContent
            case .tile: tileContent
            }
        }
    }

    // MARK: - List view

    private var listContent: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 6) {
                    ForEach(visibleRows, id: \.id) { loc in
                        LocationListRow(
                            loc: loc,
                            isCollapsed: collapsedIds.contains(loc.id),
                            hasChildren: hasChildren(loc),
                            onToggleCollapse: { toggleCollapse(loc.id) }
                        )
                        .id(loc.id)
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.vertical, 8)
                .padding(.bottom, 60)
            }
            .coordinateSpace(name: "pullToSearch")
            .scrollDismissesKeyboard(.interactively)
            .scrollIndicators(.hidden)
            .refreshable { try? await store.refreshLocations() }
            .overlay(alignment: .trailing) {
                if !locationIndexLetters.isEmpty {
                    AlphabetIndexBar(letters: locationIndexLetters, currentLetter: $indexLetter) { letter in
                        if let loc = visibleRows.first(where: {
                            String($0.name.prefix(1)).uppercased() == letter
                        }) {
                            withAnimation { proxy.scrollTo(loc.id, anchor: .center) }
                        }
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

    private var tileContent: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 155, maximum: 195), spacing: 12, alignment: .top)],
                spacing: 12
            ) {
                ForEach(tileRows, id: \.id) { loc in
                    LocationTile(loc: loc, store: store)
                }
            }
            .padding(16)
            .padding(.bottom, 60)
        }
        .coordinateSpace(name: "pullToSearch")
        .scrollDismissesKeyboard(.interactively)
        .scrollIndicators(.hidden)
        .scrollContentBackground(.hidden)
        .background(theme.current.backgroundColor)
        .refreshable { try? await store.refreshLocations() }
    }

    // MARK: - Data helpers

    private var visibleRows: [FlatLocation] {
        let all = store.locationsFlat
        let q = globalSearchQuery.trimmingCharacters(in: .whitespaces).lowercased()
        if !q.isEmpty {
            return all.filter { $0.pathString.lowercased().contains(q) }
        }
        return all.filter { $0.isVisible(collapsedIds: collapsedIds) }
    }

    private var tileRows: [FlatLocation] {
        let all = store.locationsFlat
        let q = globalSearchQuery.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return all }
        return all.filter { $0.pathString.lowercased().contains(q) }
    }

    private var locationIndexLetters: [String] {
        var seen = Set<String>()
        var result: [String] = []
        for loc in visibleRows {
            let key = loc.name.first.map { $0.isLetter ? String($0).uppercased() : "#" } ?? "#"
            if seen.insert(key).inserted { result.append(key) }
        }
        return result.sorted { a, b in
            if a == "#" { return false }
            if b == "#" { return true }
            return a < b
        }
    }

    private func hasChildren(_ loc: FlatLocation) -> Bool {
        store.locationsFlat.contains { $0.parentId == loc.id }
    }

    private func childCount(_ loc: FlatLocation) -> Int {
        store.locationsFlat.filter { $0.parentId == loc.id }.count
    }

    private func toggleCollapse(_ id: String) {
        withAnimation(.easeInOut(duration: 0.2)) {
            if collapsedIds.contains(id) { collapsedIds.remove(id) }
            else { collapsedIds.insert(id) }
        }
    }

    // MARK: - Empty states

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
            Image(systemName: "tray").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("No locations yet").font(.title3.weight(.semibold))
            Text("Tap + to add your first location.")
                .font(.callout).foregroundStyle(.secondary)
        }
    }
}

// MARK: - List row

private struct LocationListRow: View {
    @EnvironmentObject var theme: ThemeManager
    let loc: FlatLocation
    let isCollapsed: Bool
    let hasChildren: Bool
    let onToggleCollapse: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            // Expand/collapse toggle — pinned to the far left so it's always
            // easy to find and tap, regardless of how deep the item is indented.
            if hasChildren {
                Button(action: onToggleCollapse) {
                    Image(systemName: isCollapsed ? "chevron.right" : "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(theme.current.accentColor.opacity(0.7))
                        .frame(width: 48)
                        .frame(maxHeight: .infinity)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                Spacer().frame(width: 48)
            }

            // Depth indentation — visual hierarchy lines come after the chevron
            ForEach(0..<loc.depth, id: \.self) { _ in
                Rectangle()
                    .fill(theme.current.accentColor.opacity(0.20))
                    .frame(width: 2)
                    .padding(.vertical, 4)
                    .padding(.trailing, 10)
            }

            // Navigate to detail
            NavigationLink(value: LocationDetailRoute(id: loc.id)) {
                HStack(spacing: 8) {
                    Image(systemName: loc.depth == 0 ? "house.fill" : "folder.fill")
                        .foregroundStyle(theme.current.accentColor.opacity(0.85))
                        .frame(width: 20)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(loc.name).font(.body.weight(.medium))
                        if !loc.ancestors.isEmpty {
                            Text(loc.ancestors.joined(separator: " / "))
                                .font(.caption2).foregroundStyle(.secondary).monospaced()
                        }
                    }

                    Spacer(minLength: 0)

                    // Item count badge
                    if loc.itemCount > 0 {
                        Text("\(loc.itemCount)")
                            .font(.caption2.monospacedDigit().weight(.semibold))
                            .padding(.horizontal, 7).padding(.vertical, 3)
                            .background(Capsule().fill(theme.current.accentColor.opacity(0.15)))
                            .foregroundStyle(theme.current.accentColor)
                    }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 10)
        .padding(.trailing, 10)
        .padding(.leading, 0)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(0.06))
        }
        .overlay(
            RoundedRectangle(cornerRadius: 12).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1)
        )
        .padding(.leading, CGFloat(loc.depth) * 8)
    }
}

private struct LocationTile: View {
    @EnvironmentObject var theme: ThemeManager
    let loc: FlatLocation
    let store: HomeboxStore
    
    @State private var isExpanded = false

    private var children: [FlatLocation] {
        store.locationsFlat.filter { $0.parentId == loc.id }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Button {
                if !children.isEmpty {
                    withAnimation(.spring(response: 0.35, dampingFraction: 0.75)) {
                        isExpanded.toggle()
                    }
                }
            } label: {
                mainCard
            }
            .buttonStyle(.plain)

            if isExpanded && !children.isEmpty {
                childrenList
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.06))
        }
        .overlay(
            RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1)
        )
    }
    
    private var mainCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                Image(systemName: loc.depth == 0 ? "house.fill" : "folder.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(theme.current.accentColor.opacity(0.85))
                Spacer()
                if loc.itemCount > 0 {
                    Text("\(loc.itemCount)")
                        .font(.caption.monospacedDigit().weight(.semibold))
                        .padding(.horizontal, 7).padding(.vertical, 3)
                        .background(Capsule().fill(theme.current.accentColor.opacity(0.15)))
                        .foregroundStyle(theme.current.accentColor)
                }
                NavigationLink(value: LocationDetailRoute(id: loc.id)) {
                    Image(systemName: "info.circle.fill")
                        .font(.title2)
                        .foregroundStyle(theme.current.accentColor)
                }
                .buttonStyle(.plain)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(loc.name)
                    .font(.callout.weight(.semibold))
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
                if !loc.ancestors.isEmpty {
                    Text(loc.ancestors.joined(separator: " › "))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .monospaced()
                }
                if !children.isEmpty {
                    HStack(spacing: 4) {
                        Text("\(children.count) sublocation\(children.count == 1 ? "" : "s")")
                        Image(systemName: isExpanded ? "chevron.up" : "chevron.down")
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                }
            }
        }
        .padding(12)
        .contentShape(Rectangle())
    }

    private var childrenList: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(children, id: \.id) { child in
                NavigationLink(value: LocationDetailRoute(id: child.id)) {
                    HStack(spacing: 8) {
                        Rectangle()
                            .fill(theme.current.accentColor.opacity(0.3))
                            .frame(width: 2)
                        Text(child.name)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Spacer()
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 12)
    }
}

// MARK: - Create location sheet

struct CreateLocationSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    @State private var name: String = ""
    @State private var description: String = ""
    @State private var parentId: String?
    @State private var showParentPicker = false
    @State private var isSubmitting = false
    @State private var errorMsg: String?
    @FocusState private var focused: Field?

    enum Field { case name, description }

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                VStack(alignment: .leading, spacing: 12) {
                    TextField("Location name", text: $name)
                        .font(.title3.weight(.semibold))
                        .focused($focused, equals: .name)
                        .submitLabel(.done)
                        .textInputAutocapitalization(.words)
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .background {
                            RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
                            RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(0.07))
                        }
                        .overlay(RoundedRectangle(cornerRadius: 12)
                            .stroke(theme.current.accentColor.opacity(name.isEmpty ? 0.35 : 0.2),
                                    lineWidth: name.isEmpty ? 1.5 : 1))

                    Button { showParentPicker = true } label: {
                        HStack(spacing: 10) {
                            Image(systemName: parentId == nil ? "house" : "folder.fill")
                                .foregroundStyle(theme.current.accentColor)
                            if let id = parentId {
                                Text(store.pathString(forLocationId: id))
                                    .font(.callout.weight(.medium))
                                    .foregroundStyle(.primary).lineLimit(1)
                            } else {
                                Text("Top level (no parent)").font(.callout).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary).font(.caption)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 10)
                        .frame(maxWidth: .infinity)
                        .background {
                            RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial)
                            RoundedRectangle(cornerRadius: 12).fill(theme.current.accentColor.opacity(0.05))
                        }
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

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

                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
            }
            .navigationTitle("New location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        submit()
                    } label: {
                        if isSubmitting { ProgressView().controlSize(.small) }
                        else { Text("Create").bold() }
                    }
                    .disabled(isSubmitting || name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .sheet(isPresented: $showParentPicker) {
                LocationPickerSheet(selectedId: $parentId)
                    .environmentObject(store)
                    .environmentObject(theme)
            }
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { focused = .name }
            }
        }
    }

    private func submit() {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let client = store.client else { return }
        errorMsg = nil
        isSubmitting = true
        Task {
            do {
                _ = try await client.createLocation(name: trimmed, parentId: parentId, description: description)
                try? await store.refreshLocations()
                await MainActor.run {
                    UINotificationFeedbackGenerator().notificationOccurred(.success)
                    dismiss()
                }
            } catch {
                await MainActor.run {
                    errorMsg = error.localizedDescription
                    UINotificationFeedbackGenerator().notificationOccurred(.error)
                }
            }
            await MainActor.run { isSubmitting = false }
        }
    }
}
