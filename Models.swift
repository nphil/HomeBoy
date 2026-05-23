import Foundation
import SwiftUI

/// One node in the flattened (DFS-ordered) location tree, paired with its depth
/// and the parent chain — ready for an indented picker.
struct FlatLocation: Identifiable, Hashable {
    let id: String
    let name: String
    let depth: Int
    /// Ancestor names from root → immediate parent (excludes self).
    let ancestors: [String]

    /// "Garage / Shelf A / Bin 2" for display.
    var pathString: String {
        (ancestors + [name]).joined(separator: " / ")
    }
}

/// State container for Homebox connectivity + cached data.
@MainActor
final class HomeboxStore: ObservableObject {
    // MARK: - Auth + config (persisted)

    @Published var serverURLString: String {
        didSet { UserDefaults.standard.set(serverURLString, forKey: Keys.serverURL) }
    }
    @Published private(set) var token: String? {
        didSet {
            if let token { Keychain.set(token, key: Keys.token) }
            else { Keychain.delete(Keys.token) }
        }
    }
    @Published var savedUsername: String {
        didSet { UserDefaults.standard.set(savedUsername, forKey: Keys.username) }
    }

    // MARK: - In-memory caches

    @Published private(set) var locationsFlat: [FlatLocation] = []
    @Published private(set) var isLoadingLocations = false
    @Published var lastError: String?

    var isAuthenticated: Bool { token != nil && serverURL != nil }

    var serverURL: URL? {
        let trimmed = serverURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        // Auto-prefix scheme if user typed just a host.
        let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        return URL(string: withScheme)
    }

    var client: HomeboxClient? {
        guard let serverURL else { return nil }
        return HomeboxClient(serverURL: serverURL, token: token)
    }

    private enum Keys {
        static let serverURL = "homebox.serverURL"
        static let username  = "homebox.username"
        static let token     = "homebox.token"
    }

    init() {
        serverURLString = UserDefaults.standard.string(forKey: Keys.serverURL) ?? ""
        savedUsername   = UserDefaults.standard.string(forKey: Keys.username) ?? ""
        token           = Keychain.get(Keys.token)
    }

    // MARK: - Auth

    func login(username: String, password: String) async throws {
        guard let url = serverURL else { throw HBError.badURL }
        let resp = try await HomeboxClient.login(serverURL: url, username: username, password: password)
        token = resp.token
        savedUsername = username
        try await refreshLocations()
    }

    func logout() {
        token = nil
        locationsFlat = []
    }

    // MARK: - Locations

    func refreshLocations() async throws {
        guard let client else { throw HBError.notConfigured }
        await MainActor.run { self.isLoadingLocations = true }
        defer { Task { @MainActor in self.isLoadingLocations = false } }

        let tree = try await client.locationTree()
        let flat = Self.flatten(tree: tree)
        await MainActor.run { self.locationsFlat = flat }
    }

    /// Returns the breadcrumb path (e.g. "Garage / Shelf A") for a given location id,
    /// or empty string if not found in cache.
    func pathString(forLocationId id: String?) -> String {
        guard let id, let loc = locationsFlat.first(where: { $0.id == id }) else { return "" }
        return loc.pathString
    }

    /// DFS flatten the tree, ignoring non-location nodes (just in case the API
    /// returns items mixed in).
    private static func flatten(tree: [HBTreeItem]) -> [FlatLocation] {
        var out: [FlatLocation] = []
        func walk(_ node: HBTreeItem, depth: Int, ancestors: [String]) {
            if node.type == "location" || node.type == "" {
                out.append(FlatLocation(id: node.id, name: node.name, depth: depth, ancestors: ancestors))
                let kids = (node.children ?? []).sorted { $0.name.lowercased() < $1.name.lowercased() }
                for k in kids { walk(k, depth: depth + 1, ancestors: ancestors + [node.name]) }
            }
        }
        let top = tree.sorted { $0.name.lowercased() < $1.name.lowercased() }
        for n in top { walk(n, depth: 0, ancestors: []) }
        return out
    }
}
