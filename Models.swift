import Foundation
import SwiftUI

/// State container for everything Homebox-related: auth, cached entity types,
/// cached location tree. Replaces the old local-queue `CatalogStore`.
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
    @Published private(set) var itemTypeId: String? {
        didSet { UserDefaults.standard.set(itemTypeId, forKey: Keys.itemTypeId) }
    }

    // MARK: - In-memory caches

    @Published var locations: [HBEntity] = []
    @Published var entityTypes: [HBEntityType] = []
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
        static let itemTypeId = "homebox.itemTypeId"
    }

    init() {
        serverURLString = UserDefaults.standard.string(forKey: Keys.serverURL) ?? ""
        savedUsername   = UserDefaults.standard.string(forKey: Keys.username) ?? ""
        token           = Keychain.get(Keys.token)
        itemTypeId      = UserDefaults.standard.string(forKey: Keys.itemTypeId)
    }

    // MARK: - Auth

    /// Logs in, stores the token, then fetches entity types and locations.
    func login(username: String, password: String) async throws {
        guard let url = serverURL else { throw HBError.badURL }
        let resp = try await HomeboxClient.login(serverURL: url, username: username, password: password)
        token = resp.token
        savedUsername = username
        try await bootstrap()
    }

    func logout() {
        token = nil
        itemTypeId = nil
        locations = []
        entityTypes = []
    }

    /// Loads entity types (to discover the Item type ID) and the location tree.
    func bootstrap() async throws {
        guard let client else { throw HBError.notConfigured }
        let types = try await client.entityTypes()
        await MainActor.run {
            self.entityTypes = types
            // Pick the first non-location type as "Item". If multiple item types
            // exist, the user could override later — for now keep it simple.
            if let item = types.first(where: { !$0.isLocation }) {
                self.itemTypeId = item.id
            }
        }
        try await refreshLocations()
    }

    func refreshLocations() async throws {
        guard let client else { throw HBError.notConfigured }
        await MainActor.run { self.isLoadingLocations = true }
        defer { Task { @MainActor in self.isLoadingLocations = false } }

        // Pull all entities and filter for locations client-side. Homebox doesn't
        // expose an entityTypeId filter on GET /v1/entities, so we fetch and split.
        // For most home inventories this fits in a single page (we ask for 1000).
        let page = try await client.entities(pageSize: 1000)
        let locs = page.items.filter { $0.isLocation }
        await MainActor.run { self.locations = locs }
    }

    // MARK: - Helpers

    /// Returns locations ordered by depth-first traversal of the tree, each
    /// paired with its depth, suitable for an indented picker.
    func locationsAsTree() -> [(entity: HBEntity, depth: Int)] {
        let byParent = Dictionary(grouping: locations) { $0.parent?.id ?? "" }
        var out: [(HBEntity, Int)] = []

        func walk(parentId: String, depth: Int) {
            let kids = (byParent[parentId] ?? []).sorted { $0.name.lowercased() < $1.name.lowercased() }
            for k in kids {
                out.append((k, depth))
                walk(parentId: k.id, depth: depth + 1)
            }
        }
        walk(parentId: "", depth: 0)
        return out
    }

    /// Builds a "A / B / C" path string for a given location id by walking ancestors.
    func pathString(forLocationId id: String?) -> String {
        guard let id, let loc = locations.first(where: { $0.id == id }) else { return "" }
        var parts = [loc.name]
        var current: HBParentRef? = loc.parent
        while let p = current {
            parts.insert(p.name, at: 0)
            current = p.parent
        }
        return parts.joined(separator: " / ")
    }
}
