import Foundation

// MARK: - Pending Operation

enum PendingOpKind: String, Codable {
    case createItem
    case updateItem
    case deleteItem
}

struct PendingOperation: Codable, Identifiable {
    let id: String
    let kind: PendingOpKind
    let payload: Data
    var localId: String?
    let createdAt: Date
}

// MARK: - Pending Maintenance Operation

struct PendingMaintenanceOp: Codable, Identifiable {
    let id: String            // local UUID; "pending-\(id)" is the display entry ID
    var itemId: String        // may be a "local-" placeholder until its item's create syncs
    let entryId: String?      // nil = create new, non-nil = update existing
    let name: String
    let description: String
    let completedDate: String?
    let scheduledDate: String
    let cost: Double
    let localCreatedAt: Date

    func asDisplayEntry() -> HBMaintenanceEntry {
        HBMaintenanceEntry(
            id: "pending-\(id)",
            name: name,
            description: description.isEmpty ? nil : description,
            completedDate: completedDate,
            scheduledDate: scheduledDate.isEmpty ? nil : scheduledDate,
            cost: cost > 0 ? cost : nil,
            createdAt: ISO8601DateFormatter().string(from: localCreatedAt)
        )
    }
}

// MARK: - Pending Photo Operation

/// A photo captured while offline, waiting to be uploaded. The JPEG bytes live
/// in Documents/pending_photos/<id>.jpg; `itemId` may be a "local-" placeholder
/// until the matching create syncs and the op is remapped to the server id.
struct PendingPhotoOp: Codable, Identifiable {
    let id: String
    var itemId: String
    let fileName: String
    let primary: Bool
    let createdAt: Date
}

// MARK: - LocalDatabase

/// JSON-backed local store for offline use. Plain @MainActor class (not ObservableObject)
/// — callers read data from it directly; HomeboxStore publishes changes through its own @Published vars.
@MainActor
final class LocalDatabase {

    private(set) var items: [HBItem] = []
    private(set) var locations: [HBLocation] = []
    private(set) var locationTree: [HBTreeItem] = []
    private(set) var tags: [HBTag] = []
    private(set) var pendingOps: [PendingOperation] = []
    private(set) var pendingMaintenance: [PendingMaintenanceOp] = []
    private(set) var pendingPhotos: [PendingPhotoOp] = []
    private(set) var maintenanceByItem: [String: [HBMaintenanceEntry]] = [:]
    private(set) var attachmentsByItem: [String: [HBAttachmentRef]] = [:]

