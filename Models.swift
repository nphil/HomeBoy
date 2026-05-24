import Foundation
import SwiftUI

// MARK: - FlatLocation

/// One node in the flattened (DFS-ordered) location tree, paired with its depth
/// and the parent chain — ready for an indented picker.
struct FlatLocation: Identifiable, Hashable {
    let id: String
    let name: String
    let depth: Int
    /// Ancestor names from root → immediate parent (excludes self).
    let ancestors: [String]
    /// Ancestor IDs from root → immediate parent (for collapse/expand logic).
    let ancestorIds: [String]
    let parentId: String?
    let itemCount: Int

    /// "Garage / Shelf A / Bin 2" for display.
    var pathString: String {
        (ancestors + [name]).joined(separator: " / ")
    }

    /// Returns true if none of this location's ancestors are collapsed.
    func isVisible(collapsedIds: Set<String>) -> Bool {
        !ancestorIds.contains(where: { collapsedIds.contains($0) })
    }
}

// MARK: - SavedAccount

/// One stored login — server URL + credentials + group name.
/// Each account has its own token in Keychain at "homebox.token.<id>".
/// Switching accounts swaps the active credentials so the API returns different data.
struct SavedAccount: Codable, Identifiable, Hashable {
    let id: String            // UUID, stable across launches
    let serverURLString: String
    var groupName: String     // updated after login via GET /v1/groups
    let username: String
}

// MARK: - HomeboxStore

/// Central state for Homebox connectivity + cached data.
@MainActor
final class HomeboxStore: ObservableObject {

    // MARK: Active credentials (persisted — drive the live API client)

    @Published var serverURLString: String {
        didSet { UserDefaults.standard.set(serverURLString, forKey: Keys.serverURL) }
    }
    @Published private(set) var token: String? {
        didSet {
            if let token { Keychain.set(token, key: Keys.token) }
            else         { Keychain.delete(Keys.token) }
        }
    }
    @Published var savedUsername: String {
        didSet { UserDefaults.standard.set(savedUsername, forKey: Keys.username) }
    }

    // MARK: Multi-account roster

    /// All saved accounts, shown in the SiteMenuPopover.
    @Published private(set) var savedAccounts: [SavedAccount] = []
    /// Which account is currently active.
    @Published private(set) var activeAccountId: String? = nil

    // MARK: In-memory caches

    @Published private(set) var locationsFlat: [FlatLocation] = []
    @Published var lastError: String?
    @Published private(set) var isLoadingLocations = false
    @Published private(set) var cachedItemTotal: Int? = nil
    @Published private(set) var groupName: String? = nil

    var isAuthenticated: Bool { token != nil && serverURL != nil }

    var serverURL: URL? {
        let trimmed = serverURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        return URL(string: withScheme)
    }

    var client: HomeboxClient? {
        guard let serverURL else { return nil }
        return HomeboxClient(serverURL: serverURL, token: token)
    }

    private enum Keys {
        static let serverURL      = "homebox.serverURL"
        static let username       = "homebox.username"
        static let token          = "homebox.token"        // active token (legacy key, kept for compatibility)
        static let savedAccounts  = "homebox.savedAccounts"
        static let activeAccountId = "homebox.activeAccountId"
    }

    // MARK: - Init + migration

    init() {
        // ── 1. Load active credentials (unchanged from legacy path) ──────────
        let savedURL = UserDefaults.standard.string(forKey: Keys.serverURL) ?? ""
        serverURLString = savedURL
        savedUsername   = UserDefaults.standard.string(forKey: Keys.username) ?? ""

        if savedURL.isEmpty {
            token = nil
            Keychain.delete(Keys.token)
        } else {
            token = Keychain.get(Keys.token)
        }

        // ── 2. Load saved accounts ──────────────────────────────────────────
        let activeId = UserDefaults.standard.string(forKey: Keys.activeAccountId)

        if let data = UserDefaults.standard.data(forKey: Keys.savedAccounts),
           let decoded = try? JSONDecoder().decode([SavedAccount].self, from: data) {
            savedAccounts  = decoded
            activeAccountId = activeId
        } else if !savedURL.isEmpty, let existingToken = Keychain.get(Keys.token) {
            // ── Migration: wrap the existing single-account credentials ──────
            let migrated = SavedAccount(
                id: UUID().uuidString,
                serverURLString: savedURL,
                groupName: "My Home",   // overwritten next time refreshGroup() runs
                username: savedUsername
            )
            Keychain.set(existingToken, key: "homebox.token.\(migrated.id)")
            savedAccounts  = [migrated]
            activeAccountId = migrated.id
            persistAccounts()
        }
    }

