import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var password: String = ""
    @State private var isLoggingIn = false
    @State private var loginError: String?
    @State private var confirmLogout = false
    @State private var showAddGroup = false

    @FocusState private var focused: Field?
    enum Field { case server, username, password }

    var body: some View {
        NavigationStack {
            Form {
                accountsSection
                addGroupButton

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
            .navigationTitle("Settings")
            .alert("Sign out of all groups?", isPresented: $confirmLogout) {
                Button("Cancel", role: .cancel) {}
                Button("Sign out", role: .destructive) { store.logout() }
            } message: {
                Text("All saved groups will be removed. You'll need to sign in again.")
            }
            .sheet(isPresented: $showAddGroup) {
                AddGroupSheet()
                    .environmentObject(store)
                    .environmentObject(theme)
            }
        }
    }

    // MARK: - Accounts section

    private var accountsSection: some View {
        Section("Groups") {
            ForEach(store.savedAccounts) { account in
                HStack(spacing: 10) {
                    // Active indicator
                    Image(systemName: account.id == store.activeAccountId
                          ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(account.id == store.activeAccountId
                                         ? theme.current.accentColor : .secondary)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(account.groupName)
                            .font(.body.weight(account.id == store.activeAccountId ? .semibold : .regular))
                        Text(account.username)
                            .font(.caption).foregroundStyle(.secondary)
                    }

                    Spacer()

                    // Remove button (only when more than one account saved)
                    if store.savedAccounts.count > 1 {
                        Button {
                            store.removeAccount(account)
                        } label: {
                            Image(systemName: "minus.circle.fill")
                                .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Button(role: .destructive) {
                confirmLogout = true
            } label: {
                Label("Sign out of all groups", systemImage: "rectangle.portrait.and.arrow.right")
            }
        }
    }

    // MARK: - Add group button

    private var addGroupButton: some View {
        Section {
            Button {
                showAddGroup = true
            } label: {
                Label("Add Another Group", systemImage: "plus.circle")
                    .foregroundStyle(theme.current.accentColor)
            }
        } footer: {
            Text("Sign in to a different Homebox group or server. You can then switch between them from the HomeBoy menu.")
        }
    }
}

// MARK: - Add Group Sheet

struct AddGroupSheet: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    @State private var serverURL = ""
    @State private var username  = ""
    @State private var password  = ""
    @State private var isSaving  = false
    @State private var errorMsg: String?

    @FocusState private var focused: Field?
    enum Field { case server, username, password }

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("https://homebox.example.com", text: $serverURL)
                        .keyboardType(.URL)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .focused($focused, equals: .server)
                }
                Section("Credentials") {
                    TextField("Username / email", text: $username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .focused($focused, equals: .username)
                    SecureField("Password", text: $password)
                        .focused($focused, equals: .password)
                }
                if let errorMsg {
                    Section {
                        Label(errorMsg, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.red)
                            .font(.callout)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle("Add Group")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task { await save() }
                    } label: {
                        if isSaving { ProgressView().controlSize(.small) }
                        else        { Text("Sign In").bold() }
                    }
                    .disabled(isSaving || serverURL.isEmpty || username.isEmpty || password.isEmpty)
                }
            }
            .onAppear { focused = .server }
        }
    }

    private func save() async {
        isSaving = true
        errorMsg = nil
        do {
            try await store.addAccount(serverURLString: serverURL,
                                       username: username,
                                       password: password)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            dismiss()
        } catch {
            errorMsg = error.localizedDescription
            UINotificationFeedbackGenerator().notificationOccurred(.error)
        }
        isSaving = false
    }
}
