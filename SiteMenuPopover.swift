import SwiftUI

/// A snappy, springy animation used for both the popover open/close and the
/// dismiss-on-background-tap. Quick (≈0.25 s) with a tiny bounce so it feels
/// alive but never overshoots into jitter.
private let popoverSpring: Animation = .spring(duration: 0.25, bounce: 0.22)

struct SiteMenuPopover: View {
    @EnvironmentObject var store: HomeboxStore
    @EnvironmentObject var theme: ThemeManager
    @Binding var isPresented: Bool
    @Binding var globalSearchQuery: String

    @State private var isSwitching = false

    var body: some View {
        ZStack(alignment: .top) {
            if isPresented {
                // Dimmed background — tap anywhere to dismiss
                Color.black.opacity(0.35)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(popoverSpring) { isPresented = false }
                    }
                    .transition(.opacity)

                // Popover card — zooms out from the top-leading chevron button
                VStack(spacing: 10) {
                    if store.groups.isEmpty {
                        placeholderCard
                    } else {
                        ForEach(store.groups) { group in
                            groupCard(group)
                        }
                    }

                    // Settings button
                    Button {
                        withAnimation(popoverSpring) { isPresented = false }
                        NotificationCenter.default.post(name: .showSettings, object: nil)
                    } label: {
                        HStack {
                            Spacer()
                            Image(systemName: "gear")
                                .foregroundStyle(.white)
                            Text("Settings")
                                .font(.headline)
                                .foregroundStyle(.white)
                            Spacer()
                        }
                        .padding()
                        .background(theme.current.accentColor)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                }
                .padding(16)
                .background(theme.current.backgroundColor)
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .shadow(color: .black.opacity(0.2), radius: 20, y: 10)
                .padding(.horizontal, 16)
                .padding(.top, 60)
                // Pops out from the chevron — scale starts at 50% (not 1%) so it
                // feels like a card unfolding, not a wild zoom from infinity.
                .transition(
                    .scale(scale: 0.5, anchor: .topLeading)
                    .combined(with: .opacity)
                )
            }
        }
        .onChange(of: isPresented) { _, presented in
            // When the popover opens, refresh per-group stats so the cached
            // counts on each card are up to date.
            if presented {
                Task { await store.refreshAllGroupStats() }
            }
        }
    }

    // MARK: - Group card

    @ViewBuilder
    private func groupCard(_ group: HBGroup) -> some View {
        let isActive = group.id == store.activeGroupId
        // Active group uses live store values; inactive groups use cached stats
        // fetched per-group via the X-Tenant header.
        let locCount: Int = isActive
            ? store.locationsFlat.count
            : (store.cachedGroupStats[group.id]?.locationCount ?? 0)
        let itemCount: Int = isActive
            ? (store.cachedItemTotal ?? 0)
            : (store.cachedGroupStats[group.id]?.itemTotal ?? 0)

        Button {
            guard !isActive, !isSwitching else { return }
            Task { await switchTo(group) }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Image(systemName: isActive ? "cube.fill" : "cube")
                            .foregroundStyle(theme.current.accentColor)
                            .font(.subheadline)
                        Text(group.name)
                            .font(.headline)
                            .foregroundStyle(.primary)
                    }
                    HStack(spacing: 12) {
                        Label("\(locCount)", systemImage: "mappin.and.ellipse")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Label("\(itemCount)", systemImage: "shippingbox")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                if isSwitching && !isActive {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: isActive ? "checkmark.circle.fill" : "circle")
                        .foregroundStyle(isActive ? theme.current.accentColor : Color.secondary.opacity(0.4))
                        .font(.title3)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(isActive ? theme.current.accentColor.opacity(0.12) : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(
                        isActive ? theme.current.accentColor : theme.current.accentColor.opacity(0.25),
                        lineWidth: isActive ? 2.5 : 1
                    )
            )
        }
        .buttonStyle(.plain)
        .disabled(isSwitching)
    }

    // MARK: - Placeholder (groups still loading)

    @ViewBuilder
    private var placeholderCard: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Image(systemName: "cube.fill")
                        .foregroundStyle(theme.current.accentColor)
                        .font(.subheadline)
                    Text(store.groupName ?? "HomeBoy")
                        .font(.headline)
                        .foregroundStyle(.primary)
                }
                HStack(spacing: 12) {
                    Label("\(store.locationsFlat.count)", systemImage: "mappin.and.ellipse")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Label("\(store.cachedItemTotal ?? 0)", systemImage: "shippingbox")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            ProgressView()
                .controlSize(.small)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(theme.current.accentColor.opacity(0.12))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(theme.current.accentColor, lineWidth: 2.5)
        )
    }

    // MARK: - Switch

    private func switchTo(_ group: HBGroup) async {
        isSwitching = true
        await store.setActiveGroup(group)
        isSwitching = false
        withAnimation(popoverSpring) { isPresented = false }
        NotificationCenter.default.post(
            name: .showToast,
            object: nil,
            userInfo: ["message": "Switched to \(group.name)"]
        )
    }
}

// MARK: - Notification names

extension Notification.Name {
    static let showSettings = Notification.Name("showSettings")
    static let showToast    = Notification.Name("showToast")
}
