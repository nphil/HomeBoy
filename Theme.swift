import SwiftUI

// MARK: - Color helpers

extension Color {
    /// HSL → Color, where h is 0..360 and s/l are 0..1.
    init(h: Double, s: Double, l: Double) {
        let c = (1 - abs(2 * l - 1)) * s
        let hPrime = h / 60
        let x = c * (1 - abs(hPrime.truncatingRemainder(dividingBy: 2) - 1))
        let m = l - c / 2
        let (r1, g1, b1): (Double, Double, Double)
        switch hPrime {
        case 0..<1: (r1, g1, b1) = (c, x, 0)
        case 1..<2: (r1, g1, b1) = (x, c, 0)
        case 2..<3: (r1, g1, b1) = (0, c, x)
        case 3..<4: (r1, g1, b1) = (0, x, c)
        case 4..<5: (r1, g1, b1) = (x, 0, c)
        case 5..<6: (r1, g1, b1) = (c, 0, x)
        default:    (r1, g1, b1) = (0, 0, 0)
        }
        self.init(red: r1 + m, green: g1 + m, blue: b1 + m)
    }

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

// MARK: - Theme colours

struct ThemeColors {
    let background: Color
    let foreground: Color
    let primary: Color
    let accent: Color
    /// Legible text/icon colour on top of `primary` fills (FABs, chips, badges).
    let onAccent: Color
    /// Lightness of the background (0..1). > 0.5 → light scheme, ≤ 0.5 → dark.
    let backgroundLightness: Double

    var preferredColorScheme: ColorScheme { backgroundLightness > 0.5 ? .light : .dark }
}

/// 30 hand-curated palettes — 15 light + 15 dark — shared 1:1 with the Android
/// app (Theme.kt uses the same names and hex values), so a household running
/// both platforms can match them.
enum AppTheme: String, CaseIterable, Identifiable, Hashable {
    // Light
    case indigoDawn, porcelainTeal, rosewater, amberGrove, meadow
    case skyHarbor, lavenderMist, coralReef, sandstone, sakura
    case glacier, oliveGrove, copperSlate, cobalt, graphite
    // Dark
    case midnightIndigo, obsidianTeal, ember, deepForest, midnightOcean
    case velvetGrape, carbonRose, nordicNight, espresso, neonNoir
    case abyss, pitchBlack, aurora, honeyAmber, steel

    var id: String { rawValue }

    var name: String {
        switch self {
        case .indigoDawn:     return "Indigo Dawn"
        case .porcelainTeal:  return "Porcelain Teal"
        case .rosewater:      return "Rosewater"
        case .amberGrove:     return "Amber Grove"
        case .meadow:         return "Meadow"
        case .skyHarbor:      return "Sky Harbor"
        case .lavenderMist:   return "Lavender Mist"
        case .coralReef:      return "Coral Reef"
        case .sandstone:      return "Sandstone"
        case .sakura:         return "Sakura"
        case .glacier:        return "Glacier"
        case .oliveGrove:     return "Olive Grove"
        case .copperSlate:    return "Copper Slate"
        case .cobalt:         return "Cobalt"
        case .graphite:       return "Graphite"
        case .midnightIndigo: return "Midnight Indigo"
        case .obsidianTeal:   return "Obsidian Teal"
        case .ember:          return "Ember"
        case .deepForest:     return "Deep Forest"
        case .midnightOcean:  return "Midnight Ocean"
        case .velvetGrape:    return "Velvet Grape"
        case .carbonRose:     return "Carbon Rose"
        case .nordicNight:    return "Nordic Night"
        case .espresso:       return "Espresso"
        case .neonNoir:       return "Neon Noir"
        case .abyss:          return "Abyss"
        case .pitchBlack:     return "Pitch Black"
        case .aurora:         return "Aurora"
        case .honeyAmber:     return "Honey Amber"
        case .steel:          return "Steel"
        }
    }

    var isDark: Bool { colors.backgroundLightness <= 0.5 }

    static var lightThemes: [AppTheme] { allCases.filter { !$0.isDark } }
    static var darkThemes: [AppTheme] { allCases.filter { $0.isDark } }

