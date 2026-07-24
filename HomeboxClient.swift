import Foundation

// MARK: - Models (matches Homebox v0.25.x API)

struct HBLoginResponse: Codable {
    let token: String
    let expiresAt: String?
    let attachmentToken: String?
}

/// Minimal info we hold for a location — the shape returned by `GET /v1/locations`
/// (LocationOutCount). The list endpoint does NOT include parent info, so the
/// breadcrumb path is built from the `/v1/locations/tree` response.
struct HBLocation: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
    let itemCount: Double?
}

/// Returned by `GET /v1/locations/tree`. Self-referential, so it's a `final class`.
final class HBTreeItem: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let type: String
    let children: [HBTreeItem]?

    init(id: String, name: String, type: String, children: [HBTreeItem]?) {
        self.id = id; self.name = name; self.type = type; self.children = children
    }

    static func == (lhs: HBTreeItem, rhs: HBTreeItem) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

struct HBLocationSummary: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
}

/// Item summary as returned by `GET /v1/items` (paginated).
/// Homebox versions vary: some include `labels`, some `tags`, some neither in the summary.
/// Use `effectiveLabels` instead of accessing the two fields directly.
struct HBItem: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
    let quantity: Double?
    let archived: Bool?
    let createdAt: String?
    let location: HBLocationSummary?
    let parent: HBLocationSummary?
    let labels: [HBTag]?
    let tags: [HBTag]?

    var quantityInt: Int { Int(quantity ?? 1) }

    /// nil = the API didn't include labels in the summary (trust server-side ?labels= filter).
    /// [] = the API did include them and the item has none (genuinely no tags).
    var effectiveLabels: [HBTag]? { labels ?? tags }
    var effectiveLocation: HBLocationSummary? { location ?? parent }
}

struct HBItemListResponse: Codable {
    let items: [HBItem]
    let page: Int?
    let pageSize: Int?
    let total: Int?
}

struct HBItemCreate: Codable {
    let name: String
    let quantity: Double
    let description: String
    let parentId: String?
    let tagIds: [String]
}

/// Minimal decode of POST /v1/items response — just the id so we can attach.
struct HBItemCreateResponse: Codable { let id: String }

struct HBLocationCreate: Codable {
    let name: String
    let parentId: String?
    let description: String
}

struct HBLocationCreateResponse: Codable {
    let id: String
    let name: String
}

// MARK: - Detail models

struct HBTag: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
    let color: String?
    let icon: String?
    var itemCount: Double?
}

struct HBGroup: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
}

struct HBAttachmentRef: Codable, Identifiable, Hashable {
    let id: String
    let type: String
    let primary: Bool?
    let title: String?
    let mimeType: String?
    let createdAt: String?
}

/// Full item record returned by `GET /v1/items/{id}` (subset of ItemOut we use).
/// All fields are optional except `id` and `name` to tolerate older versions.
struct HBItemDetail: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var description: String?
    var quantity: Double?
    var notes: String?
    var insured: Bool?
    var archived: Bool?
    var assetId: String?
    var serialNumber: String?
    var modelNumber: String?
    var manufacturer: String?
    var purchasePrice: Double?
    var purchaseFrom: String?
    var purchaseDate: String?
    var lifetimeWarranty: Bool?
    var warrantyExpires: String?
    var warrantyDetails: String?
    var soldTo: String?
    var soldPrice: Double?
    var soldDate: String?
    var soldNotes: String?
    var parent: HBItemSummary?
    var syncChildEntityLocations: Bool?
    var createdAt: String?
    var updatedAt: String?
    var location: HBLocationSummary?
    var tags: [HBTag]?
    var attachments: [HBAttachmentRef]?

    var quantityInt: Int { Int(quantity ?? 1) }

    var effectiveLocation: HBLocationSummary? {
        location ?? parent.map { HBLocationSummary(id: $0.id, name: $0.name, description: nil) }
    }

    /// Build a minimal detail from a cached list item for offline display.
    init(offline item: HBItem) {
        id = item.id; name = item.name
        description = item.description; quantity = item.quantity
        archived = item.archived; createdAt = item.createdAt
        location = item.location; tags = item.effectiveLabels
        notes = nil; insured = nil; assetId = nil; serialNumber = nil
        modelNumber = nil; manufacturer = nil; purchasePrice = nil
        purchaseFrom = nil; purchaseDate = nil; lifetimeWarranty = nil
        warrantyExpires = nil; warrantyDetails = nil; soldTo = nil
        soldPrice = nil; soldDate = nil; soldNotes = nil; parent = nil
        syncChildEntityLocations = nil; updatedAt = nil; attachments = nil
    }
}

