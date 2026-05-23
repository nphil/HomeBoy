import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject var store: CatalogStore
    @EnvironmentObject var theme: ThemeManager
    @State private var shareItem: ShareItem?
    @State private var confirmClear = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Export") {
                    Button {
                        exportCSV()
                    } label: {
                        Label("Export CSV (Homebox format)", systemImage: "square.and.arrow.up")
                    }
                    .disabled(store.items.isEmpty)
                    Text("Produces a CSV with HB.name, HB.quantity, HB.location, HB.description, HB.labels. Locations are joined with ' / '.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Queue") {
                    LabeledContent("Items in queue", value: "\(store.items.count)")
                    Button(role: .destructive) {
                        confirmClear = true
                    } label: {
                        Label("Clear all items", systemImage: "trash")
                    }
                    .disabled(store.items.isEmpty)
                }

                Section("Theme") {
                    HStack(spacing: 0) {
                        ForEach(AppTheme.allCases) { t in
                            ThemeSwatch(
                                theme: t,
                                isSelected: theme.current == t,
                                onTap: { theme.set(t) }
                            )
                            .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.vertical, 8)
                }

                Section("Homebox (coming soon)") {
                    LabeledContent("Server URL", value: "—")
                    LabeledContent("API token", value: "—")
                    Text("Direct push to your self-hosted Homebox will appear here in a future build.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("About") {
                    LabeledContent("Version", value: "0.1")
                    Link("Source on GitHub", destination: URL(string: "https://github.com/nphil/homebox-catalog-ios")!)
                }
            }
            .scrollContentBackground(.hidden)
            .background(ThemeBackground().ignoresSafeArea())
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
            }
            .sheet(item: $shareItem) { item in
                ShareSheet(activityItems: [item.url])
            }
            .alert("Clear all items?", isPresented: $confirmClear) {
                Button("Cancel", role: .cancel) {}
                Button("Clear", role: .destructive) { store.clearAll() }
            } message: {
                Text("This removes all \(store.items.count) items from the queue. Export first if you need them.")
            }
        }
    }

    private func exportCSV() {
        guard let url = CSVExporter.write(items: store.items) else { return }
        shareItem = ShareItem(url: url)
    }
}

struct ShareItem: Identifiable {
    let id = UUID()
    let url: URL
}

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
