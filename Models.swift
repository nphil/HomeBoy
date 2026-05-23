import Foundation
import SwiftUI

struct CatalogItem: Identifiable, Codable, Equatable {
    var id: UUID = UUID()
    var name: String
    var quantity: Int
    var location1: String
    var location2: String
    var location3: String
    var details: String
    var labels: String   // semicolon-separated, matches Homebox HB.labels
    var createdAt: Date = Date()

    var locationPath: String {
        [location1, location2, location3]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " / ")
    }
}

@MainActor
final class CatalogStore: ObservableObject {
    @Published private(set) var items: [CatalogItem] = []
    @Published var locks: [Bool] = [false, false, false] {
        didSet { saveLocks() }
    }

    private let itemsFile: URL = {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return dir.appendingPathComponent("catalog-items.json")
    }()
    private let locksKey = "homebox.locks"

    init() {
        load()
        if let saved = UserDefaults.standard.array(forKey: locksKey) as? [Bool], saved.count == 3 {
            locks = saved
        }
    }

    func add(_ item: CatalogItem) {
        items.append(item)
        save()
    }

    func update(_ item: CatalogItem) {
        guard let i = items.firstIndex(where: { $0.id == item.id }) else { return }
        items[i] = item
        save()
    }

    func delete(_ item: CatalogItem) {
        items.removeAll { $0.id == item.id }
        save()
    }

    func delete(at offsets: IndexSet) {
        items.remove(atOffsets: offsets)
        save()
    }

    func clearAll() {
        items.removeAll()
        save()
    }

    /// Returns previously-used values for a given location level (0 = L1, 1 = L2, 2 = L3),
    /// most-recent first, deduplicated.
    func recentLocations(level: Int) -> [String] {
        var seen = Set<String>()
        var result: [String] = []
        for item in items.reversed() {
            let v: String
            switch level {
            case 0: v = item.location1
            case 1: v = item.location2
            case 2: v = item.location3
            default: return []
            }
            let trimmed = v.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty, !seen.contains(trimmed) {
                seen.insert(trimmed)
                result.append(trimmed)
            }
        }
        return result
    }

    // MARK: - Persistence

    private func save() {
        do {
            let data = try JSONEncoder().encode(items)
            try data.write(to: itemsFile, options: .atomic)
        } catch {
            print("CatalogStore save error: \(error)")
        }
    }

    private func load() {
        guard FileManager.default.fileExists(atPath: itemsFile.path) else { return }
        do {
            let data = try Data(contentsOf: itemsFile)
            items = try JSONDecoder().decode([CatalogItem].self, from: data)
        } catch {
            print("CatalogStore load error: \(error)")
        }
    }

    private func saveLocks() {
        UserDefaults.standard.set(locks, forKey: locksKey)
    }
}