/// PUT /v1/items/{id} body. Build it from an HBItemDetail you already have so
/// fields you don't touch round-trip cleanly.
struct HBItemUpdate: Codable {
    var id: String
    var name: String
    var description: String
    var quantity: Double
    var insured: Bool
    var archived: Bool
    var assetId: String
    var parentId: String?
    var tagIds: [String]
    var serialNumber: String
    var modelNumber: String
    var manufacturer: String
    var lifetimeWarranty: Bool
    var warrantyExpires: String
    var warrantyDetails: String
    var purchaseDate: String
    var purchaseFrom: String
    var purchasePrice: Double
    var soldDate: String
    var soldTo: String
    var soldPrice: Double
    var soldNotes: String
    var notes: String
    var syncChildEntityLocations: Bool
}

extension HBItemUpdate {
    /// Seed from a fetched detail. Any nil values become defaults.
    init(from d: HBItemDetail, overrideLocationId: String? = nil, overrideTagIds: [String]? = nil) {
        self.id = d.id
        self.name = d.name
        self.description = d.description ?? ""
        self.quantity = d.quantity ?? 1
        self.insured = d.insured ?? false
        self.archived = d.archived ?? false
        self.assetId = d.assetId ?? "0"
        self.parentId = overrideLocationId ?? d.effectiveLocation?.id
        self.tagIds = overrideTagIds ?? (d.tags?.map { $0.id } ?? [])
        self.serialNumber = d.serialNumber ?? ""
        self.modelNumber = d.modelNumber ?? ""
        self.manufacturer = d.manufacturer ?? ""
        self.lifetimeWarranty = d.lifetimeWarranty ?? false
        self.warrantyExpires = d.warrantyExpires ?? ""
        self.warrantyDetails = d.warrantyDetails ?? ""
        self.purchaseDate = d.purchaseDate ?? ""
        self.purchaseFrom = d.purchaseFrom ?? ""
        self.purchasePrice = d.purchasePrice ?? 0
        self.soldDate = d.soldDate ?? ""
        self.soldTo = d.soldTo ?? ""
        self.soldPrice = d.soldPrice ?? 0
        self.soldNotes = d.soldNotes ?? ""
        self.notes = d.notes ?? ""
        self.syncChildEntityLocations = d.syncChildEntityLocations ?? false
    }
}

/// GET /v1/locations/{id} (LocationOut).
struct HBLocationDetail: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    var description: String?
    var totalPrice: Double?
    var createdAt: String?
    var updatedAt: String?
    var parent: HBLocationSummary?
    var children: [HBLocationSummary]?
}

struct HBLocationUpdate: Codable {
    var id: String
    var name: String
    var description: String
    var parentId: String?
}

/// Minimal parent/child reference used in item detail responses.
struct HBItemSummary: Codable, Identifiable, Hashable {
    let id: String
    let name: String
}

