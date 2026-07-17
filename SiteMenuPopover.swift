import SwiftUI

// MARK: - Notification names

extension Notification.Name {
    static let showSettings = Notification.Name("showSettings")
    static let showToast    = Notification.Name("showToast")
    /// Posted by HomeboxStore after queued offline changes reach the server,
    /// so open views can reload fresh data.
    static let offlineSyncCompleted = Notification.Name("offlineSyncCompleted")
}
