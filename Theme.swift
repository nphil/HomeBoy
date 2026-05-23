import SwiftUI

enum AppTheme: String, CaseIterable, Identifiable {
    case midnight, ocean, forest, dusk

    var id: String { rawValue }

    var name: String {
        switch self {
        case .midnight: return "Midnight"
        case .ocean:    return "Ocean"
        case .forest:   return "Forest"
        case .dusk:     return "Dusk"
        }
    }

    var accentColor: Color {
        switch self {
        case .midnight: return .indigo
        case .ocean:    return Color(hex: "38bdf8")
        case .forest:   return Color(hex: "4ade80")
        case .dusk:     return Color(hex: "d946ef")
        }
    }

    var swatchColors: [Color] {
        switch self {
        case .midnight: return [Color(hex: "4338ca"), Color(hex: "0f0a2e")]
        case .ocean:    return [Color(hex: "0369a1"), Color(hex: "071828")]
        case .forest:   return [Color(hex: "16a34a"), Color(hex: "052e16")]
        case .dusk:     return [Color(hex: "a21caf"), Color(hex: "2e0d38")]
        }
    }
}

extension Color {
    init(hex: String) {
        var rgb: UInt64 = 0
        Scanner(string: hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))).scanHexInt64(&rgb)
        self.init(
            red:   Double((rgb >> 16) & 0xFF) / 255,
            green: Double((rgb >>  8) & 0xFF) / 255,
            blue:  Double( rgb        & 0xFF) / 255
        )
    }
}

@MainActor
final class ThemeManager: ObservableObject {
    @Published private(set) var current: AppTheme {
        didSet { UserDefaults.standard.set(current.rawValue, forKey: "homebox.theme") }
    }

    init() {
        if let raw = UserDefaults.standard.string(forKey: "homebox.theme"),
           let saved = AppTheme(rawValue: raw) {
            current = saved
        } else {
            current = .ocean
        }
    }

    func set(_ theme: AppTheme) { current = theme }
}

/// Full-screen background with blurred colour orbs on near-black.
struct ThemeBackground: View {
    @EnvironmentObject var theme: ThemeManager

    var body: some View {
        ZStack {
            theme.current.accentColor.opacity(0.06).ignoresSafeArea()
            Color.black.opacity(0.82).ignoresSafeArea()
            Circle()
                .fill(theme.current.accentColor.opacity(0.85))
                .frame(width: 520)
                .blur(radius: 140)
                .offset(x: -80, y: -230)
            Circle()
                .fill(theme.current.accentColor.opacity(0.65))
                .frame(width: 480)
                .blur(radius: 130)
                .offset(x: 160, y: 370)
            Circle()
                .fill(theme.current.accentColor.opacity(0.25))
                .frame(width: 420)
                .blur(radius: 130)
                .offset(x: 20, y: 80)
        }
        .ignoresSafeArea()
    }
}

struct ThemeSwatch: View {
    let theme: AppTheme
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 6) {
                ZStack {
                    LinearGradient(
                        colors: theme.swatchColors,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    .clipShape(Circle())
                    .frame(width: 52, height: 52)

                    if isSelected {
                        Image(systemName: "checkmark")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
                .overlay(
                    Circle().stroke(
                        isSelected ? theme.accentColor : Color.white.opacity(0.15),
                        lineWidth: isSelected ? 2.5 : 1
                    )
                )

                Text(theme.name)
                    .font(.caption2)
                    .foregroundStyle(isSelected ? .primary : .secondary)
            }
        }
        .buttonStyle(.plain)
    }
}