struct HBMaintenanceEntry: Codable, Identifiable {
    let id: String
    let name: String
    var description: String?
    var completedDate: String?
    var scheduledDate: String?
    var cost: Double?
    var createdAt: String?
    var updatedAt: String?

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id            = try c.decode(String.self, forKey: .id)
        name          = try c.decode(String.self, forKey: .name)
        description   = try? c.decodeIfPresent(String.self, forKey: .description)
        completedDate = try? c.decodeIfPresent(String.self, forKey: .completedDate)
        scheduledDate = try? c.decodeIfPresent(String.self, forKey: .scheduledDate)
        createdAt     = try? c.decodeIfPresent(String.self, forKey: .createdAt)
        updatedAt     = try? c.decodeIfPresent(String.self, forKey: .updatedAt)
        // cost is encoded as a JSON string by the server ("cost,string" tag)
        if let s = try? c.decodeIfPresent(String.self, forKey: .cost) {
            cost = Double(s)
        } else {
            cost = try? c.decodeIfPresent(Double.self, forKey: .cost)
        }
    }

    /// Mirror of `init(from:)` for the offline cache: cost round-trips as a JSON
    /// string, matching the server's `json:"cost,string"` encoding.
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encodeIfPresent(description, forKey: .description)
        try c.encodeIfPresent(completedDate, forKey: .completedDate)
        try c.encodeIfPresent(scheduledDate, forKey: .scheduledDate)
        try c.encodeIfPresent(createdAt, forKey: .createdAt)
        try c.encodeIfPresent(updatedAt, forKey: .updatedAt)
        if let cost {
            let costStr = cost.truncatingRemainder(dividingBy: 1) == 0
                ? "\(Int(cost))"
                : "\(cost)"
            try c.encode(costStr, forKey: .cost)
        }
    }

    enum CodingKeys: String, CodingKey {
        case id, name, description, completedDate, scheduledDate, cost, createdAt, updatedAt
    }

    init(id: String, name: String, description: String? = nil, completedDate: String? = nil,
         scheduledDate: String? = nil, cost: Double? = nil, createdAt: String? = nil, updatedAt: String? = nil) {
        self.id = id; self.name = name; self.description = description
        self.completedDate = completedDate; self.scheduledDate = scheduledDate
        self.cost = cost; self.createdAt = createdAt; self.updatedAt = updatedAt
    }
}

struct HBMaintenanceCreate: Encodable {
    var name: String
    var description: String
    var completedDate: String?    // omitted when nil (entry not yet completed); YYYY-MM-DD
    var scheduledDate: String     // YYYY-MM-DD
    var cost: Double

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(name, forKey: .name)
        try c.encode(description, forKey: .description)
        try c.encodeIfPresent(completedDate, forKey: .completedDate)
        try c.encode(scheduledDate, forKey: .scheduledDate)
        // server uses json:"cost,string" — must send as JSON string; use locale-independent formatting
        let costStr = cost.truncatingRemainder(dividingBy: 1) == 0
            ? "\(Int(cost))"
            : "\(cost)"
        try c.encode(costStr, forKey: .cost)
    }

    enum CodingKeys: String, CodingKey {
        case name, description, completedDate, scheduledDate, cost
    }
}

struct HBBarcodeItem: Codable {
    let name: String?
    let quantity: Double?
    let description: String?
}

struct HBBarcodeProduct: Codable {
    let barcode: String?
    let imageBase64: String?
    let imageURL: String?
    let item: HBBarcodeItem?
    let manufacturer: String?
    let modelNumber: String?
    let notes: String?
    let searchEngineName: String?

    enum CodingKeys: String, CodingKey {
        case barcode, imageBase64, imageURL, item, manufacturer, modelNumber, notes
        case searchEngineName = "search_engine_name"
    }
}

struct HBEntityType: Codable, Identifiable {
    let id: String
    let name: String
    let isLocation: Bool
}

// MARK: - Errors

enum HBError: LocalizedError {
    case notConfigured
    case badURL
    case http(Int, String)
    case decode(Error)
    case transport(Error)
    case unauthorized