    /// Palettes are immutable — build every ThemeColors once. Rows read
    /// `theme.current.accentColor` many times per render; without this cache
    /// each access re-converted four colours.
    private static let paletteCache: [AppTheme: ThemeColors] =
        Dictionary(uniqueKeysWithValues: allCases.map { ($0, $0.buildColors()) })

    var colors: ThemeColors { Self.paletteCache[self] ?? buildColors() }

    private func buildColors() -> ThemeColors {
        // t(bg, fg, primary, accent, onAccent, bgLightness)
        func t(_ bg: String, _ fg: String, _ p: String, _ a: String, _ on: String, _ l: Double) -> ThemeColors {
            ThemeColors(background: Color(hex: bg), foreground: Color(hex: fg),
                        primary: Color(hex: p), accent: Color(hex: a),
                        onAccent: Color(hex: on), backgroundLightness: l)
        }
        let dark = "#101014", white = "#FFFFFF"
        switch self {
        // ---- Light (15) ----
        case .indigoDawn:     return t("#F4F5FE", "#1E1B4B", "#4F46E5", "#7C3AED", white, 0.98)
        case .porcelainTeal:  return t("#F0FAF8", "#134E4A", "#0F766E", "#0891B2", white, 0.96)
        case .rosewater:      return t("#FDF2F4", "#4C0519", "#BE123C", "#FB7185", white, 0.97)
        case .amberGrove:     return t("#FDF8EF", "#451A03", "#D97706", "#B45309", white, 0.97)
        case .meadow:         return t("#F2FAF2", "#14532D", "#16A34A", "#65A30D", white, 0.97)
        case .skyHarbor:      return t("#EFF7FF", "#0C4A6E", "#0284C7", "#38BDF8", white, 0.97)
        case .lavenderMist:   return t("#F7F4FD", "#3B0764", "#7C3AED", "#C084FC", white, 0.97)
        case .coralReef:      return t("#FFF4F0", "#431407", "#EA580C", "#F97316", white, 0.97)
        case .sandstone:      return t("#FAF6F0", "#422006", "#A16207", "#CA8A04", white, 0.96)
        case .sakura:         return t("#FDF2F8", "#500724", "#DB2777", "#F472B6", white, 0.97)
        case .glacier:        return t("#F0F9FB", "#164E63", "#0891B2", "#06B6D4", white, 0.96)
        case .oliveGrove:     return t("#F7F8EC", "#1A2E05", "#4D7C0F", "#84CC16", white, 0.95)
        case .copperSlate:    return t("#F6F7F8", "#292524", "#B45309", "#57534E", white, 0.97)
        case .cobalt:         return t("#F2F6FF", "#172554", "#2563EB", "#3B82F6", white, 0.97)
        case .graphite:       return t("#F7F7F8", "#18181B", "#3F3F46", "#71717A", white, 0.97)
        // ---- Dark (15) ----
        case .midnightIndigo: return t("#11122B", "#E0E7FF", "#818CF8", "#A5B4FC", dark, 0.12)
        case .obsidianTeal:   return t("#091514", "#CCFBF1", "#2DD4BF", "#5EEAD4", dark, 0.06)
        case .ember:          return t("#1B100C", "#FFEDD5", "#F97316", "#FB923C", white, 0.08)
        case .deepForest:     return t("#0B140E", "#DCFCE7", "#4ADE80", "#86EFAC", dark, 0.06)
        case .midnightOcean:  return t("#081420", "#E0F2FE", "#38BDF8", "#7DD3FC", dark, 0.08)
        case .velvetGrape:    return t("#16101F", "#F3E8FF", "#A78BFA", "#C4B5FD", dark, 0.09)
        case .carbonRose:     return t("#1A0E13", "#FFE4E6", "#FB7185", "#FDA4AF", dark, 0.08)
        case .nordicNight:    return t("#10151D", "#ECEFF4", "#88C0D0", "#81A1C1", dark, 0.09)
        case .espresso:       return t("#171210", "#F5E9DC", "#E0A458", "#C68A4E", dark, 0.08)
        case .neonNoir:       return t("#0D0B14", "#FAE8FF", "#E879F9", "#F0ABFC", dark, 0.06)
        case .abyss:          return t("#061218", "#D1FAE5", "#34D399", "#6EE7B7", dark, 0.06)
        case .pitchBlack:     return t("#000000", "#E4E4E7", "#60A5FA", "#93C5FD", dark, 0.00)
        case .aurora:         return t("#0C1322", "#CCFBF1", "#5EEAD4", "#A78BFA", dark, 0.09)
        case .honeyAmber:     return t("#15110A", "#FEF3C7", "#FBBF24", "#FCD34D", dark, 0.06)
        case .steel:          return t("#0D1117", "#C9D1D9", "#58A6FF", "#79C0FF", dark, 0.07)
        }
    }

