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
    let itemId: String
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

// MARK: - LocalDatabase

/// JSON-backed local store for offline use. Plain @MainActor class (not ObservableObject)
/// — callers read data from it directly; HomeboxStore publishes changes through its own @Published vars.
@MainActor
final class LocalDatabase {

    private(set) var items: [HBItem] = []
    private(set) var locations: [HBLocation] = []
    private(set) var tags: [HBTag] = []
    private(set) var pendingOps: [PendingOperation] = []
    private(set) var pendingMaintenance: [PendingMaintenanceOp] = []

    private let itemsURL: URL
    private let locationsURL: URL
    private let tagsURL: URL
    private let pendingOpsURL: URL
    private let pendingMaintenanceURL: URL

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
        tagsURL              = docs.appendingPathComponent("homebox_tags.json")
        pendingOpsURL        = docs.appendingPathComponent("homebox_pending.json")
        pendingMaintenanceURL = docs.appendingPathComponent("homebox_pending_maint.json")
        load()
    }

    // MARK: - Persistence

    private func load() {
        items              = loadJSON(from: itemsURL)              ?? []
        locations          = loadJSON(from: locationsURL)          ?? []
        tags               = loadJSON(from: tagsURL)               ?? []
        pendingOps         = loadJSON(from: pendingOpsURL)         ?? []
        pendingMaintenance = loadJSON(from: pendingMaintenanceURL) ?? []
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

    func cacheTags(_ newTags: [HBTag]) {
        tags = newTags
        persist(tags, to: tagsURL)
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

    // MARK: - Pending operations queue

    func enqueue(_ op: PendingOperation) {
        pendingOps.append(op)
        persist(pendingOps, to: pendingOpsURL)
    }

    func dequeue(id: String) {
        pendingOps.removeAll { $0.id == id }
        persist(pendingOps, to: pendingOpsURL)
    }

    // MARK: - Maintenance pending ops

    func enqueueMaintenance(_ op: PendingMaintenanceOp) {
        pendingMaintenance.removeAll { $0.entryId == op.entryId && op.entryId != nil }
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
