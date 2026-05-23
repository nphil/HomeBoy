import SwiftUI

/// Browse the Homebox location tree and create new locations (optionally under a parent).
struct LocationsTabView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var query: String = ""
    @State private var showCreate = false
    @State private var loadError: String?

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                content
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
                if store.isAuthenticated {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showCreate = true
                        } label: {
                            Image(systemName: "plus.circle.fill")
                                .font(.title2)
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
            .navigationDestination(for: LocationDetailRoute.self) { route in
                LocationDetailView(locationId: route.id, onChange: { Task { try? await store.refreshLocations() } })
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

    @ViewBuilder
    private var content: some View {
        if !store.isAuthenticated {
            notSignedIn
        } else if store.locationsFlat.isEmpty && store.isLoadingLocations {
            ProgressView("Loading locations…")
        } else if store.locationsFlat.isEmpty {
            emptyState
        } else {
            List {
                ForEach(filteredRows, id: \.id) { loc in
                    NavigationLink(value: LocationDetailRoute(id: loc.id)) {
                        LocationRow(loc: loc)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .searchable(text: $query, prompt: "Search locations")
            .refreshable {
                try? await store.refreshLocations()
            }
        }
    }

    private var filteredRows: [FlatLocation] {
        let all = store.locationsFlat
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return all }
        return all.filter { $0.pathString.lowercased().contains(q) }
    }

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

private struct LocationRow: View {
    @EnvironmentObject var theme: ThemeManager
    let loc: FlatLocation

    var body: some View {
        HStack(spacing: 10) {
            ForEach(0..<loc.depth, id: \.self) { _ in
                Rectangle()
                    .fill(theme.current.accentColor.opacity(0.25))
                    .frame(width: 2)
                    .padding(.vertical, 2)
            }
            Image(systemName: loc.depth == 0 ? "house.fill" : "folder.fill")
                .foregroundStyle(theme.current.accentColor.opacity(0.85))
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(loc.name).font(.body.weight(.medium))
                if !loc.ancestors.isEmpty {
                    Text(loc.ancestors.joined(separator: " / "))
                        .font(.caption2).foregroundStyle(.secondary).monospaced()
                }
            }
            Spacer(minLength: 0)
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

/// Sheet to create a new location, with optional parent selection.
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
