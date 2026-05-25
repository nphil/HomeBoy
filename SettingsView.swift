import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var password: String = ""
    @State private var isLoggingIn = false
    @State private var loginError: String?
    @State private var confirmLogout = false
    @FocusState private var focused: Field?
    @AppStorage("showQRScannerFAB") private var showQRScannerFAB = true

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

                Section("Scanner") {
                    Toggle(isOn: $showQRScannerFAB) {
                        Label("Show QR scanner button", systemImage: "qrcode.viewfinder")
                    }
                    .tint(theme.current.accentColor)
                    Text("When on, a QR button appears on the Items tab. Scan a Homebox asset QR label to jump straight to that item.")
                        .font(.caption).foregroundStyle(.secondary)
                }

                Section("Library") {
                    NavigationLink {
                        ArchivedItemsView()
                            .environmentObject(store)
                            .environmentObject(theme)
                    } label: {
                        Label("Archived Items", systemImage: "archivebox")
                    }
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
                    LabeledContent("Groups") {
                        Text("\(store.groups.count)")
                            .font(.callout.monospacedDigit().weight(.medium))
                    }
                }

                Section("About") {
                    LabeledContent("Version", value: appVersionString)
                    Link("Source on GitHub", destination: URL(string: "https://github.com/nphil/HomeBoy")!)
                }
            }
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("Settings")
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
            if let name = store.groupName {
                LabeledContent("Active group", value: name)
            }
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
                        try? await store.refreshGroups()
                        try? await store.refreshLocations()
                        await store.refreshItemTotal()
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

    private var appVersionString: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.0.10"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "11"
        return "\(version) (\(build))"
    }
}
