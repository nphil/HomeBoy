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
                if store.isAuthenticated {
                    signedInSection
                    serverSection
                } else {
                    serverSection
                    loginSection
                }

                Section("Theme") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 5), spacing: 14) {
                        ForEach(AppTheme.allCases) { t in
                            ThemeSwatch(theme: t, isSelected: theme.current == t, onTap: { theme.set(t) })
                        }
                    }
                    .padding(.vertical, 8)
                }

                Section("About") {
                    LabeledContent("Version", value: "0.3")
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
                    password = ""
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
                    Text("\(store.locations.count) locations cached")
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Button("Refresh") {
                    Task {
                        do { try await store.refreshLocations() }
                        catch { loginError = error.localizedDescription }
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

    private var serverSection: some View {
        Section("Server") {
            TextField("homebox.example.com", text: $store.serverURLString)
                .focused($focused, equals: .server)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .keyboardType(.URL)
                .submitLabel(.next)
                .onSubmit { focused = .username }
            Text("Just the host (https:// is added automatically) or a full URL. No trailing slash.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private var loginSection: some View {
        Section("Sign in") {
            TextField("Email or username", text: $store.savedUsername)
                .focused($focused, equals: .username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
                .submitLabel(.next)
                .onSubmit { focused = .password }
            SecureField("Password", text: $password)
                .focused($focused, equals: .password)
                .submitLabel(.go)
                .onSubmit { performLogin() }

            if let loginError {
                Label(loginError, systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            Button {
                performLogin()
            } label: {
                HStack {
                    if isLoggingIn { ProgressView().controlSize(.small) }
                    Text(isLoggingIn ? "Signing in…" : "Sign in")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.glassProminent)
            .disabled(isLoggingIn || !canSubmit)
        }
    }

    private var canSubmit: Bool {
        store.serverURL != nil &&
        !store.savedUsername.trimmingCharacters(in: .whitespaces).isEmpty &&
        !password.isEmpty
    }

    private var displayServer: String {
        store.serverURL?.host ?? store.serverURLString
    }

    private func performLogin() {
        loginError = nil
        isLoggingIn = true
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
}
