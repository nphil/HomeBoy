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
struct HBItem: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
    let quantity: Double?
    let archived: Bool?
    let createdAt: String?
    let location: HBLocationSummary?
    let labels: [HBTag]?

    var quantityInt: Int { Int(quantity ?? 1) }
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
    let locationId: String
    /// `parentId` is for nesting items under other items; we don't use it.
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
    let name: String
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
    var purchaseTime: String?
    var lifetimeWarranty: Bool?
    var warrantyExpires: String?
    var warrantyDetails: String?
    var soldTo: String?
    var soldPrice: Double?
    var soldTime: String?
    var soldNotes: String?
    var syncChildItemsLocations: Bool?
    var createdAt: String?
    var updatedAt: String?
    var location: HBLocationSummary?
    var tags: [HBTag]?
    var attachments: [HBAttachmentRef]?

    var quantityInt: Int { Int(quantity ?? 1) }
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
    var locationId: String
    var tagIds: [String]
    var serialNumber: String
    var modelNumber: String
    var manufacturer: String
    var lifetimeWarranty: Bool
    var warrantyExpires: String
    var warrantyDetails: String
    var purchaseTime: String
    var purchaseFrom: String
    var purchasePrice: Double
    var soldTime: String
    var soldTo: String
    var soldPrice: Double
    var soldNotes: String
    var notes: String
    var syncChildItemsLocations: Bool
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
        self.locationId = overrideLocationId ?? d.location?.id ?? ""
        self.tagIds = overrideTagIds ?? (d.tags?.map { $0.id } ?? [])
        self.serialNumber = d.serialNumber ?? ""
        self.modelNumber = d.modelNumber ?? ""
        self.manufacturer = d.manufacturer ?? ""
        self.lifetimeWarranty = d.lifetimeWarranty ?? false
        self.warrantyExpires = d.warrantyExpires ?? ""
        self.warrantyDetails = d.warrantyDetails ?? ""
        self.purchaseTime = d.purchaseTime ?? ""
        self.purchaseFrom = d.purchaseFrom ?? ""
        self.purchasePrice = d.purchasePrice ?? 0
        self.soldTime = d.soldTime ?? ""
        self.soldTo = d.soldTo ?? ""
        self.soldPrice = d.soldPrice ?? 0
        self.soldNotes = d.soldNotes ?? ""
        self.notes = d.notes ?? ""
        self.syncChildItemsLocations = d.syncChildItemsLocations ?? false
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

/// Stateless HTTP client. Caller holds the server URL + token.
struct HomeboxClient {
    var serverURL: URL
    var token: String?

    private func url(_ path: String, query: [URLQueryItem] = []) throws -> URL {
        var comps = URLComponents(url: serverURL.appendingPathComponent("api").appendingPathComponent(path), resolvingAgainstBaseURL: false)
        if !query.isEmpty { comps?.queryItems = query }
        guard let u = comps?.url else { throw HBError.badURL }
        return u
    }

    private func request(_ path: String, method: String, body: Data? = nil, contentType: String = "application/json", query: [URLQueryItem] = []) async throws -> Data {
        var req = URLRequest(url: try url(path, query: query))
        req.httpMethod = method
        req.httpBody = body
        if body != nil { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let token { req.setValue(token, forHTTPHeaderField: "Authorization") }

        let (data, resp): (Data, URLResponse)
        do {
            (data, resp) = try await URLSession.shared.data(for: req)
        } catch {
            throw HBError.transport(error)
        }
        guard let http = resp as? HTTPURLResponse else { throw HBError.http(-1, "No HTTP response") }
        if http.statusCode == 401 || http.statusCode == 403 { throw HBError.unauthorized }
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

    /// `GET /v1/locations` — flat list (no parent info).
    func listLocations() async throws -> [HBLocation] {
        let data = try await request("v1/locations", method: "GET")
        do { return try JSONDecoder().decode([HBLocation].self, from: data) }
        catch { throw HBError.decode(error) }
    }

    /// `GET /v1/locations/tree?withItems=false` — nested tree for the picker.
    func locationTree() async throws -> [HBTreeItem] {
        let data = try await request(
            "v1/locations/tree",
            method: "GET",
            query: [URLQueryItem(name: "withItems", value: "false")]
        )
        do { return try JSONDecoder().decode([HBTreeItem].self, from: data) }
        catch { throw HBError.decode(error) }
    }

    // MARK: Items

    /// `GET /v1/items` — paginated list of items, optionally filtered.
    func listItems(query: String? = nil, locationIds: [String] = [], page: Int = 1, pageSize: Int = 500) async throws -> HBItemListResponse {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "pageSize", value: String(pageSize)),
        ]
        if let query, !query.isEmpty { items.append(URLQueryItem(name: "q", value: query)) }
        for id in locationIds { items.append(URLQueryItem(name: "locations", value: id)) }
        let data = try await request("v1/items", method: "GET", query: items)
        do { return try JSONDecoder().decode(HBItemListResponse.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    /// `POST /v1/items` — create an item under a location. Returns the new item's id.
    @discardableResult
    func createItem(_ payload: HBItemCreate) async throws -> String {
        let body = try JSONEncoder().encode(payload)
        let data = try await request("v1/items", method: "POST", body: body)
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
            "v1/items/\(itemId)/attachments",
            method: "POST",
            body: body,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
    }

    /// `POST /v1/locations` — create a location, optionally under a parent.
    @discardableResult
    func createLocation(name: String, parentId: String?, description: String = "") async throws -> String {
        let payload = HBLocationCreate(name: name, parentId: parentId, description: description)
        let body = try JSONEncoder().encode(payload)
        let data = try await request("v1/locations", method: "POST", body: body)
        do { return try JSONDecoder().decode(HBLocationCreateResponse.self, from: data).id }
        catch { throw HBError.decode(error) }
    }

    // MARK: Item detail / edit / delete

    func getItem(id: String) async throws -> HBItemDetail {
        let data = try await request("v1/items/\(id)", method: "GET")
        do { return try JSONDecoder().decode(HBItemDetail.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func updateItem(_ payload: HBItemUpdate) async throws {
        let body = try JSONEncoder().encode(payload)
        _ = try await request("v1/items/\(payload.id)", method: "PUT", body: body)
    }

    func deleteItem(id: String) async throws {
        _ = try await request("v1/items/\(id)", method: "DELETE")
    }

    // MARK: Location detail / edit / delete

    func getLocation(id: String) async throws -> HBLocationDetail {
        let data = try await request("v1/locations/\(id)", method: "GET")
        do { return try JSONDecoder().decode(HBLocationDetail.self, from: data) }
        catch { throw HBError.decode(error) }
    }

    func updateLocation(_ payload: HBLocationUpdate) async throws {
        let body = try JSONEncoder().encode(payload)
        _ = try await request("v1/locations/\(payload.id)", method: "PUT", body: body)
    }

    func deleteLocation(id: String) async throws {
        _ = try await request("v1/locations/\(id)", method: "DELETE")
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

    // MARK: Attachment fetch

    /// Fetch attachment bytes for an item attachment. Uses the standard bearer token.
    func attachmentData(itemId: String, attachmentId: String) async throws -> Data {
        return try await request("v1/items/\(itemId)/attachments/\(attachmentId)", method: "GET")
    }
}