    var errorDescription: String? {
        switch self {
        case .notConfigured: return "Server URL or login is missing."
        case .badURL:        return "Server URL is malformed."
        case .http(let c, let m): return "HTTP \(c): \(m)"
        case .decode(let e): return "Decode error: \(e.localizedDescription)"
        case .transport(let e): return "Network error: \(e.localizedDescription)"
        case .unauthorized:  return "Not signed in. Open Settings to log in."
        }
    }
}

// MARK: - Client

/// Stateless HTTP client. Caller holds the server URL + token + active group id.
/// `tenantId` (when set) is sent on every request as `X-Tenant: <uuid>` — this is
/// how Homebox routes a request to a specific group/collection. Same token works
/// for any group the user is a member of. Without the header, the server falls
/// back to the user's `defaultGroupId`.
struct HomeboxClient {
    var serverURL: URL
    var token: String?
    var tenantId: String?

    init(serverURL: URL, token: String? = nil, tenantId: String? = nil) {
        self.serverURL = serverURL
        self.token = token
        self.tenantId = tenantId
    }

    private func url(_ path: String, query: [URLQueryItem] = []) throws -> URL {
        var comps = URLComponents(url: serverURL.appendingPathComponent("api").appendingPathComponent(path), resolvingAgainstBaseURL: false)
        if !query.isEmpty { comps?.queryItems = query }
        guard let u = comps?.url else { throw HBError.badURL }
        return u
    }

