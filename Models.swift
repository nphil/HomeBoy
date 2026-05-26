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

// MARK: - GroupStats

/// Cached (locations, items) count for a group — used in the popover to show
/// totals on every collection card, not just the active one.
struct GroupStats: Equatable {
    let locationCount: Int
    let itemTotal: Int
}

// MARK: - HomeboxStore

/// Central state for Homebox connectivity + cached data.
///
/// Homebox is multi-tenant per request — one user can belong to many groups
/// (a.k.a. "collections"). Switching collections is purely client-side: we just
/// change `activeGroupId` and re-fetch. The HTTP client stamps every outgoing
/// request with `X-Tenant: <activeGroupId>` so the server scopes the response
/// to the right group. Same auth token works for every group the user belongs to.
@MainActor
final class HomeboxStore: ObservableObject {

    // MARK: Auth + config (persisted)

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
    /// Currently-selected group/collection — sent as `X-Tenant` on every request.
    /// `nil` means use the user's default group on the server.
    @Published private(set) var activeGroupId: String? {
        didSet {
            if let id = activeGroupId {
                UserDefaults.standard.set(id, forKey: Keys.activeGroupId)
            } else {
                UserDefaults.standard.removeObject(forKey: Keys.activeGroupId)
            }
        }
    }

    // MARK: In-memory caches

    @Published private(set) var groups: [HBGroup] = []
    @Published private(set) var locationsFlat: [FlatLocation] = []
    @Published var lastError: String?
    @Published private(set) var isLoadingLocations = false
    @Published private(set) var cachedItemTotal: Int? = nil
    @Published private(set) var groupName: String? = nil
    /// Per-group cached counts shown on each card in the SiteMenuPopover.
    @Published private(set) var cachedGroupStats: [String: GroupStats] = [:]

    var isAuthenticated: Bool { token != nil && serverURL != nil }

    var serverURL: URL? {
        let trimmed = serverURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let withScheme = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        return URL(string: withScheme)
    }

    /// HTTP client wired with the active token AND active group id. All API
    /// calls go through here, so switching `activeGroupId` automatically scopes
    /// every subsequent request to that group.
    var client: HomeboxClient? {
        guard let serverURL else { return nil }
        return HomeboxClient(serverURL: serverURL, token: token, tenantId: activeGroupId)
    }

    private enum Keys {
        static let serverURL     = "homebox.serverURL"
        static let username      = "homebox.username"
        static let token         = "homebox.token"
        static let activeGroupId = "homebox.activeGroupId"
    }

    // MARK: - Init

    init() {
        let savedURL = UserDefaults.standard.string(forKey: Keys.serverURL) ?? ""
        serverURLString = savedURL
        savedUsername   = UserDefaults.standard.string(forKey: Keys.username) ?? ""
        activeGroupId   = UserDefaults.standard.string(forKey: Keys.activeGroupId)

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
        token        = resp.token
        savedUsername = username
        // Clear stale group selection from previous logins
        activeGroupId = nil
        groups        = []
        try await refreshGroups()
        try await refreshLocations()
        await refreshItemTotal()
    }

    func logout() {
        token         = nil
        activeGroupId = nil
        groups        = []
        locationsFlat = []
        cachedItemTotal = nil
        groupName     = nil
        cachedGroupStats = [:]
    }

    // MARK: - Group switching (the X-Tenant story)

    /// Switch the active group/collection. Same auth token, just a different
    /// `X-Tenant` header on subsequent requests → different data comes back.
    func setActiveGroup(_ group: HBGroup) async {
        activeGroupId = group.id
        groupName     = group.name

        // Clear stale data so the UI doesn't briefly flash the wrong items
        locationsFlat   = []
        cachedItemTotal = nil

        try? await refreshLocations()
        await refreshItemTotal()

        // Keep this group's card stats in sync with what we just fetched
        cachedGroupStats[group.id] = GroupStats(
            locationCount: locationsFlat.count,
            itemTotal:     cachedItemTotal ?? 0
        )
    }

    /// Fetch (locationCount, itemTotal) for every group in `self.groups` using
    /// each group's own `X-Tenant` header. Results populate `cachedGroupStats`
    /// so the popover can show numbers on every card, not just the active one.
    func refreshAllGroupStats() async {
        guard let serverURL else { return }
        let snapshotToken  = token
        let snapshotGroups = groups
        guard !snapshotGroups.isEmpty else { return }

        var newStats: [String: GroupStats] = cachedGroupStats
        for group in snapshotGroups {
            let scoped = HomeboxClient(serverURL: serverURL,
                                       token: snapshotToken,
                                       tenantId: group.id)
            async let locTask  = scoped.listLocations()
            async let itemTask = scoped.listItems(page: 1, pageSize: 1)
            let locs    = (try? await locTask) ?? []
            let itemRes = try? await itemTask
            newStats[group.id] = GroupStats(
                locationCount: locs.count,
                itemTotal:     itemRes?.total ?? 0
            )
        }
        cachedGroupStats = newStats
    }

    // MARK: - Data fetching

    /// Fetch all groups the user is a member of. If `activeGroupId` is unset or
    /// no longer matches any group, defaults to the first group.
    func refreshGroups() async throws {
        guard let client else { throw HBError.notConfigured }
        let fetched = try await client.listGroups()
        self.groups = fetched

        // If our selection is stale or unset, fall back to the first group
        if activeGroupId == nil || !fetched.contains(where: { $0.id == activeGroupId }) {
            activeGroupId = fetched.first?.id
        }
        groupName = fetched.first { $0.id == activeGroupId }?.name ?? fetched.first?.name
    }

    /// Fetch only the total item count (pageSize=1 is enough — we just want the `total` field).
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
        // Compute recursive totals: each location counts items in itself + all descendants
        var totals: [String: Int] = [:]
        for loc in out {
            totals[loc.id, default: 0] += loc.itemCount
            for aid in loc.ancestorIds { totals[aid, default: 0] += loc.itemCount }
        }
        return out.map {
            FlatLocation(id: $0.id, name: $0.name, depth: $0.depth,
                         ancestors: $0.ancestors, ancestorIds: $0.ancestorIds,
                         parentId: $0.parentId, itemCount: totals[$0.id] ?? $0.itemCount)
        }
    }
}
