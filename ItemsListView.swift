import SwiftUI

struct ItemsListView: View {
    @EnvironmentObject var store: CatalogStore
    @EnvironmentObject var theme: ThemeManager
    @State private var editingItem: CatalogItem?

    var body: some View {
        NavigationStack {
            ZStack {
                ThemeBackground()
                Group {
                    if store.items.isEmpty {
                        emptyState
                    } else {
                        List {
                            ForEach(store.items.reversed()) { item in
                                ItemRow(item: item)
                                    .listRowBackground(Color.clear)
                                    .listRowSeparator(.hidden)
                                    .contentShape(Rectangle())
                                    .onTapGesture { editingItem = item }
                            }
                            .onDelete { offsets in
                                // List shows reversed order — translate offsets.
                                let count = store.items.count
                                let realOffsets = IndexSet(offsets.map { count - 1 - $0 })
                                store.delete(at: realOffsets)
                            }
                        }
                        .listStyle(.plain)
                        .scrollContentBackground(.hidden)
                    }
                }
            }
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
                if !store.items.isEmpty {
                    ToolbarItem(placement: .topBarTrailing) {
                        Text("\(store.items.count)")
                            .font(.callout.weight(.semibold).monospacedDigit())
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Capsule().fill(.ultraThinMaterial))
                    }
                }
            }
            .sheet(item: $editingItem) { item in
                EditItemSheet(item: item)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "shippingbox")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("No items in the queue")
                .font(.title3.weight(.semibold))
            Text("Switch to the Add tab to catalogue your first item.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }
}

private struct ItemRow: View {
    @EnvironmentObject var theme: ThemeManager
    let item: CatalogItem

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(theme.current.accentColor.opacity(0.20))
                    .frame(width: 36, height: 36)
                Text("\(item.quantity)")
                    .font(.callout.weight(.semibold).monospacedDigit())
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(item.name)
                    .font(.body.weight(.medium))
                if !item.locationPath.isEmpty {
                    HStack(spacing: 4) {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.caption2)
                        Text(item.locationPath)
                            .font(.caption)
                            .monospaced()
                    }
                    .foregroundStyle(.secondary)
                }
                if !item.labels.isEmpty {
                    Text(item.labels)
                        .font(.caption2)
                        .foregroundStyle(theme.current.accentColor.opacity(0.85))
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(.tertiary)
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

private struct EditItemSheet: View {
    @EnvironmentObject var store: CatalogStore
    @Environment(\.dismiss) var dismiss
    @State var item: CatalogItem

    var body: some View {
        NavigationStack {
            Form {
                Section("Item") {
                    TextField("Name", text: $item.name)
                    Stepper("Quantity: \(item.quantity)", value: $item.quantity, in: 1...9999)
                }
                Section("Location") {
                    TextField("Location", text: $item.location1)
                    TextField("Sublocation", text: $item.location2)
                    TextField("Sub-sublocation", text: $item.location3)
                }
                Section("Optional") {
                    TextField("Description", text: $item.details, axis: .vertical).lineLimit(1...4)
                    TextField("Labels (semicolon-separated)", text: $item.labels)
                        .autocorrectionDisabled(true)
                        .textInputAutocapitalization(.never)
                }
                Section {
                    Button(role: .destructive) {
                        store.delete(item)
                        dismiss()
                    } label: {
                        Label("Delete item", systemImage: "trash")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(ThemeBackground().ignoresSafeArea())
            .navigationTitle("Edit item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        store.update(item)
                        dismiss()
                    }
                    .disabled(item.name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
