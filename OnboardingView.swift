import SwiftUI

struct OnboardingView: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager

    @State private var password: String = ""
    @State private var isLoggingIn = false
    @State private var loginError: String?
    @FocusState private var focused: Field?

    enum Field { case server, username, password }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 16) {
                        Image(systemName: "shippingbox.fill")
                            .font(.system(size: 64, weight: .semibold))
                            .foregroundStyle(theme.current.accentColor)
                        
                        Text("Welcome to HomeBoy")
                            .font(.title2.weight(.bold))
                        
                        Text("Connect to your self-hosted Homebox instance to get started.")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 20)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                
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
            .scrollContentBackground(.hidden)
            .scrollIndicators(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
        }
    }

    private var canSubmit: Bool {
        store.serverURL != nil &&
        !store.savedUsername.trimmingCharacters(in: .whitespaces).isEmpty &&
        !password.isEmpty
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
