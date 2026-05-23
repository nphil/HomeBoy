import SwiftUI

/// A common card shape — Liquid Glass background with theme tint.
struct GlassCard<Content: View>: View {
    @EnvironmentObject var theme: ThemeManager
    var title: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let title {
                Text(title.uppercased())
                    .font(.caption.weight(.semibold))
                    .tracking(0.6)
                    .foregroundStyle(theme.current.accentColor.opacity(0.75))
            }
            content
        }
        .padding(16)
        .background {
            RoundedRectangle(cornerRadius: 18).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 18).fill(theme.current.accentColor.opacity(0.08))
        }
        .overlay(
            RoundedRectangle(cornerRadius: 18).stroke(theme.current.accentColor.opacity(0.25), lineWidth: 1)
        )
    }
}

/// A horizontal scrollable strip of recent values — tap to fill.
struct RecentChips: View {
    @EnvironmentObject var theme: ThemeManager
    let values: [String]
    let onPick: (String) -> Void

    var body: some View {
        if values.isEmpty {
            EmptyView()
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(values.prefix(12), id: \.self) { v in
                        Button {
                            onPick(v)
                        } label: {
                            Text(v)
                                .font(.callout)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background {
                                    Capsule().fill(.ultraThinMaterial)
                                    Capsule().fill(theme.current.accentColor.opacity(0.18))
                                }
                                .overlay(Capsule().stroke(theme.current.accentColor.opacity(0.35), lineWidth: 1))
                                .foregroundStyle(.primary)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 4)
            }
        }
    }
}

/// Lock toggle next to a location field. Tap to flip between 🔓 and 🔒.
struct LockToggle: View {
    @EnvironmentObject var theme: ThemeManager
    @Binding var locked: Bool
    let label: String

    var body: some View {
        Button {
            locked.toggle()
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: locked ? "lock.fill" : "lock.open")
                    .font(.caption.weight(.semibold))
                Text(label.uppercased())
                    .font(.caption.weight(.semibold))
                    .tracking(0.6)
            }
            .foregroundStyle(locked ? Color.orange : theme.current.accentColor.opacity(0.75))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(locked ? "\(label) locked — value will stick after adding" : "\(label) unlocked — value will clear after adding")
    }
}
