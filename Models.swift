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
struct GroupStats: Equatable, Codable {
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

    // MARK: Offline support

    let localDB = LocalDatabase()
    let syncEngine = SyncEngine()

    @Published var isOfflineModeEnabled: Bool {
        didSet {
            UserDefaults.standard.set(isOfflineModeEnabled, forKey: Keys.offlineMode)
            if !isOfflineModeEnabled && isConnectedToNetwork && hasPendingChanges {
                Task { await syncPendingOps() }
            }
        }
    }
    @Published private(set) var isAuthenticatedOffline: Bool {
        didSet { UserDefaults.standard.set(isAuthenticatedOffline, forKey: Keys.offlineAuth) }
    }
    @Published private(set) var isConnectedToNetwork: Bool = true
    @Published private(set) var pendingOpsCount: Int = 0
    @Published private(set) var isSyncing = false

    private var hasPendingChanges: Bool {
        localDB.pendingOps.count > 0 || localDB.pendingMaintenance.count > 0 || localDB.pendingPhotos.count > 0
    }

    /// Recompute the unified pending-change count (item ops + maintenance + photos).
    /// Call after every enqueue/dequeue against `localDB`.
    func refreshPendingCount() {
        pendingOpsCount = localDB.pendingOps.count + localDB.pendingMaintenance.count + localDB.pendingPhotos.count
    }

    var isOffline: Bool { isOfflineModeEnabled || !isConnectedToNetwork }

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
    @Published private(set) var locationsFlat: [FlatLocation] = [] {
        didSet {
            // Rebuilt on every assignment (network refresh, offline hydrate,
            // logout/group switch) so pathString(forLocationId:) stays a
            // dictionary lookup instead of a per-row linear scan.
            var map = [String: String](minimumCapacity: locationsFlat.count)
            for loc in locationsFlat { map[loc.id] = loc.pathString }
            pathByLocationId = map
        }
    }
    private var pathByLocationId: [String: String] = [:]
    @Published var lastError: String?
    @Published private(set) var isLoadingLocations = false
    @Published private(set) var cachedItemTotal: Int? = nil
    @Published private(set) var groupName: String? = nil
    /// Per-group cached counts shown on each card in the SiteMenuPopover.
    @Published private(set) var cachedGroupStats: [String: GroupStats] = [:]

