import SwiftUI

/// Browse the Homebox location tree and create new locations (optionally under a parent).
struct LocationsTabView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var query: String = ""
    @State private var showCreate = false
    @State private var collapsedIds: Set<String> = []
    @State private var didInitializeCollapse = false
    @State private var viewMode: LocViewMode = .list

    enum LocViewMode { case list, tile }

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                content
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
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
            .onChange(of: store.locationsFlat) { _, flat in
                // On first load, collapse all parents so only top-level is visible.
                // Subsequent refreshes preserve the user's expand/collapse state.
                guard !didInitializeCollapse, !flat.isEmpty else { return }
                collapsedIds = Set(flat.compactMap { $0.parentId })
                didInitializeCollapse = true
            }
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

    // MARK: - Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .principal) { BrandMark() }
        if store.isAuthenticated {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        viewMode = viewMode == .list ? .tile : .list
                    }
                } label: {
                    Image(systemName: viewMode == .list ? "square.grid.2x2" : "list.bullet")
                }
                Button {
                    showCreate = true
                } label: {
                    Image(systemName: "plus.circle.fill").font(.title2)
                }
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
        List {
            ForEach(visibleRows, id: \.id) { loc in
                LocationListRow(
                    loc: loc,
                    isCollapsed: collapsedIds.contains(loc.id),
                    hasChildren: hasChildren(loc),
                    onToggleCollapse: { toggleCollapse(loc.id) }
                )
                .listRowBackground(Color.clear)
                .listRowSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .searchable(text: $query, prompt: "Search locations")
        .refreshable { try? await store.refreshLocations() }
    }

    // MARK: - Tile view

    private var tileContent: some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: 155, maximum: 195), spacing: 12)],
                spacing: 12
            ) {
                ForEach(tileRows, id: \.id) { loc in
                    NavigationLink(value: LocationDetailRoute(id: loc.id)) {
                        LocationTile(loc: loc, childCount: childCount(loc))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(16)
            .padding(.bottom, 60)
        }
        .scrollContentBackground(.hidden)
        .background(theme.current.backgroundColor)
        .searchable(text: $query, prompt: "Search locations")
        .refreshable { try? await store.refreshLocations() }
    }

    // MARK: - Data helpers

    private var visibleRows: [FlatLocation] {
        let all = store.locationsFlat
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        if !q.isEmpty {
            return all.filter { $0.pathString.lowercased().contains(q) }
        }
        return all.filter { $0.isVisible(collapsedIds: collapsedIds) }
    }

    private var tileRows: [FlatLocation] {
        let all = store.locationsFlat
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return all }
        return all.filter { $0.pathString.lowercased().contains(q) }
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
            // Depth indentation
            ForEach(0..<loc.depth, id: \.self) { _ in
                Rectangle()
                    .fill(theme.current.accentColor.opacity(0.20))
                    .frame(width: 2)
                    .padding(.vertical, 4)
                    .padding(.trailing, 10)
            }

            // Expand/collapse toggle
            if hasChildren {
                Button(action: onToggleCollapse) {
                    Image(systemName: isCollapsed ? "chevron.right" : "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(theme.current.accentColor.opacity(0.7))
                        .frame(width: 20, height: 20)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            } else {
                Spacer().frame(width: 20)
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
        .padding(.horizontal, 10)
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

// MARK: - Tile

private struct LocationTile: View {
    @EnvironmentObject var theme: ThemeManager
    let loc: FlatLocation
    let childCount: Int

    var body: some View {
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
                if childCount > 0 {
                    Text("\(childCount) sublocation\(childCount == 1 ? "" : "s")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.06))
        }
        .overlay(
            RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1)
        )
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
            Form {
                Section("Name") {
                    TextField("e.g. Garage Shelf 3", text: $name)
                        .focused($focused, equals: .name)
                        .submitLabel(.next)
                        .onSubmit { focused = .description }
                        .textInputAutocapitalization(.words)
                }

                Section("Parent location (optional)") {
                    Button {
                        showParentPicker = true
                    } label: {
                        HStack {
                            Image(systemName: parentId == nil ? "house" : "folder.fill")
                            if let id = parentId {
                                Text(store.pathString(forLocationId: id))
                                    .foregroundStyle(.primary)
                            } else {
                                Text("Top level (no parent)").foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                Section("Description (optional)") {
                    TextField("Notes about this location", text: $description, axis: .vertical)
                        .focused($focused, equals: .description)
                        .lineLimit(1...4)
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
            .onAppear { focused = .name }
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
