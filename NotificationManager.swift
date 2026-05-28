import Foundation
import UserNotifications

// MARK: - Cadence unit

enum CadenceUnit: String, CaseIterable, Identifiable {
    case hour  = "hour"
    case day   = "day"
    case week  = "week"
    case month = "month"
    case year  = "year"

    var id: String { rawValue }

    func label(count: Int) -> String {
        let s: String
        switch self {
        case .hour:  s = "hour"
        case .day:   s = "day"
        case .week:  s = "week"
        case .month: s = "month"
        case .year:  s = "year"
        }
        return count == 1 ? s : s + "s"
    }
}

// MARK: - Maintenance cadence

struct MaintenanceCadence: Equatable, Identifiable {
    var value: Int         // 0 = one-time (no recurrence)
    var unit: CadenceUnit

    var id: String { rawValue }

    static let oneTime = MaintenanceCadence(value: 0, unit: .month)

    static let presets: [MaintenanceCadence] = [
        .oneTime,
        MaintenanceCadence(value: 1, unit: .week),
        MaintenanceCadence(value: 2, unit: .week),
        MaintenanceCadence(value: 1, unit: .month),
        MaintenanceCadence(value: 3, unit: .month),
        MaintenanceCadence(value: 6, unit: .month),
        MaintenanceCadence(value: 1, unit: .year),
    ]

    var isOneTime: Bool { value == 0 }

    var rawValue: String { isOneTime ? "0" : "\(value):\(unit.rawValue)" }

    init(value: Int = 0, unit: CadenceUnit = .month) {
        self.value = value; self.unit = unit
    }

    init?(rawValue: String) {
        // Legacy enum format (before the "every X Y" redesign)
        switch rawValue {
        case "0", "never":  self.value = 0; self.unit = .month
        case "weekly":      self.value = 1; self.unit = .week
        case "biweekly":    self.value = 2; self.unit = .week
        case "monthly":     self.value = 1; self.unit = .month
        case "quarterly":   self.value = 3; self.unit = .month
        case "biannually":  self.value = 6; self.unit = .month
        case "yearly":      self.value = 1; self.unit = .year
        default:
            let parts = rawValue.split(separator: ":").map(String.init)
            guard parts.count == 2,
                  let v = Int(parts[0]),
                  let u = CadenceUnit(rawValue: parts[1]) else { return nil }
            self.value = v; self.unit = u
        }
    }

    func nextDate(from date: Date) -> Date? {
        guard value > 0 else { return nil }
        var comps = DateComponents()
        switch unit {
        case .hour:  comps.hour       = value
        case .day:   comps.day        = value
        case .week:  comps.weekOfYear = value
        case .month: comps.month      = value
        case .year:  comps.year       = value
        }
        return Calendar.current.date(byAdding: comps, to: date)
    }

    var displayLabel: String {
        guard value > 0 else { return "One-time" }
        return "Every \(value) \(unit.label(count: value))"
    }
}

// MARK: - NotificationManager

