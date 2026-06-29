import SwiftUI
import UIKit

struct SettingsView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @EnvironmentObject var ai: AIModelManager

    @State private var password: String = ""
    @State private var isLoggingIn = false
    @State private var loginError: String?
    @State private var confirmLogout = false
    @State private var shareItems: [Any] = []
    @State private var showShareSheet = false
    @State private var imageCacheMB: Int = 0
    @FocusState private var focused: Field?
    @AppStorage("showQRScannerFAB") private var showQRScannerFAB = true

    enum Field { case server, username, password }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Spacer()
                        VStack(spacing: 10) {
                            Image(systemName: "shippingbox.fill")
                                .font(.system(size: 48, weight: .semibold))
                                .foregroundStyle(theme.current.accentColor)
                            Text("HomeBoy")
                                .font(.title.weight(.bold))
                            Text("Homebox iOS Client")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .padding(.vertical, 16)
                }
                .listRowBackground(Color.clear)

                if store.token != nil {
                    signedInSection
                } else {
                    offlineSignInSection
                }

                Section("Network") {
                    Toggle(isOn: $store.isOfflineModeEnabled) {
                        Label("Offline Mode", systemImage: "wifi.slash")
                    }
                    .tint(theme.current.accentColor)
                    Text("When on, the app reads and writes to the local database only. Turn off to sync with the server when connected.")
                        .font(.caption).foregroundStyle(.secondary)

                    if !store.isConnectedToNetwork {
                        HStack(spacing: 6) {
                            Image(systemName: "wifi.exclamationmark").foregroundStyle(.orange)
                            Text("No network connection").foregroundStyle(.secondary)
                        }
                        .font(.callout)
                    }

                    if store.pendingOpsCount > 0 {
                        HStack {
                            Image(systemName: "clock.arrow.circlepath").foregroundStyle(.orange)
                            Text("\(store.pendingOpsCount) item(s) pending sync")
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("Sync now") { Task { await store.syncPendingOps() } }
                                .buttonStyle(.bordered)
                        }
                    }
                }

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

                Section("Intelligence") {
                    NavigationLink {
                        AIManagementView()
                            .environmentObject(store)
                            .environmentObject(theme)
                            .environmentObject(ai)
                    } label: {
                        Label("AI & Models", systemImage: "sparkles")
                    }
                    Text("On-device semantic search and AI tag suggestions. Runs privately on your iPhone.")
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

                Section("Data") {
                    Button {
                        let csv = store.localDB.exportCSV()
                        let url = FileManager.default.temporaryDirectory
                            .appendingPathComponent("homebox_items.csv")
                        if (try? csv.write(to: url, atomically: true, encoding: .utf8)) != nil {
                            shareItems = [url]
                            showShareSheet = true
                        }
                    } label: {
                        Label("Export to CSV", systemImage: "square.and.arrow.up")
                    }
                    Text("Exports all locally cached items in Homebox import format. Open Items tab while connected to refresh the cache first.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button(role: .destructive) {
                        ImageCache.shared.clear()
                        imageCacheMB = 0
                    } label: {
                        HStack {
                            Label("Clear image cache", systemImage: "photo.stack")
                            Spacer()
                            if imageCacheMB > 0 {
                                Text("\(imageCacheMB) MB")
                                    .font(.callout).foregroundStyle(.secondary)
                            }
                        }
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
            .onAppear {
                imageCacheMB = ImageCache.shared.diskSizeBytes / (1024 * 1024)
            }
            .sheet(isPresented: $showShareSheet) {
                ActivityView(items: shareItems).presentationDetents([.medium, .large])
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

    private var offlineSignInSection: some View {
        Section("Connect to server") {
            TextField("homebox.example.com", text: $store.serverURLString)
                .focused($focused, equals: .server)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .keyboardType(.URL)
            TextField("Email or username", text: $store.savedUsername)
                .focused($focused, equals: .username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
            SecureField("Password", text: $password)
                .focused($focused, equals: .password)
                .onSubmit { performLogin() }

            if let loginError {
                Label(loginError, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption).foregroundStyle(.red)
            }

            Button {
                performLogin()
            } label: {
                HStack {
                    if isLoggingIn { ProgressView().controlSize(.small) }
                    Text(isLoggingIn ? "Signing in…" : "Sign in to server")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glassProminent)
            .disabled(isLoggingIn || !canSignIn)
        }
    }

    private var canSignIn: Bool {
        store.serverURL != nil &&
        !store.savedUsername.trimmingCharacters(in: .whitespaces).isEmpty &&
        !password.isEmpty
    }

    private func performLogin() {
        guard canSignIn else { return }
        loginError = nil; isLoggingIn = true
        Task {
            do {
                try await store.login(username: store.savedUsername, password: password)
                password = ""
            } catch {
                loginError = error.localizedDescription
            }
            isLoggingIn = false
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

private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uvc: UIActivityViewController, context: Context) {}
}
