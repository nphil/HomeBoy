import SwiftUI

/// Detail view for a single location: parent, children, description; edit + delete.
struct LocationDetailView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let locationId: String
    var onChange: () -> Void = {}

    @State private var detail: HBLocationDetail?
    @State private var items: [HBItem] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var showEdit = false
    @State private var confirmDelete = false

    var body: some View {
        ZStack {
            theme.current.backgroundColor.ignoresSafeArea()
            content
        }
        .navigationTitle(detail?.name ?? "Location")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { showEdit = true } label: { Label("Edit", systemImage: "pencil") }
                        .disabled(detail == nil)
                    Button(role: .destructive) { confirmDelete = true } label: { Label("Delete", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle").font(.title3)
                }
            }
        }
        .task { await load() }
        .sheet(isPresented: $showEdit) {
            if let detail {
                EditLocationSheet(original: detail) { updated in
                    self.detail = updated
                    Task { try? await store.refreshLocations() }
                    onChange()
                }
                .environmentObject(store)
                .environmentObject(theme)
            }
        }
        .alert("Delete location?", isPresented: $confirmDelete) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) { Task { await performDelete() } }
        } message: {
            Text("Items inside this location will be left where they are; you cannot delete a location with items in it.")
        }
    }

    @ViewBuilder
    private var content: some View {
        if isLoading && detail == nil {
            ProgressView("Loading…")
        } else if let detail {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    headerCard(detail)
                    if !(detail.children ?? []).isEmpty { childrenCard(detail) }
                    itemsCard
                }
                .padding(16)
                .padding(.bottom, 60)
            }
            .scrollIndicators(.hidden)
        } else if let loadError {
            VStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle").font(.system(size: 32)).foregroundStyle(.orange)
                Text("Couldn't load").font(.title3.weight(.semibold))
                Text(loadError).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.horizontal, 24)
                Button("Try again") { Task { await load() } }.buttonStyle(.glass)
            }
        }
    }

    private func headerCard(_ d: HBLocationDetail) -> some View {
        GlassCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(d.name).font(.title2.weight(.semibold))
                if let parent = d.parent {
                    HStack(spacing: 4) {
                        Image(systemName: "arrow.turn.up.left").font(.caption2)
                        Text("Inside ").font(.caption).foregroundStyle(.secondary)
                        Text(parent.name).font(.caption.weight(.medium))
                    }
                }
                if let desc = d.description, !desc.isEmpty {
                    Text(desc).font(.body).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
                }
                if let p = d.totalPrice, p > 0 {
                    HStack(spacing: 4) {
                        Image(systemName: "tag.fill").font(.caption)
                        Text("Total value: \(String(format: "%.2f", p))").font(.caption)
                    }
                    .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func childrenCard(_ d: HBLocationDetail) -> some View {
        GlassCard(title: "Sublocations") {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(d.children ?? []) { child in
                    NavigationLink(value: LocationDetailRoute(id: child.id)) {
                        HStack {
                            Image(systemName: "folder.fill").foregroundStyle(theme.current.accentColor.opacity(0.85))
                            Text(child.name)
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary).font(.caption)
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .padding(.vertical, 4)
                }
            }
        }
    }

    private var itemsCard: some View {
        GlassCard(title: items.isEmpty ? "Items" : "Items (\(items.count))") {
            if items.isEmpty {
                Text("No items in this location").font(.callout).foregroundStyle(.secondary)
            } else {
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(items.prefix(50)) { item in
                        NavigationLink(value: ItemDetailRoute(id: item.id)) {
                            HStack {
                                Image(systemName: "shippingbox.fill").foregroundStyle(theme.current.accentColor.opacity(0.85))
                                Text(item.name)
                                Spacer()
                                Text("×\(item.quantityInt)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary).font(.caption)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .padding(.vertical, 4)
                    }
                    if items.count > 50 {
                        Text("…and \(items.count - 50) more").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func load() async {
        guard let client = store.client else { return }
        isLoading = true; loadError = nil
        do {
            async let d = client.getLocation(id: locationId)
            async let i = client.listItems(locationIds: [locationId], pageSize: 500)
            let (det, list) = try await (d, i)
            await MainActor.run {
                self.detail = det
                self.items = list.items.sorted { ($0.createdAt ?? "") > ($1.createdAt ?? "") }
            }
        } catch {
            loadError = error.localizedDescription
        }
        isLoading = false
    }

    private func performDelete() async {
        guard let client = store.client else { return }
        do {
            try await client.deleteLocation(id: locationId)
            try? await store.refreshLocations()
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onChange()
            dismiss()
        } catch {
            loadError = error.localizedDescription
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
    }
}

// MARK: - Edit sheet

struct EditLocationSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let original: HBLocationDetail
    var onSaved: (HBLocationDetail) -> Void = { _ in }

    @State private var name: String
    @State private var description: String
    @State private var parentId: String?
    @State private var showParentPicker = false
    @State private var isSaving = false
    @State private var errorMsg: String?

    init(original: HBLocationDetail, onSaved: @escaping (HBLocationDetail) -> Void = { _ in }) {
        self.original = original
        self.onSaved = onSaved
        _name = State(initialValue: original.name)
        _description = State(initialValue: original.description ?? "")
        _parentId = State(initialValue: original.parent?.id)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Name", text: $name).textInputAutocapitalization(.words)
                }
                Section("Parent") {
                    Button { showParentPicker = true } label: {
                        HStack {
                            Image(systemName: parentId == nil ? "house" : "folder.fill")
                            if let id = parentId {
                                Text(store.pathString(forLocationId: id)).foregroundStyle(.primary)
                            } else {
                                Text("Top level (no parent)").foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                Section("Description") {
                    TextField("Notes about this location", text: $description, axis: .vertical).lineLimit(1...4)
                }
                if let errorMsg {
                    Section { Label(errorMsg, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.red).font(.callout) }
                }
            }
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("Edit location")
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
                    .disabled(isSaving || name.trimmingCharacters(in: .whitespaces).isEmpty || parentId == original.id)
                }
            }
            .sheet(isPresented: $showParentPicker) {
                LocationPickerSheet(selectedId: $parentId).environmentObject(store).environmentObject(theme)
            }
        }
    }

    private func save() async {
        guard let client = store.client else { return }
        isSaving = true; errorMsg = nil
        let payload = HBLocationUpdate(
            id: original.id,
            name: name.trimmingCharacters(in: .whitespacesAndNewlines),
            description: description,
            parentId: parentId
        )
        do {
            try await client.updateLocation(payload)
            try? await store.refreshLocations()
            let fresh = try await client.getLocation(id: original.id)
            await MainActor.run {
                onSaved(fresh)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                dismiss()
            }
        } catch {
            await MainActor.run {
                errorMsg = error.localizedDescription
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            }
        }
        isSaving = false
    }
}

// MARK: - Nav routes

/// Used by NavigationStack(value:) so we can push detail views from anywhere in the tree.
struct ItemDetailRoute: Hashable { let id: String }
struct LocationDetailRoute: Hashable { let id: String }