    // MARK: - Single-account login (OnboardingView / Settings re-login)

    func login(username: String, password: String) async throws {
        guard let url = serverURL else { throw HBError.badURL }
        let resp = try await HomeboxClient.login(serverURL: url, username: username, password: password)
        token        = resp.token
        savedUsername = username

        // Discover the group this token is scoped to
        try await refreshGroup()

        // Save / update account in the roster
        let name = groupName ?? "My Home"
        upsertAccount(serverURLString: serverURLString,
                      username: username,
                      token: resp.token,
                      groupName: name)

        try await refreshLocations()
        await refreshItemTotal()
    }

    // MARK: - Add a *second* account without disturbing the active session

    func addAccount(serverURLString rawURL: String, username: String, password: String) async throws {
        let trimmed = rawURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw HBError.badURL }
        let urlStr = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        guard let url = URL(string: urlStr) else { throw HBError.badURL }

        // Login on a temporary client — does NOT touch self.token or self.serverURLString
        let resp = try await HomeboxClient.login(serverURL: url, username: username, password: password)

        // Discover group name for this new token
        let tempClient = HomeboxClient(serverURL: url, token: resp.token)
        var newGroupName = "My Home"
        if let group = try? await tempClient.currentGroup() {
            newGroupName = group.name
        } else if let groups = try? await tempClient.listGroups(), let first = groups.first {
            newGroupName = first.name
        }