    // Convenience accessors used across the app.
    var backgroundColor: Color { colors.background }
    var foregroundColor: Color { colors.foreground }
    var accentColor: Color { colors.primary }
    var secondaryColor: Color { colors.accent }
    /// Legible content colour on accent-filled surfaces.
    var onAccentColor: Color { colors.onAccent }
    var preferredColorScheme: ColorScheme { colors.preferredColorScheme }
}

// MARK: - Theme manager

@MainActor
final class ThemeManager: ObservableObject {
    @Published private(set) var current: AppTheme {
        didSet { UserDefaults.standard.set(current.rawValue, forKey: "homebox.theme") }
    }

    init() {
        if let raw = UserDefaults.standard.string(forKey: "homebox.theme") {
            current = AppTheme(rawValue: raw) ?? Self.migrated(from: raw) ?? .indigoDawn
        } else {
            current = .indigoDawn
        }
    }

    func set(_ theme: AppTheme) { current = theme }

    /// Users upgrading from the DaisyUI-era palette keep the closest new theme
    /// instead of being silently reset.
    private static func migrated(from old: String) -> AppTheme? {
        switch old {
        case "homebox", "garden", "emerald": return .meadow
        case "light":                        return .indigoDawn
        case "dark", "night":                return .midnightIndigo
        case "forest":                       return .deepForest
        case "aqua":                         return .glacier
        case "ocean":                        return .midnightOcean
        case "dracula":                      return .velvetGrape
        case "synthwave", "acid":            return .neonNoir
        case "halloween":                    return .ember
        case "coffee":                       return .espresso
        case "business":                     return .steel
        case "luxury":                       return .honeyAmber
        case "black":                        return .pitchBlack
        case "cupcake":                      return .porcelainTeal
        case "valentine", "autumn":          return .rosewater
        case "pastel", "fantasy":            return .lavenderMist
        case "retro":                        return .sandstone
        case "bumblebee":                    return .amberGrove
        case "lemonade":                     return .oliveGrove
        case "corporate", "winter":          return .cobalt
        case "cmyk":                         return .skyHarbor
        case "cyberpunk":                    return .neonNoir
        case "wireframe", "lofi":            return .graphite
        default:                             return nil
        }
    }
}

// MARK: - Theme swatch

/// Round swatch showing the theme's actual background + primary colour.
struct ThemeSwatch: View {
    let theme: AppTheme
    let isSelected: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 6) {
                ZStack {
                    Circle()
                        .fill(theme.backgroundColor)
                        .frame(width: 48, height: 48)
                    // Two stacked discs hint at the primary + secondary accent.
                    Circle()
                        .fill(theme.accentColor)
                        .frame(width: 22, height: 22)
                        .offset(x: -6, y: -2)
                    Circle()
                        .fill(theme.secondaryColor)
                        .frame(width: 14, height: 14)
                        .offset(x: 9, y: 6)
                    if isSelected {
                        Circle()
                            .stroke(theme.accentColor, lineWidth: 3)
                            .frame(width: 52, height: 52)
                        Image(systemName: "checkmark.circle.fill")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(theme.accentColor)
                            .background(Circle().fill(theme.backgroundColor).frame(width: 18, height: 18))
                            .offset(x: 16, y: 16)
                    }
                }
                .overlay(
                    Circle().stroke(theme.foregroundColor.opacity(0.15), lineWidth: 1)
                        .frame(width: 48, height: 48)
                )
                Text(theme.name)
                    .font(.caption2)
                    .foregroundStyle(isSelected ? .primary : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(theme.name) theme")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
