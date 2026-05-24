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
    @Published private(set) var cachedItemTotal: Int? = nil

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
        static let serverURL = "homebox.serverURL"
        static let username  = "homebox.username"
        static let token     = "homebox.token"
    }

    init() {
        let savedURL = UserDefaults.standard.string(forKey: Keys.serverURL) ?? ""
        serverURLString = savedURL
        savedUsername   = UserDefaults.standard.string(forKey: Keys.username) ?? ""
        
        if savedURL.isEmpty {
            token = nil
            Keychain.delete(Keys.token) // Clean up dangling token from previous installs
        } else {
            token = Keychain.get(Keys.token)
        }
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
        cachedItemTotal = nil
    }

    // MARK: - Locations

    func refreshLocations() async throws {
        guard let client else { throw HBError.notConfigured }
        isLoadingLocations = true
        defer { Task { @MainActor in self.isLoadingLocations = false } }

        // Fetch tree (hierarchy) and flat list (item counts) in parallel.
        async let treeTask = client.locationTree()
        async let listTask = client.listLocations()
        let (tree, list) = try await (treeTask, listTask)

        var countMap: [String: Int] = [:]
        for loc in list { countMap[loc.id] = Int(loc.itemCount ?? 0) }

        let flat = Self.flatten(tree: tree, countMap: countMap)
        self.locationsFlat = flat
    }

    func updateCachedItemTotal(_ total: Int) {
        cachedItemTotal = total
    }

    /// Returns the breadcrumb path (e.g. "Garage / Shelf A") for a given location id,
    /// or empty string if not found in cache.
    func pathString(forLocationId id: String?) -> String {
        guard let id, let loc = locationsFlat.first(where: { $0.id == id }) else { return "" }
        return loc.pathString
    }

    /// DFS flatten the tree, ignoring non-location nodes.
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
