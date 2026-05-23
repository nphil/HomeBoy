import SwiftUI

/// A sheet that presents the Homebox location tree as an indented searchable
/// list. Tap a row to pick that location.
struct LocationPickerSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    @Binding var selectedId: String?
    @State private var query: String = ""

    var body: some View {
        NavigationStack {
            ZStack {
                theme.current.backgroundColor.ignoresSafeArea()
                content
            }
            .navigationTitle("Choose location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if selectedId != nil {
                        Button("Clear", role: .destructive) {
                            selectedId = nil
                            dismiss()
                        }
                    }
                }
            }
        }
        .task {
            if store.locationsFlat.isEmpty {
                try? await store.refreshLocations()
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if store.locationsFlat.isEmpty && store.isLoadingLocations {
            ProgressView("Loading locations…")
        } else if store.locationsFlat.isEmpty {
            VStack(spacing: 10) {
                Image(systemName: "tray").font(.system(size: 36)).foregroundStyle(.secondary)
                Text("No locations found")
                    .font(.headline)
                Text("Create some locations in your Homebox first, then pull to refresh.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
            }
        } else {
            List {
                ForEach(filteredRows, id: \.id) { loc in
                    Button {
                        selectedId = loc.id
                        dismiss()
                    } label: {
                        rowView(loc)
                    }
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .buttonStyle(.plain)
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

    private func rowView(_ loc: FlatLocation) -> some View {
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
                Text(loc.name)
                    .font(.body.weight(.medium))
                if !loc.ancestors.isEmpty {
                    Text(loc.ancestors.joined(separator: " / "))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .monospaced()
                }
            }
            Spacer(minLength: 0)
            if selectedId == loc.id {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
            }
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 12).fill(.ultraThinMaterial.opacity(0.6))
        }
        .padding(.leading, CGFloat(loc.depth) * 8)
    }
}
