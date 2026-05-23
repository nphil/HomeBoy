import Foundation

// MARK: - Models

struct HBLoginResponse: Codable {
    let token: String
    let expiresAt: String?
    let attachmentToken: String?
}

struct HBEntityType: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let isLocation: Bool
    let description: String?
    let icon: String?
}

/// Decodes the minimum we need from /v1/entities. The full schema is much wider,
/// but we keep this lean and rely on `parent` being a recursive entity.
struct HBEntity: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let description: String?
    let quantity: Double?
    let archived: Bool?
    let createdAt: String?
    let entityType: HBEntityType?
    let parent: HBParentRef?

    var quantityInt: Int { Int(quantity ?? 1) }
    var isLocation: Bool { entityType?.isLocation == true }
}

/// `parent` in API responses is itself a nested entity (recursive). We only keep
/// id/name and the grand-parent reference — enough to render a breadcrumb.
final class HBParentRef: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let parent: HBParentRef?

    init(id: String, name: String, parent: HBParentRef? = nil) {
        self.id = id
        self.name = name
        self.parent = parent
    }

    static func == (lhs: HBParentRef, rhs: HBParentRef) -> Bool { lhs.id == rhs.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }

    /// Builds a "A / B / C" breadcrumb, ancestors first.
    var pathString: String {
        var parts: [String] = []
        var cur: HBParentRef? = self
        while let p = cur {
            parts.insert(p.name, at: 0)
            cur = p.parent
        }
        return parts.joined(separator: " / ")
    }
}

struct HBEntityListResponse: Codable {
    let items: [HBEntity]
    let page: Int?
    let pageSize: Int?
    let total: Int?
}

struct HBCreateEntityRequest: Codable {
    let name: String
    let entityTypeId: String
    let parentId: String?
    let quantity: Double?
    let description: String?
}

// MARK: - Client

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

/// Stateless HTTP client. All credentials are passed in by the caller (HomeboxStore).
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
        do {
            return try JSONDecoder().decode(HBLoginResponse.self, from: data)
        } catch {
            throw HBError.decode(error)
        }
    }

    // MARK: Entity types

    func entityTypes() async throws -> [HBEntityType] {
        let data = try await request("v1/entity-types", method: "GET")
        do {
            return try JSONDecoder().decode([HBEntityType].self, from: data)
        } catch {
            throw HBError.decode(error)
        }
    }

    // MARK: Entities (items + locations)

    /// Fetch a page of entities. Caller filters by `entityType.isLocation` for items vs locations.
    func entities(query: String? = nil, parentIds: [String] = [], page: Int = 1, pageSize: Int = 500) async throws -> HBEntityListResponse {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "page", value: String(page)),
            URLQueryItem(name: "pageSize", value: String(pageSize)),
        ]
        if let query, !query.isEmpty { items.append(URLQueryItem(name: "q", value: query)) }
        for pid in parentIds { items.append(URLQueryItem(name: "parentIds", value: pid)) }
        let data = try await request("v1/entities", method: "GET", query: items)
        do {
            return try JSONDecoder().decode(HBEntityListResponse.self, from: data)
        } catch {
            throw HBError.decode(error)
        }
    }

    func createEntity(_ payload: HBCreateEntityRequest) async throws -> HBEntity {
        let body = try JSONEncoder().encode(payload)
        let data = try await request("v1/entities", method: "POST", body: body)
        do {
            return try JSONDecoder().decode(HBEntity.self, from: data)
        } catch {
            throw HBError.decode(error)
        }
    }
}
