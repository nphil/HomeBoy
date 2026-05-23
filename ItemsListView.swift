import SwiftUI

struct ItemsListView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var items: [HBEntity] = []
    @State private var isLoading = false
    @State private var loadError: String?
    @State private var query: String = ""

    var body: some View {
        NavigationStack {
            ZStack {
                ThemeBackground().ignoresSafeArea()
                content
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
                if store.isAuthenticated {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text("\(items.count)")
                            .font(.callout.weight(.semibold).monospacedDigit())
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Capsule().fill(.ultraThinMaterial))
                    }
                }
            }
            .task { await load() }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !store.isAuthenticated {
            notSignedIn
        } else if isLoading && items.isEmpty {
            ProgressView("Loading items…")
        } else if let loadError, items.isEmpty {
            errorState(loadError)
        } else if items.isEmpty {
            emptyState
        } else {
            List {
                ForEach(filteredItems) { item in
                    ItemRow(item: item)
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .searchable(text: $query, prompt: "Search items")
            .refreshable { await load(force: true) }
        }
    }

    private var filteredItems: [HBEntity] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return items }
        return items.filter {
            $0.name.lowercased().contains(q) ||
            ($0.description ?? "").lowercased().contains(q)
        }
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
            Image(systemName: "shippingbox").font(.system(size: 48)).foregroundStyle(.secondary)
            Text("No items yet").font(.title3.weight(.semibold))
            Text("Add your first item from the Add tab.")
                .font(.callout).foregroundStyle(.secondary)
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

    private func load(force: Bool = false) async {
        guard let client = store.client else { return }
        if !force && !items.isEmpty { return }
        isLoading = true
        loadError = nil
        do {
            let resp = try await client.entities(pageSize: 1000)
            // Items are entities that are NOT locations. Newest first.
            let pulled = resp.items.filter { !$0.isLocation }
                .sorted { ($0.createdAt ?? "") > ($1.createdAt ?? "") }
            await MainActor.run { self.items = pulled }
        } catch {
            await MainActor.run { self.loadError = error.localizedDescription }
        }
        await MainActor.run { self.isLoading = false }
    }
}

private struct ItemRow: View {
    @EnvironmentObject var theme: ThemeManager
    let item: HBEntity

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().fill(theme.current.accentColor.opacity(0.20)).frame(width: 36, height: 36)
                Text("\(item.quantityInt)")
                    .font(.callout.weight(.semibold).monospacedDigit())
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name).font(.body.weight(.medium))
                if let parent = item.parent {
                    HStack(spacing: 4) {
                        Image(systemName: "mappin.and.ellipse").font(.caption2)
                        Text(parent.pathString).font(.caption).monospaced()
                    }
                    .foregroundStyle(.secondary)
                }
                if let d = item.description, !d.isEmpty {
                    Text(d).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(14)
        .background {
            RoundedRectangle(cornerRadius: 14).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 14).fill(theme.current.accentColor.opacity(0.06))
        }
        .overlay(
            RoundedRectangle(cornerRadius: 14).stroke(theme.current.accentColor.opacity(0.18), lineWidth: 1)
        )
    }
}
