import Foundation

enum CSVExporter {
    static let headers = [
        "HB.name",
        "HB.quantity",
        "HB.location",
        "HB.description",
        "HB.labels",
    ]

    /// Writes a Homebox-format CSV of the given items into the app's tmp directory
    /// and returns the URL — suitable to hand off to a share sheet.
    static func write(items: [CatalogItem]) -> URL? {
        let csv = render(items: items)
        let stamp = Self.dateFormatter.string(from: Date())
        let filename = "homebox-import-\(stamp).csv"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        do {
            try csv.data(using: .utf8)?.write(to: url, options: .atomic)
            return url
        } catch {
            print("CSV write error: \(error)")
            return nil
        }
    }

    static func render(items: [CatalogItem]) -> String {
        var rows: [String] = [headers.joined(separator: ",")]
        for item in items {
            let row = [
                escape(item.name),
                escape(String(item.quantity)),
                escape(item.locationPath),
                escape(item.details),
                escape(item.labels),
            ].joined(separator: ",")
            rows.append(row)
        }
        return rows.joined(separator: "\r\n")
    }

    /// RFC 4180 escaping — wrap in quotes if value contains comma, quote, CR, or LF.
    private static func escape(_ value: String) -> String {
        let needs = value.contains(",") || value.contains("\"") || value.contains("\r") || value.contains("\n")
        if needs {
            return "\"" + value.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return value
    }

    private static let dateFormatter: DateFormatter = {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd-HHmm"
        df.locale = Locale(identifier: "en_US_POSIX")
        return df
    }()
}