    private let itemsURL: URL
    private let locationsURL: URL
    private let locationTreeURL: URL
    private let tagsURL: URL
    private let pendingOpsURL: URL
    private let pendingMaintenanceURL: URL
    private let pendingPhotosURL: URL
    private let maintenanceURL: URL
    private let attachmentsURL: URL
    private let pendingPhotosDir: URL

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()

    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        itemsURL             = docs.appendingPathComponent("homebox_items.json")
        locationsURL         = docs.appendingPathComponent("homebox_locations.json")
        locationTreeURL      = docs.appendingPathComponent("homebox_location_tree.json")
        tagsURL              = docs.appendingPathComponent("homebox_tags.json")
        pendingOpsURL        = docs.appendingPathComponent("homebox_pending.json")
        pendingMaintenanceURL = docs.appendingPathComponent("homebox_pending_maint.json")
        pendingPhotosURL     = docs.appendingPathComponent("homebox_pending_photos.json")
        maintenanceURL       = docs.appendingPathComponent("homebox_maintenance.json")
        attachmentsURL       = docs.appendingPathComponent("homebox_attachments.json")
        pendingPhotosDir     = docs.appendingPathComponent("pending_photos", isDirectory: true)
        try? FileManager.default.createDirectory(at: pendingPhotosDir, withIntermediateDirectories: true)
        load()
    }

    // MARK: - Persistence

    private func load() {
        items              = loadJSON(from: itemsURL)              ?? []
        locations          = loadJSON(from: locationsURL)          ?? []
        locationTree       = loadJSON(from: locationTreeURL)       ?? []
        tags               = loadJSON(from: tagsURL)               ?? []
        pendingOps         = loadJSON(from: pendingOpsURL)         ?? []
        pendingMaintenance = loadJSON(from: pendingMaintenanceURL) ?? []
        pendingPhotos      = loadJSON(from: pendingPhotosURL)      ?? []
        maintenanceByItem  = loadJSON(from: maintenanceURL)        ?? [:]
        attachmentsByItem  = loadJSON(from: attachmentsURL)        ?? [:]
    }

    private func loadJSON<T: Decodable>(from url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? decoder.decode(T.self, from: data)
    }

    private func persist<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? encoder.encode(value) else { return }
        try? data.write(to: url, options: .atomic)
    }

    // MARK: - Cache update (called after successful server fetch)

    func cacheItems(_ newItems: [HBItem]) {
        items = newItems
        persist(items, to: itemsURL)
    }

    func cacheLocations(_ newLocations: [HBLocation]) {
        locations = newLocations
        persist(locations, to: locationsURL)
    }

    func cacheLocationTree(_ tree: [HBTreeItem]) {
        locationTree = tree
        persist(locationTree, to: locationTreeURL)
    }

    func cacheTags(_ newTags: [HBTag]) {
        tags = newTags
        persist(tags, to: tagsURL)
    }

    func cacheMaintenance(itemId: String, entries: [HBMaintenanceEntry]) {
        maintenanceByItem[itemId] = entries
        persist(maintenanceByItem, to: maintenanceURL)
    }

    func maintenanceEntries(for itemId: String) -> [HBMaintenanceEntry] {
        maintenanceByItem[itemId] ?? []
    }

    func cacheAttachments(itemId: String, attachments: [HBAttachmentRef]) {
        attachmentsByItem[itemId] = attachments
        persist(attachmentsByItem, to: attachmentsURL)
    }

    func attachments(for itemId: String) -> [HBAttachmentRef] {
        attachmentsByItem[itemId] ?? []
    }

    // MARK: - Local CRUD (offline mode)

    func addItem(_ item: HBItem) {
        items.insert(item, at: 0)
        persist(items, to: itemsURL)
    }

    func removeItem(id: String) {
        items.removeAll { $0.id == id }
        persist(items, to: itemsURL)
    }

    /// Optimistically apply an offline edit to the cached list item so the UI
    /// reflects it immediately. `location`/`tags` are pre-resolved summaries;
    /// pass nil to keep the item's existing values.
    func applyUpdate(_ update: HBItemUpdate, location: HBLocationSummary?, tags newTags: [HBTag]?) {
        guard let idx = items.firstIndex(where: { $0.id == update.id }) else { return }
        let old = items[idx]
        items[idx] = HBItem(
            id: old.id,
            name: update.name,
            description: update.description.isEmpty ? nil : update.description,
            quantity: update.quantity,
            archived: update.archived,
            createdAt: old.createdAt,
            location: location ?? old.location,
            parent: old.parent,
            labels: newTags ?? old.labels,
            tags: old.tags
        )
        persist(items, to: itemsURL)
    }

    // MARK: - Pending operations queue

    func enqueue(_ op: PendingOperation) {
        pendingOps.append(op)
        persist(pendingOps, to: pendingOpsURL)
    }

    func dequeue(id: String) {
        pendingOps.removeAll { $0.id == id }
        persist(pendingOps, to: pendingOpsURL)
    }

    /// Swap the payload of a queued op in place (keeps queue order) — used when
    /// an offline-created item is edited again before it ever reaches the server.
    func replacePendingOpPayload(id: String, payload: Data) {
        guard let idx = pendingOps.firstIndex(where: { $0.id == id }) else { return }
        let old = pendingOps[idx]
        pendingOps[idx] = PendingOperation(
            id: old.id, kind: old.kind, payload: payload,
            localId: old.localId, createdAt: old.createdAt
        )
        persist(pendingOps, to: pendingOpsURL)
    }

    // MARK: - Maintenance pending ops

    func enqueueMaintenance(_ op: PendingMaintenanceOp) {
        // Replace a prior op that targets the same thing: same local op id (re-editing
        // a not-yet-synced entry) or the same server entry (a queued update).
        pendingMaintenance.removeAll { $0.id == op.id || ($0.entryId == op.entryId && op.entryId != nil) }
        pendingMaintenance.append(op)
        persist(pendingMaintenance, to: pendingMaintenanceURL)
    }

    func dequeueMaintenance(id: String) {
        pendingMaintenance.removeAll { $0.id == id }
        persist(pendingMaintenance, to: pendingMaintenanceURL)
    }

    func pendingMaintenanceOps(for itemId: String) -> [PendingMaintenanceOp] {
        pendingMaintenance.filter { $0.itemId == itemId }
    }

    /// After an offline item create syncs, point its queued maintenance ops at the
    /// real server id so they can be created against a resolvable item.
    func remapPendingMaintenance(fromItemId: String, to newItemId: String) {
        var changed = false
        for i in pendingMaintenance.indices where pendingMaintenance[i].itemId == fromItemId {
            pendingMaintenance[i].itemId = newItemId
            changed = true
        }
        if changed { persist(pendingMaintenance, to: pendingMaintenanceURL) }
    }

    // MARK: - Pending photo queue

    /// Persist JPEG bytes to Documents/pending_photos and queue the upload.
    func enqueuePhoto(itemId: String, jpegData: Data, primary: Bool) {
        let id = UUID().uuidString
        let fileURL = pendingPhotosDir.appendingPathComponent("\(id).jpg")
        guard (try? jpegData.write(to: fileURL, options: .atomic)) != nil else { return }
        let op = PendingPhotoOp(
            id: id,
            itemId: itemId,
            fileName: "photo-\(Int(Date().timeIntervalSince1970))-\(id.prefix(8)).jpg",
            primary: primary,
            createdAt: Date()
        )
        pendingPhotos.append(op)
        persist(pendingPhotos, to: pendingPhotosURL)
    }

    func dequeuePhoto(id: String) {
        pendingPhotos.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: pendingPhotoFileURL(id: id))
        persist(pendingPhotos, to: pendingPhotosURL)
    }

    func pendingPhotoOps(for itemId: String) -> [PendingPhotoOp] {
        pendingPhotos.filter { $0.itemId == itemId }
    }

    func pendingPhotoData(id: String) -> Data? {
        try? Data(contentsOf: pendingPhotoFileURL(id: id))
    }

    func pendingPhotoFileURL(id: String) -> URL {
        pendingPhotosDir.appendingPathComponent("\(id).jpg")
    }

    /// After an offline create syncs, point its queued photos at the real server id.
    func remapPendingPhotos(fromItemId: String, to newItemId: String) {
        var changed = false
        for i in pendingPhotos.indices where pendingPhotos[i].itemId == fromItemId {
            pendingPhotos[i].itemId = newItemId
            changed = true
        }
        if changed { persist(pendingPhotos, to: pendingPhotosURL) }
    }

    // MARK: - Factory

    func makeOfflineItem(from create: HBItemCreate, location: HBLocationSummary?) -> HBItem {
        HBItem(
            id: "local-\(UUID().uuidString)",
            name: create.name,
            description: create.description.isEmpty ? nil : create.description,
            quantity: create.quantity,
            archived: false,
            createdAt: ISO8601DateFormatter().string(from: Date()),
            location: location,
            parent: nil,
            labels: nil,
            tags: nil
        )
    }

    // MARK: - CSV export (Homebox import format)

    func exportCSV() -> String {
        var lines = ["HB.import_ref,Location,Quantity,Name,Description,Archived,Labels"]
        for item in items {
            let ref  = item.id
            let loc  = escape(item.effectiveLocation?.name ?? "")
            let qty  = item.quantityInt
            let name = escape(item.name)
            let desc = escape(item.description ?? "")
            let arch = item.archived == true ? "true" : "false"
            let lbls = escape((item.effectiveLabels ?? []).map { $0.name }.joined(separator: ";"))
            lines.append("\(ref),\(loc),\(qty),\(name),\(desc),\(arch),\(lbls)")
        }
        return lines.joined(separator: "\n")
    }

    private func escape(_ s: String) -> String {
        guard s.contains(",") || s.contains("\"") || s.contains("\n") else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }
}
