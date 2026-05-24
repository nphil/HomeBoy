import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var password: String = ""
    @State private var isLoggingIn = false
    @State private var loginError: String?
    @State private var confirmLogout = false
    @FocusState private var focused: Field?

    enum Field { case server, username, password }

    var body: some View {
        NavigationStack {
            Form {
                signedInSection

                Section("Theme") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5), spacing: 14) {
                        ForEach(AppTheme.allCases) { t in
                            ThemeSwatch(theme: t, isSelected: theme.current == t, onTap: { theme.set(t) })
                        }
                    }
                    .padding(.vertical, 8)
                }

                Section("Info") {
                    if let total = store.cachedItemTotal {
                        LabeledContent("Items") {
                            Text("\(total)")
                                .font(.callout.monospacedDigit().weight(.medium))
                        }
                    } else {
                        LabeledContent("Items") {
                            Text("Open Items tab to load")
                                .font(.callout).foregroundStyle(.secondary)
                        }
                    }
                    LabeledContent("Locations") {
                        Text("\(store.locationsFlat.count)")
                            .font(.callout.monospacedDigit().weight(.medium))
                    }
                }

                Section("About") {
                    LabeledContent("Version", value: "0.6")
                    Link("Source on GitHub", destination: URL(string: "https://github.com/nphil/homebox-catalog-ios")!)
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { BrandMark() }
            }
            .alert("Sign out?", isPresented: $confirmLogout) {
                Button("Cancel", role: .cancel) {}
                Button("Sign out", role: .destructive) {
                    store.logout()
                }
            } message: {
                Text("You'll need to enter your password again to reconnect.")
            }
        }
    }

    // MARK: - Sections

    private var signedInSection: some View {
        Section("Signed in") {
            LabeledContent("Server") { Text(displayServer).font(.callout.monospaced()) }
            LabeledContent("User",   value: store.savedUsername)
            HStack {
                if store.isLoadingLocations {
                    ProgressView().controlSize(.small)
                    Text("Loading locations…").foregroundStyle(.secondary)
                } else {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    Text("\(store.locationsFlat.count) locations cached")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("Refresh") {
                    Task {
                        do { try await store.refreshLocations() }
                        catch { /* error handling */ }
                    }
                }
                .buttonStyle(.bordered)
            }
            Button(role: .destructive) {
                confirmLogout = true
            } label: {
                Label("Sign out", systemImage: "rectangle.portrait.and.arrow.right")
            }
        }
    }

    private var displayServer: String {
        store.serverURL?.host ?? store.serverURLString
    }
}
