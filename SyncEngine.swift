import Foundation
import Network

/// Monitors network reachability and fires a callback when connectivity changes.
@MainActor
final class SyncEngine {

    private(set) var isConnected: Bool = true

    /// Called on the main actor whenever connectivity transitions.
    /// `true` = just came online; `false` = just went offline.
    var onConnectionChange: ((Bool) -> Void)?

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "homebox.netmonitor", qos: .utility)

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let connected = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                guard self.isConnected != connected else { return }
                self.isConnected = connected
                self.onConnectionChange?(connected)
            }
        }
        monitor.start(queue: monitorQueue)
    }

    deinit { monitor.cancel() }
}