/// Singleton UNUserNotificationCenterDelegate.
/// Set as delegate in HomeboxCatalogApp.init() before any notifications arrive.
final class NotificationManager: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationManager()
    private override init() { super.init() }

    // MARK: Setup

    func registerCategories() {
        let markDone = UNNotificationAction(
            identifier: "MARK_DONE",
            title: "Mark as Done",
            options: [.foreground]
        )
        let category = UNNotificationCategory(
            identifier: "MAINTENANCE",
            actions: [markDone],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    func requestPermission() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    // MARK: Schedule / cancel

    func schedule(
        entryId: String,
        entryName: String,
        entryDescription: String,
        itemId: String,
        itemName: String,
        date: Date,
        cadence: MaintenanceCadence
    ) {
        let content = UNMutableNotificationContent()
        content.title = "Maintenance Due"
        content.body = "\(itemName) · \(entryName)"
        if !entryDescription.isEmpty { content.subtitle = entryDescription }
        content.sound = .default
        content.categoryIdentifier = "MAINTENANCE"
        content.userInfo = [
            "entryId": entryId,
            "entryName": entryName,
            "entryDescription": entryDescription,
            "itemId": itemId,
            "itemName": itemName,
            "scheduledDate": iso.string(from: date),
            "cadence": cadence.rawValue
        ]

        var comps = Calendar.current.dateComponents([.year, .month, .day], from: date)
        comps.hour = 9; comps.minute = 0

        let trigger = UNCalendarNotificationTrigger(dateMatching: comps, repeats: false)
        let request = UNNotificationRequest(identifier: notifId(entryId), content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request)

        saveCadence(cadence, for: entryId)
    }

    func cancel(entryId: String) {
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: [notifId(entryId)])
        removeCadence(for: entryId)
    }

    private func notifId(_ entryId: String) -> String { "maint-\(entryId)" }

    // MARK: Cadence persistence

    private let cadenceKey = "homebox.maint.cadence"

    func saveCadence(_ cadence: MaintenanceCadence, for entryId: String) {
        var dict = cadenceDict()
        if cadence.isOneTime { dict.removeValue(forKey: entryId) }
        else                 { dict[entryId] = cadence.rawValue }
        UserDefaults.standard.set(dict, forKey: cadenceKey)
    }

    func cadence(for entryId: String) -> MaintenanceCadence {
        guard let raw = cadenceDict()[entryId] else { return .oneTime }
        return MaintenanceCadence(rawValue: raw) ?? .oneTime
    }

    private func removeCadence(for entryId: String) {
        var dict = cadenceDict()
        dict.removeValue(forKey: entryId)
        UserDefaults.standard.set(dict, forKey: cadenceKey)
    }

    private func cadenceDict() -> [String: String] {
        (UserDefaults.standard.dictionary(forKey: cadenceKey) as? [String: String]) ?? [:]
    }

    // MARK: Delegate — foreground presentation

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    // MARK: Delegate — user response

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo

        if response.actionIdentifier == "MARK_DONE" {
            handleMarkDone(info: info, completionHandler: completionHandler)
        } else {
            completionHandler()
        }
    }

    private func handleMarkDone(info: [AnyHashable: Any], completionHandler: @escaping () -> Void) {
        guard
            let entryId     = info["entryId"]     as? String,
            let entryName   = info["entryName"]   as? String,
            let urlString   = UserDefaults.standard.string(forKey: "homebox.serverURL"),
            let serverURL   = URL(string: urlString.contains("://") ? urlString : "https://\(urlString)"),
            let token       = Keychain.get("homebox.token")
        else { completionHandler(); return }

        let groupId  = UserDefaults.standard.string(forKey: "homebox.activeGroupId")
        let client   = HomeboxClient(serverURL: serverURL, token: token, tenantId: groupId)
        let desc     = info["entryDescription"] as? String ?? ""
        let schedStr = info["scheduledDate"]    as? String ?? ""
        let cadence  = MaintenanceCadence(rawValue: info["cadence"] as? String ?? "") ?? .oneTime

        Task {
            let doneEntry = HBMaintenanceCreate(
                name: entryName, description: desc,
                completedDate: dateOnly(Date()), scheduledDate: schedStr, cost: 0
            )
            try? await client.updateMaintenance(id: entryId, entry: doneEntry)

            if !cadence.isOneTime,
               let scheduledDate = parseISO(schedStr),
               let nextDate      = cadence.nextDate(from: scheduledDate),
               let itemId        = info["itemId"]   as? String,
               let itemName      = info["itemName"] as? String
            {
                let nextEntry = HBMaintenanceCreate(
                    name: entryName, description: desc,
                    completedDate: nil, scheduledDate: dateOnly(nextDate), cost: 0
                )
                if let created = try? await client.createMaintenance(itemId: itemId, entry: nextEntry) {
                    self.schedule(
                        entryId: created.id, entryName: entryName, entryDescription: desc,
                        itemId: itemId, itemName: itemName, date: nextDate, cadence: cadence
                    )
                }
            }

            completionHandler()
        }
    }

    // MARK: Helpers

    private let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private func parseISO(_ s: String) -> Date? {
        if let d = iso.date(from: s) { return d }
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: s)
    }

    private func dateOnly(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
    }
}
