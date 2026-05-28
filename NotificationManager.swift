import Foundation
import UserNotifications

// MARK: - Maintenance cadence

enum MaintenanceCadence: String, CaseIterable, Identifiable {
    case never       = "never"
    case weekly      = "weekly"
    case biweekly    = "biweekly"
    case monthly     = "monthly"
    case quarterly   = "quarterly"
    case biannually  = "biannually"
    case yearly      = "yearly"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .never:      return "One-time"
        case .weekly:     return "Weekly"
        case .biweekly:   return "Every 2 Weeks"
        case .monthly:    return "Monthly"
        case .quarterly:  return "Every 3 Months"
        case .biannually: return "Every 6 Months"
        case .yearly:     return "Yearly"
        }
    }

    /// Returns the date of the next occurrence, or nil for one-time.
    func nextDate(from date: Date) -> Date? {
        var comps = DateComponents()
        switch self {
        case .never:      return nil
        case .weekly:     comps.weekOfYear = 1
        case .biweekly:   comps.weekOfYear = 2
        case .monthly:    comps.month = 1
        case .quarterly:  comps.month = 3
        case .biannually: comps.month = 6
        case .yearly:     comps.year = 1
        }
        return Calendar.current.date(byAdding: comps, to: date)
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
        if cadence == .never { dict.removeValue(forKey: entryId) }
        else                 { dict[entryId] = cadence.rawValue }
        UserDefaults.standard.set(dict, forKey: cadenceKey)
    }

    func cadence(for entryId: String) -> MaintenanceCadence {
        MaintenanceCadence(rawValue: cadenceDict()[entryId] ?? "") ?? .never
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
            // Tap on banner body — open the app; nothing special to navigate yet
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
        let cadence  = MaintenanceCadence(rawValue: info["cadence"] as? String ?? "") ?? .never

        Task {
            let doneEntry = HBMaintenanceCreate(
                name: entryName, description: desc,
                date: iso.string(from: Date()), scheduledDate: schedStr, cost: 0
            )
            try? await client.updateMaintenance(id: entryId, entry: doneEntry)

            // Schedule next occurrence for repeating entries
            if cadence != .never,
               let scheduledDate = parseISO(schedStr),
               let nextDate      = cadence.nextDate(from: scheduledDate),
               let itemId        = info["itemId"]   as? String,
               let itemName      = info["itemName"] as? String
            {
                let nextEntry = HBMaintenanceCreate(
                    name: entryName, description: desc,
                    date: "", scheduledDate: iso.string(from: nextDate), cost: 0
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
}