        upsertAccount(serverURLString: urlStr,
                      username: username,
                      token: resp.token,
                      groupName: newGroupName)
    }

    // MARK: - Switch active account (the real group-switching mechanism)

    func switchAccount(_ account: SavedAccount) async {
        guard let newToken = Keychain.get("homebox.token.\(account.id)") else { return }

        // Swap every credential that the API client depends on
        activeAccountId = account.id
        serverURLString = account.serverURLString   // didSet saves to UserDefaults
        savedUsername   = account.username           // didSet saves to UserDefaults
        token           = newToken                   // didSet saves to Keychain (Keys.token)
        groupName       = account.groupName

        persistAccounts()

        // Clear stale data from previous account
        locationsFlat   = []
        cachedItemTotal = nil

        // Reload with new credentials
        try? await refreshLocations()
        await refreshItemTotal()
        // Also refresh group name in case it changed
        try? await refreshGroup()
    }

    // MARK: - Remove a saved account

    func removeAccount(_ account: SavedAccount) {
        Keychain.delete("homebox.token.\(account.id)")
        savedAccounts.removeAll { $0.id == account.id }
        persistAccounts()

        if activeAccountId == account.id {
            if let next = savedAccounts.first {
                Task { await switchAccount(next) }
            } else {
                token           = nil
                activeAccountId = nil
                locationsFlat   = []
                cachedItemTotal = nil
                groupName       = nil
            }
        }
    }

    // MARK: - Logout (removes all accounts → returns to OnboardingView)

    func logout() {
        for acct in savedAccounts {
            Keychain.delete("homebox.token.\(acct.id)")
        }
        savedAccounts   = []
        activeAccountId = nil
        persistAccounts()

        token           = nil
        locationsFlat   = []
        cachedItemTotal = nil
        groupName       = nil
    }

    // MARK: - Data fetching

    /// Fetch the group name for the current token and sync it into the active account.
    func refreshGroup() async throws {
        guard let client else { throw HBError.notConfigured }

        // Try the single-group endpoint first (scoped to this token)
        let name: String?
        if let group = try? await client.currentGroup() {
            name = group.name
        } else {
            // Fallback: list all and take the first
            let all = (try? await client.listGroups()) ?? []
            name = all.first?.name
        }

        if let name {
            self.groupName = name
            // Keep the account roster in sync
            if let activeId = activeAccountId,
               let idx = savedAccounts.firstIndex(where: { $0.id == activeId }),
               savedAccounts[idx].groupName != name {
                savedAccounts[idx].groupName = name
                persistAccounts()
            }
        }
    }

    /// Fetch only the total item count (pageSize=1 is enough to get the `total` field).
    func refreshItemTotal() async {
        guard let client else { return }
        if let resp = try? await client.listItems(page: 1, pageSize: 1),
           let total = resp.total {
            cachedItemTotal = total
        }
    }

    func refreshLocations() async throws {
        guard let client else { throw HBError.notConfigured }
        isLoadingLocations = true
        defer { Task { @MainActor in self.isLoadingLocations = false } }

        async let treeTask = client.locationTree()
        async let listTask = client.listLocations()
        let (tree, list) = try await (treeTask, listTask)

        var countMap: [String: Int] = [:]
        for loc in list { countMap[loc.id] = Int(loc.itemCount ?? 0) }

        self.locationsFlat = Self.flatten(tree: tree, countMap: countMap)
    }

    func updateCachedItemTotal(_ total: Int) {
        cachedItemTotal = total
    }

    /// Breadcrumb path (e.g. "Garage / Shelf A") for a given location id.
    func pathString(forLocationId id: String?) -> String {
        guard let id, let loc = locationsFlat.first(where: { $0.id == id }) else { return "" }
        return loc.pathString
    }

    // MARK: - Private helpers

    /// Insert or update an account in the roster and mark it active.
    private func upsertAccount(serverURLString urlStr: String,
                               username: String,
                               token newToken: String,
                               groupName name: String) {
        if let idx = savedAccounts.firstIndex(where: {
            $0.serverURLString == urlStr && $0.username == username
        }) {
            savedAccounts[idx].groupName = name
            Keychain.set(newToken, key: "homebox.token.\(savedAccounts[idx].id)")
            activeAccountId = savedAccounts[idx].id
        } else {
            let acct = SavedAccount(id: UUID().uuidString,
                                    serverURLString: urlStr,
                                    groupName: name,
                                    username: username)
            Keychain.set(newToken, key: "homebox.token.\(acct.id)")
            savedAccounts.append(acct)
            activeAccountId = acct.id
        }
        persistAccounts()
    }

    private func persistAccounts() {
        if let data = try? JSONEncoder().encode(savedAccounts) {
            UserDefaults.standard.set(data, forKey: Keys.savedAccounts)
        }
        if let id = activeAccountId {
            UserDefaults.standard.set(id, forKey: Keys.activeAccountId)
        } else {
            UserDefaults.standard.removeObject(forKey: Keys.activeAccountId)
        }
    }

    /// DFS flatten the location tree into a depth-annotated list.
    private static func flatten(tree: [HBTreeItem], countMap: [String: Int] = [:]) -> [FlatLocation] {
        var out: [FlatLocation] = []
        func walk(_ node: HBTreeItem, depth: Int, ancestors: [String], ancestorIds: [String]) {
            if node.type == "location" || node.type == "" {
                out.append(FlatLocation(
                    id: node.id, name: node.name, depth: depth,
                    ancestors: ancestors, ancestorIds: ancestorIds,
                    parentId: ancestorIds.last,
                    itemCount: countMap[node.id] ?? 0
                ))
                let kids = (node.children ?? []).sorted { $0.name.lowercased() < $1.name.lowercased() }
                for k in kids {
                    walk(k, depth: depth + 1,
                         ancestors: ancestors + [node.name],
                         ancestorIds: ancestorIds + [node.id])
                }
            }
        }
        let top = tree.sorted { $0.name.lowercased() < $1.name.lowercased() }
        for n in top { walk(n, depth: 0, ancestors: [], ancestorIds: []) }
        return out
    }
}