    var isAuthenticated: Bool { (token != nil && serverURL != nil) || isAuthenticatedOffline }

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
        static let offlineMode   = "homebox.offlineMode"
        static let offlineAuth   = "homebox.offlineAuth"
        static let cachedGroups     = "homebox.cachedGroups"
        static let cachedGroupStats = "homebox.cachedGroupStats"
    }

    // MARK: - Init

    init() {
        let savedURL = UserDefaults.standard.string(forKey: Keys.serverURL) ?? ""
        serverURLString       = savedURL
        savedUsername         = UserDefaults.standard.string(forKey: Keys.username) ?? ""
        activeGroupId         = UserDefaults.standard.string(forKey: Keys.activeGroupId)
        isOfflineModeEnabled  = UserDefaults.standard.bool(forKey: Keys.offlineMode)
        isAuthenticatedOffline = UserDefaults.standard.bool(forKey: Keys.offlineAuth)

        if savedURL.isEmpty {
            token = nil
            Keychain.delete(Keys.token)
        } else {
            token = Keychain.get(Keys.token)
        }

        refreshPendingCount()

        // Hydrate groups + per-group stats from the last successful refresh so
        // the collections menu works offline.
        if let data = UserDefaults.standard.data(forKey: Keys.cachedGroups),
           let savedGroups = try? JSONDecoder().decode([HBGroup].self, from: data) {
            groups = savedGroups
            groupName = savedGroups.first { $0.id == activeGroupId }?.name ?? savedGroups.first?.name
        }
        if let data = UserDefaults.standard.data(forKey: Keys.cachedGroupStats),
           let savedStats = try? JSONDecoder().decode([String: GroupStats].self, from: data) {
            cachedGroupStats = savedStats
        }

        syncEngine.onConnectionChange = { [weak self] connected in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isConnectedToNetwork = connected
                if connected && !self.isOfflineModeEnabled && self.hasPendingChanges {
                    await self.syncPendingOps()
                }
            }
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
        token                  = nil
        activeGroupId          = nil
        groups                 = []
        locationsFlat          = []
        cachedItemTotal        = nil
        groupName              = nil
        cachedGroupStats       = [:]
        isAuthenticatedOffline = false
    }

    func loginOffline() {
        isAuthenticatedOffline = true
    }

    /// Replay all queued offline changes (item creates/updates/deletes, photos,
    /// maintenance) against the server, in enqueue order. Failed ops stay queued.
    func syncPendingOps() async {
        guard let client else { return }
        guard !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }

        var syncedCount = 0

        // 1. Item operations (array order = enqueue order)
        let ops = localDB.pendingOps
        for op in ops {
            do {
                switch op.kind {
                case .createItem:
                    let payload = try JSONDecoder().decode(HBItemCreate.self, from: op.payload)
                    let serverId = try await client.createItem(payload)
                    if let localId = op.localId {
                        localDB.remapPendingPhotos(fromItemId: localId, to: serverId)
                        localDB.remapPendingMaintenance(fromItemId: localId, to: serverId)
                        localDB.removeItem(id: localId)
                    }
                case .updateItem:
                    let payload = try JSONDecoder().decode(HBItemUpdate.self, from: op.payload)
                    try await client.updateItem(payload)
                case .deleteItem:
                    if let itemId = op.localId {
                        try await client.deleteItem(id: itemId)
                    }
                }
                localDB.dequeue(id: op.id)
                syncedCount += 1
            } catch {}
        }

        // 2. Queued photos — after creates so "local-" ids have been remapped to
        //    server ids. Ops still pointing at a "local-" item wait for its create.
        let photoOps = localDB.pendingPhotos
        for op in photoOps {
            guard !op.itemId.hasPrefix("local-") else { continue }
            guard let data = localDB.pendingPhotoData(id: op.id) else {
                localDB.dequeuePhoto(id: op.id)   // bytes are gone — drop the op
                continue
            }
            do {
                try await client.uploadAttachment(itemId: op.itemId, fileData: data,
                                                  filename: op.fileName, primary: op.primary)
                localDB.dequeuePhoto(id: op.id)
                syncedCount += 1
            } catch {}
        }

        // 3. Pending maintenance operations. Skip any still pointing at a "local-"
        //    item whose create hasn't synced yet (a create failure left it unmapped)
        //    or at a "pending-" entry id the server can't resolve — both would 404
        //    forever; they wait for the create to land and remap them.
        let maintOps = localDB.pendingMaintenance
        for op in maintOps {
            guard !op.itemId.hasPrefix("local-"),
                  !(op.entryId?.hasPrefix("pending-") ?? false) else { continue }
            do {
                let entry = HBMaintenanceCreate(
                    name: op.name, description: op.description,
                    completedDate: op.completedDate, scheduledDate: op.scheduledDate, cost: op.cost
                )
                if let entryId = op.entryId {
                    try await client.updateMaintenance(id: entryId, entry: entry)
                } else {
                    _ = try await client.createMaintenance(itemId: op.itemId, entry: entry)
                }
                localDB.dequeueMaintenance(id: op.id)
                syncedCount += 1
            } catch {}
        }

        refreshPendingCount()

        if syncedCount > 0 {
            await refreshCachesAfterSync()
            NotificationCenter.default.post(name: .offlineSyncCompleted, object: nil)
            NotificationCenter.default.post(name: .showToast, object: nil,
                                            userInfo: ["message": "Synced \(syncedCount) offline change(s)"])
        }
    }

    /// After a successful sync pass, pull fresh data into the offline caches so
    /// they reflect what the server accepted.
    private func refreshCachesAfterSync() async {
        guard let client, !isOffline else { return }
        if let resp = try? await client.listItems(pageSize: 1000) {
            localDB.cacheItems(resp.items)
            cachedItemTotal = resp.total ?? resp.items.count
        }
        try? await refreshLocations()
        if let tags = try? await client.listTags() {
            localDB.cacheTags(tags)
        }
    }

    /// Save an offline create: queue a pending op and insert into the local cache.
    func enqueueOfflineCreate(payload: HBItemCreate, item: HBItem) {
        if let data = try? JSONEncoder().encode(payload) {
            localDB.enqueue(PendingOperation(
                id: UUID().uuidString, kind: .createItem,
                payload: data, localId: item.id, createdAt: Date()
            ))
            refreshPendingCount()
        }
        localDB.addItem(item)
    }

    /// Queue an offline edit and apply it optimistically to the local cache.
    /// Edits to an item that only exists as a queued create ("local-" id) rewrite
    /// that create's payload instead of queueing a PUT the server can't resolve.
    func enqueueOfflineUpdate(_ update: HBItemUpdate) {
        if update.id.hasPrefix("local-") {
            if let createOp = localDB.pendingOps.first(where: { $0.kind == .createItem && $0.localId == update.id }) {
                let payload = HBItemCreate(
                    name: update.name, quantity: update.quantity,
                    description: update.description,
                    parentId: update.parentId, tagIds: update.tagIds
                )
                if let data = try? JSONEncoder().encode(payload) {
                    localDB.replacePendingOpPayload(id: createOp.id, payload: data)
                }
            }
        } else if let data = try? JSONEncoder().encode(update) {
            localDB.enqueue(PendingOperation(
                id: UUID().uuidString, kind: .updateItem,
                payload: data, localId: update.id, createdAt: Date()
            ))
        }

        let location = update.parentId.flatMap { pid in
            locationsFlat.first(where: { $0.id == pid }).map {
                HBLocationSummary(id: $0.id, name: $0.name, description: nil)
            }
        }
        // Resolve tag ids against the cache; keep the old labels if we can't.
        let resolved = localDB.tags.filter { update.tagIds.contains($0.id) }
        let newTags: [HBTag]? = update.tagIds.isEmpty ? [] : (resolved.isEmpty ? nil : resolved)
        localDB.applyUpdate(update, location: location, tags: newTags)
        refreshPendingCount()
    }

    /// Queue an offline delete. Deleting an item that was created offline simply
    /// cancels the queued create (and any photos queued against it).
    func enqueueOfflineDelete(itemId: String) {
        // Whatever kind of item this is, its queued photos and maintenance can no
        // longer be applied once it's gone — drop them so they don't 404 forever
        // against a deleted (or never-created) item on every sync.
        for photo in localDB.pendingPhotoOps(for: itemId) {
            localDB.dequeuePhoto(id: photo.id)
        }
        for m in localDB.pendingMaintenanceOps(for: itemId) {
            localDB.dequeueMaintenance(id: m.id)
        }
        // Drop any queued create/update for this item; a server item additionally
        // needs a delete op so the server row is removed on the next sync.
        for op in localDB.pendingOps where op.localId == itemId
            && (op.kind == .createItem || op.kind == .updateItem) {
            localDB.dequeue(id: op.id)
        }
        if !itemId.hasPrefix("local-") {
            localDB.enqueue(PendingOperation(
                id: UUID().uuidString, kind: .deleteItem,
                payload: Data(), localId: itemId, createdAt: Date()
            ))
        }
        localDB.removeItem(id: itemId)
        refreshPendingCount()
    }

    /// Persist JPEG bytes for a photo taken offline; uploads on the next sync.
    func enqueueOfflinePhoto(itemId: String, jpegData: Data, primary: Bool) {
        localDB.enqueuePhoto(itemId: itemId, jpegData: jpegData, primary: primary)
        refreshPendingCount()
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
    /// Throttled: GroupMenuButton fires this on every tab appear, so skip when
    /// the last pass ran recently and only publish when the values changed.
    func refreshAllGroupStats() async {
        guard let serverURL else { return }
        if let last = lastGroupStatsRefresh, Date().timeIntervalSince(last) < 300 { return }
        let snapshotToken  = token
        let snapshotGroups = groups
        guard !snapshotGroups.isEmpty else { return }
        lastGroupStatsRefresh = Date()

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
        if newStats != cachedGroupStats {
            cachedGroupStats = newStats
            persistGroupCaches()
        }
    }

    /// Timestamp of the last completed group-stats pass (see refreshAllGroupStats).
    private var lastGroupStatsRefresh: Date? = nil

    /// Persist groups + per-group stats (small payloads) so the collections
    /// menu and its counts survive offline restarts.
    private func persistGroupCaches() {
        if let data = try? JSONEncoder().encode(groups) {
            UserDefaults.standard.set(data, forKey: Keys.cachedGroups)
        }
        if let data = try? JSONEncoder().encode(cachedGroupStats) {
            UserDefaults.standard.set(data, forKey: Keys.cachedGroupStats)
        }
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
        persistGroupCaches()
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
        // Offline path needs no client — serve the cached tree before the guard,
        // so a cleared/blank server URL doesn't mask cached data with an error.
        if isOffline {
            hydrateLocationsFromCache()
            return
        }
        guard let client else { throw HBError.notConfigured }
        isLoadingLocations = true
        defer { Task { @MainActor in self.isLoadingLocations = false } }

        do {
            async let treeTask = client.locationTree()
            async let listTask = client.listLocations()
            let (tree, list) = try await (treeTask, listTask)

            var countMap: [String: Int] = [:]
            for loc in list { countMap[loc.id] = Int(loc.itemCount ?? 0) }

            self.locationsFlat = Self.flatten(tree: tree, countMap: countMap)
            localDB.cacheLocations(list)
            localDB.cacheLocationTree(tree)
        } catch {
            // Fetch failed (flaky/absent network) — fall back to the last good cache.
            if hydrateLocationsFromCache() { return }
            throw error
        }
    }

    /// Rebuild `locationsFlat` from the cached tree + list. Returns false when
    /// nothing has been cached yet.
    @discardableResult
    private func hydrateLocationsFromCache() -> Bool {
        let tree = localDB.locationTree
        guard !tree.isEmpty else { return false }
        var countMap: [String: Int] = [:]
        for loc in localDB.locations { countMap[loc.id] = Int(loc.itemCount ?? 0) }
        locationsFlat = Self.flatten(tree: tree, countMap: countMap)
        return true
    }

    func updateCachedItemTotal(_ total: Int) {
        cachedItemTotal = total
    }

    /// Breadcrumb path (e.g. "Garage / Shelf A") for a given location id.
    func pathString(forLocationId id: String?) -> String {
        guard let id else { return "" }
        return pathByLocationId[id] ?? ""
    }

    /// Breadcrumb for an item row: the full location path when the location is
    /// known, else the raw location name from the item summary.
    func breadcrumb(for item: HBItem) -> String? {
        if let id = item.effectiveLocation?.id {
            let p = pathString(forLocationId: id)
            if !p.isEmpty { return p }
        }
        return item.effectiveLocation?.name
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
