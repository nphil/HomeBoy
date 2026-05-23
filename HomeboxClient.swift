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

    /// `POST /v1/items` — create an item under a location.
    func createItem(_ payload: HBItemCreate) async throws {
        let body = try JSONEncoder().encode(payload)
        _ = try await request("v1/items", method: "POST", body: body)
    }
}