    private func request(_ path: String, method: String, body: Data? = nil, contentType: String = "application/json", query: [URLQueryItem] = [], allowAuthRecovery: Bool = true) async throws -> Data {
        var req = URLRequest(url: try url(path, query: query))
        req.httpMethod = method
        req.httpBody = body
        if body != nil { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let token { req.setValue(token, forHTTPHeaderField: "Authorization") }
        // Scope this request to the selected group/collection. If nil, the server
        // uses the user's default group.
        if let tenantId { req.setValue(tenantId, forHTTPHeaderField: "X-Tenant") }

        let (data, resp): (Data, URLResponse)
        do {
            (data, resp) = try await URLSession.shared.data(for: req)
        } catch {
            throw HBError.transport(error)
        }
        guard let http = resp as? HTTPURLResponse else { throw HBError.http(-1, "No HTTP response") }
        if http.statusCode == 401 || http.statusCode == 403 {
            // Self-healing auth: Homebox tokens expire. On a 401 (token had been
            // sent, first attempt only), refresh it — or silently re-login with the
            // saved credentials — then retry this request once with the new token.
            if http.statusCode == 401, allowAuthRecovery, let failedToken = token {
                let fresh = await AuthRecovery.shared.recover(serverURL: serverURL, failedToken: failedToken)
                if let fresh {
                    var retry = self
                    retry.token = fresh
                    return try await retry.request(path, method: method, body: body,
                                                   contentType: contentType, query: query,
                                                   allowAuthRecovery: false)
                }
            }
            throw HBError.unauthorized
        }
        if !(200...299).contains(http.statusCode) {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw HBError.http(http.statusCode, body)
        }
        return data
    }

    // MARK: Auth

    static func login(serverURL: URL, username: String, password: String, stayLoggedIn: Bool = true) async throws -> HBLoginResponse {
        let client = HomeboxClient(serverURL: serverURL, token: nil)
        var body = URLComponents()
        body.queryItems = [
            URLQueryItem(name: "username", value: username),
            URLQueryItem(name: "password", value: password),
            URLQueryItem(name: "stayLoggedIn", value: stayLoggedIn ? "true" : "false"),
        ]
        let data = try await client.request(
            "v1/users/login",
            method: "POST",
            body: body.percentEncodedQuery?.data(using: .utf8),
            contentType: "application/x-www-form-urlencoded"
        )
        do { return try JSONDecoder().decode(HBLoginResponse.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    // MARK: Locations

    /// `GET /v1/entities?isLocation=true` — flat list (no parent info).
    func listLocations() async throws -> [HBLocation] {
        let data = try await request("v1/entities", method: "GET", query: [
            URLQueryItem(name: "isLocation", value: "true"),
            URLQueryItem(name: "pageSize", value: "500")
        ])
        struct Page: Codable { let items: [HBLocation] }
        do { return try JSONDecoder().decode(Page.self, from: data).items }
        catch { throw HBError.decode(error) }
    }

    /// `GET /v1/entities/tree?withItems=false` — nested tree for the picker.
    func locationTree() async throws -> [HBTreeItem] {
        let data = try await request(
            "v1/entities/tree",
            method: "GET",
            query: [URLQueryItem(name: "withItems", value: "false")]
        )
        do { return try JSONDecoder().decode([HBTreeItem].self, from: data) }
        catch { throw HBError.decode(error) }
    }

    // MARK: Items

    /// `GET /v1/entities?isLocation=false` — paginated list of items, optionally filtered.
    func listItems(query: String? = nil, locationIds: [String] = [], labelIds: [String] = [], parentIds: [String] = [], includeArchived: Bool = false, page: Int = 1, pageSize: Int = 500) async throws -> HBItemListResponse {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "pageSize", value: String(pageSize)),
            URLQueryItem(name: "isLocation", value: "false"),
        ]
        if let query, !query.isEmpty { items.append(URLQueryItem(name: "q", value: query)) }
        for id in locationIds { items.append(URLQueryItem(name: "parentIds", value: id)) }
        for id in labelIds { items.append(URLQueryItem(name: "tags", value: id)) }
        for id in parentIds { items.append(URLQueryItem(name: "parentIds", value: id)) }
        if includeArchived { items.append(URLQueryItem(name: "includeArchived", value: "true")) }
        let data = try await request("v1/entities", method: "GET", query: items)
        do { return try JSONDecoder().decode(HBItemListResponse.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    /// `POST /v1/entities` — create an item under a location. Returns the new item's id.
    @discardableResult
    func createItem(_ payload: HBItemCreate) async throws -> String {
        let body = try JSONEncoder().encode(payload)
        let data = try await request("v1/entities", method: "POST", body: body)
        do { return try JSONDecoder().decode(HBItemCreateResponse.self, from: data).id }
        catch { throw HBError.decode(error) }
    }

    /// `POST /v1/items/{id}/attachments` — multipart/form-data upload.
    /// `mimeType` is set on the multipart part; Homebox itself infers attachment type from the filename extension.
    func uploadAttachment(itemId: String, fileData: Data, filename: String, mimeType: String = "image/jpeg", primary: Bool = false) async throws {
        let boundary = "----HomeBoy\(UUID().uuidString)"
        var body = Data()
        func appendString(_ s: String) { body.append(s.data(using: .utf8)!) }

        // file part
        appendString("--\(boundary)\r\n")
        appendString("Content-Disposition: form-data; name=\"file\"; filename=\"\(filename)\"\r\n")
        appendString("Content-Type: \(mimeType)\r\n\r\n")
        body.append(fileData)
        appendString("\r\n")

        // name part
        appendString("--\(boundary)\r\n")
        appendString("Content-Disposition: form-data; name=\"name\"\r\n\r\n")
        appendString("\(filename)\r\n")

        // primary part
        appendString("--\(boundary)\r\n")
        appendString("Content-Disposition: form-data; name=\"primary\"\r\n\r\n")
        appendString(primary ? "true\r\n" : "false\r\n")

        appendString("--\(boundary)--\r\n")

        _ = try await request(
            "v1/entities/\(itemId)/attachments",
            method: "POST",
            body: body,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
    }

    /// `POST /v1/entities` — create a location, optionally under a parent.
    @discardableResult
    func createLocation(name: String, parentId: String?, description: String = "") async throws -> String {
        let types = try await listEntityTypes()
        let locTypeId = types.first(where: { $0.isLocation })?.id ?? ""
        struct EntityCreate: Codable {
            let name: String
            let description: String
            let parentId: String?
            let entityTypeId: String
        }
        let payload = EntityCreate(name: name, description: description, parentId: parentId, entityTypeId: locTypeId)
        let body = try JSONEncoder().encode(payload)
        let data = try await request("v1/entities", method: "POST", body: body)
        do { return try JSONDecoder().decode(HBLocationCreateResponse.self, from: data).id }
        catch { throw HBError.decode(error) }
    }

    // MARK: Item detail / edit / delete

    func getItem(id: String) async throws -> HBItemDetail {
        let data = try await request("v1/entities/\(id)", method: "GET")
        do { return try JSONDecoder().decode(HBItemDetail.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func updateItem(_ payload: HBItemUpdate) async throws {
        let body = try JSONEncoder().encode(payload)
        _ = try await request("v1/entities/\(payload.id)", method: "PUT", body: body)
    }

    func deleteItem(id: String) async throws {
        _ = try await request("v1/entities/\(id)", method: "DELETE")
    }

    // MARK: Location detail / edit / delete

    func getLocation(id: String) async throws -> HBLocationDetail {
        let data = try await request("v1/entities/\(id)", method: "GET")
        do { return try JSONDecoder().decode(HBLocationDetail.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func updateLocation(_ payload: HBLocationUpdate) async throws {
        let body = try JSONEncoder().encode(payload)
        _ = try await request("v1/entities/\(payload.id)", method: "PUT", body: body)
    }

    func deleteLocation(id: String) async throws {
        _ = try await request("v1/entities/\(id)", method: "DELETE")
    }

    // MARK: Groups

    /// `GET /v1/groups/all` — list every group the user is a member of.
    /// The frontend calls these "collections" but they're the same thing — group UUIDs
    /// stamped into the `X-Tenant` header to scope every other request.
    func listGroups() async throws -> [HBGroup] {
        let data = try await request("v1/groups/all", method: "GET")
        do { return try JSONDecoder().decode([HBGroup].self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func listEntityTypes() async throws -> [HBEntityType] {
        let data = try await request("v1/entity-types", method: "GET")
        do { return try JSONDecoder().decode([HBEntityType].self, from: data) }
        catch { throw HBError.decode(error) }
    }

    // MARK: Tags

    func listTags() async throws -> [HBTag] {
        // Homebox sometimes wraps tags in a `{ items: [...] }` envelope and sometimes not.
        // Try both shapes.
        let data = try await request("v1/tags", method: "GET")
        if let arr = try? JSONDecoder().decode([HBTag].self, from: data) { return arr }
        struct Wrap: Codable { let items: [HBTag] }
        if let w = try? JSONDecoder().decode(Wrap.self, from: data) { return w.items }
        throw HBError.decode(NSError(domain: "homebox", code: 0, userInfo: [NSLocalizedDescriptionKey: "Unexpected tags response shape"]))
    }

    struct TagCreatePayload: Codable {
        let name: String
        let description: String
        let color: String
    }

    @discardableResult
    func createTag(name: String, description: String = "", color: String = "") async throws -> HBTag {
        let body = try JSONEncoder().encode(TagCreatePayload(name: name, description: description, color: color))
        let data = try await request("v1/tags", method: "POST", body: body)
        do { return try JSONDecoder().decode(HBTag.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func deleteTag(id: String) async throws {
        _ = try await request("v1/tags/\(id)", method: "DELETE")
    }

    func updateTag(id: String, name: String, description: String = "", color: String = "") async throws {
        let body = try JSONEncoder().encode(TagCreatePayload(name: name, description: description, color: color))
        _ = try await request("v1/tags/\(id)", method: "PUT", body: body)
    }

    // MARK: Attachment fetch

    /// Fetch attachment bytes for an item attachment. Uses the standard bearer token.
    func attachmentData(itemId: String, attachmentId: String) async throws -> Data {
        return try await request("v1/entities/\(itemId)/attachments/\(attachmentId)", method: "GET")
    }

    /// Delete an attachment from an item.
    func deleteAttachment(itemId: String, attachmentId: String) async throws {
        _ = try await request("v1/entities/\(itemId)/attachments/\(attachmentId)", method: "DELETE")
    }

    // MARK: Barcode / Asset

    /// `GET /v1/products/search-from-barcode?data=<code>`
    func searchFromBarcode(data: String) async throws -> [HBBarcodeProduct] {
        let d = try await request("v1/products/search-from-barcode", method: "GET",
                                  query: [URLQueryItem(name: "data", value: data)])
        do { return try JSONDecoder().decode([HBBarcodeProduct].self, from: d) }
        catch { throw HBError.decode(error) }
    }

    // MARK: External barcode lookup

    /// Open Food Facts — free, no key, best for food/grocery/household consumables.
    static func lookupOpenFoodFacts(barcode: String) async -> HBBarcodeProduct? {
        guard let url = URL(string: "https://world.openfoodfacts.org/api/v0/product/\(barcode).json") else { return nil }
        var req = URLRequest(url: url)
        req.setValue("HomeBoy-iOS/1.0 barcode-lookup", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: req) else { return nil }

        struct OFFResponse: Decodable {
            let status: Int
            let product: OFFProduct?
            struct OFFProduct: Decodable {
                let product_name: String?
                let brands: String?
                let generic_name: String?
            }
        }

        guard let r = try? JSONDecoder().decode(OFFResponse.self, from: data),
              r.status == 1,
              let p = r.product else { return nil }

        let name = p.product_name.flatMap { $0.trimmingCharacters(in: .whitespaces).nilIfEmpty }
        guard let name else { return nil }
        let desc = p.generic_name.flatMap { $0.trimmingCharacters(in: .whitespaces).nilIfEmpty }
        let brand = p.brands.flatMap { $0.trimmingCharacters(in: .whitespaces).nilIfEmpty }

        return HBBarcodeProduct(
            barcode: barcode, imageBase64: nil, imageURL: nil,
            item: HBBarcodeItem(name: name, quantity: nil, description: desc),
            manufacturer: brand, modelNumber: nil, notes: nil,
            searchEngineName: "Open Food Facts"
        )
    }

    /// UPC Item DB — free trial tier (100 lookups/day), good for general products.
    static func lookupUPCItemDB(barcode: String) async -> HBBarcodeProduct? {
        guard let url = URL(string: "https://api.upcitemdb.com/prod/trial/lookup?upc=\(barcode)") else { return nil }
        guard let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }

        struct UPCResponse: Decodable {
            let code: String?
            let items: [UPCItem]?
            struct UPCItem: Decodable {
                let title: String?
                let brand: String?
                let description: String?
            }
        }

        guard let r = try? JSONDecoder().decode(UPCResponse.self, from: data),
              let first = r.items?.first,
              let rawName = first.title,
              let name = rawName.trimmingCharacters(in: .whitespaces).nilIfEmpty
        else { return nil }

        return HBBarcodeProduct(
            barcode: barcode, imageBase64: nil, imageURL: nil,
            item: HBBarcodeItem(name: name, quantity: nil, description: first.description.flatMap { $0.nilIfEmpty }),
            manufacturer: first.brand.flatMap { $0.nilIfEmpty },
            modelNumber: nil, notes: nil,
            searchEngineName: "UPC Item DB"
        )
    }

    // MARK: Maintenance

    func listMaintenance(itemId: String) async throws -> [HBMaintenanceEntry] {
        let data = try await request("v1/entities/\(itemId)/maintenance", method: "GET")
        do { return try JSONDecoder().decode([HBMaintenanceEntry].self, from: data) }
        catch { throw HBError.decode(error) }
    }

    @discardableResult
    func createMaintenance(itemId: String, entry: HBMaintenanceCreate) async throws -> HBMaintenanceEntry {
        let body = try JSONEncoder().encode(entry)
        let data = try await request("v1/entities/\(itemId)/maintenance", method: "POST", body: body)
        do { return try JSONDecoder().decode(HBMaintenanceEntry.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func updateMaintenance(id: String, entry: HBMaintenanceCreate) async throws {
        let body = try JSONEncoder().encode(entry)
        _ = try await request("v1/maintenance/\(id)", method: "PUT", body: body)
    }

    func deleteMaintenance(id: String) async throws {
        _ = try await request("v1/maintenance/\(id)", method: "DELETE")
    }

    /// `GET /v1/assets/{numericId}` — look up an item by its asset ID string (e.g. "000-001").
    /// Strips non-digit characters before calling the endpoint.
    func getAsset(assetId: String) async throws -> HBItem? {
        let numeric = assetId.filter(\.isNumber)
        guard !numeric.isEmpty else { return nil }
        let d = try await request("v1/assets/\(numeric)", method: "GET")
        do { return try JSONDecoder().decode(HBItemListResponse.self, from: d).items.first }
        catch { throw HBError.decode(error) }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

// MARK: - Auth recovery

extension Notification.Name {
    /// Posted with userInfo["token"] after an expired token was silently replaced,
    /// so HomeboxStore can adopt the new token for future `store.client` values.
    static let authTokenRecovered = Notification.Name("homebox.authTokenRecovered")
}

/// Serializes token recovery so parallel 401s trigger a single refresh/re-login
/// instead of a stampede. Keychain keys mirror HomeboxStore's.
actor AuthRecovery {
    static let shared = AuthRecovery()

    static let tokenKey    = "homebox.token"
    static let usernameKey = "homebox.login.username"
    static let passwordKey = "homebox.login.password"

    /// Returns a working token after the given one got a 401, or nil when
    /// recovery is impossible (no saved credentials / password changed).
    func recover(serverURL: URL, failedToken: String) async -> String? {
        // Another request already recovered while we waited for the actor.
        if let current = Keychain.get(Self.tokenKey), !current.isEmpty, current != failedToken {
            return current
        }

        // 1) Cheap path: exchange the (possibly still-valid) token for a fresh one.
        var newToken = await refresh(serverURL: serverURL, token: failedToken)

        // 2) Token fully expired: silent re-login with the saved credentials.
        if newToken == nil,
           let username = Keychain.get(Self.usernameKey), !username.isEmpty,
           let password = Keychain.get(Self.passwordKey), !password.isEmpty {
            let resp = try? await HomeboxClient.login(serverURL: serverURL,
                                                      username: username, password: password)
            if let t = resp?.token, !t.isEmpty { newToken = t }
        }

        guard let newToken else { return nil }
        Keychain.set(newToken, key: Self.tokenKey)
        NotificationCenter.default.post(name: .authTokenRecovered, object: nil,
                                        userInfo: ["token": newToken])
        return newToken
    }

    /// `GET /api/v1/users/refresh` — raw URLSession so it can never recurse into
    /// the client's own 401 handling.
    private func refresh(serverURL: URL, token: String) async -> String? {
        let url = serverURL.appendingPathComponent("api")
            .appendingPathComponent("v1/users/refresh")
        var req = URLRequest(url: url)
        req.setValue(token, forHTTPHeaderField: "Authorization")
        guard let (data, resp) = try? await URLSession.shared.data(for: req),
              let http = resp as? HTTPURLResponse,
              (200...299).contains(http.statusCode),
              let decoded = try? JSONDecoder().decode(HBLoginResponse.self, from: data),
              !decoded.token.isEmpty
        else { return nil }
        return decoded.token
    }
}
