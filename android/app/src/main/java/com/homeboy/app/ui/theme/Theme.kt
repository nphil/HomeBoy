package com.homeboy.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 30 hand-curated palettes — 15 light + 15 dark — shared 1:1 with the iOS app
 * (`Theme.swift` uses the same names and hex values). Each theme is
 * self-contained: it carries its own background/foreground and a `dark` flag,
 * so picking a light theme stays light even when the system is in dark mode
 * (matching iOS). Index 0 remains Android-only Material You dynamic color.
 *
 * `seed` is the swatch/preview color shown in Settings (= the primary color).
 */
data class AppColorScheme(
    val name: String,
    val seed: Color,
    val background: Color,
    val foreground: Color,
    val primary: Color,
    val accent: Color,
    val dark: Boolean
)

/** Index 0 is Material You: dynamic color derived from the wallpaper (Android 12+). */
const val THEME_MATERIAL_YOU = 0

private fun theme(
    name: String,
    bg: Long, fg: Long, primary: Long, accent: Long, dark: Boolean
) = AppColorScheme(name, Color(primary), Color(bg), Color(fg), Color(primary), Color(accent), dark)

val APP_THEMES: List<AppColorScheme> = listOf(
    // Index 0 — Android-native dynamic color (handled specially in HomeBoyTheme).
    AppColorScheme("Material You", Color(0xFF4F46E5), Color(0xFFFFFFFF), Color(0xFF333333), Color(0xFF4F46E5), Color(0xFF7C3AED), false),

    // ---- Light (15) — names/colors shared with iOS Theme.swift ----
    theme("Indigo Dawn",    0xFFF4F5FE, 0xFF1E1B4B, 0xFF4F46E5, 0xFF7C3AED, false),
    theme("Porcelain Teal", 0xFFF0FAF8, 0xFF134E4A, 0xFF0F766E, 0xFF0891B2, false),
    theme("Rosewater",      0xFFFDF2F4, 0xFF4C0519, 0xFFBE123C, 0xFFFB7185, false),
    theme("Amber Grove",    0xFFFDF8EF, 0xFF451A03, 0xFFD97706, 0xFFB45309, false),
    theme("Meadow",         0xFFF2FAF2, 0xFF14532D, 0xFF16A34A, 0xFF65A30D, false),
    theme("Sky Harbor",     0xFFEFF7FF, 0xFF0C4A6E, 0xFF0284C7, 0xFF38BDF8, false),
    theme("Lavender Mist",  0xFFF7F4FD, 0xFF3B0764, 0xFF7C3AED, 0xFFC084FC, false),
    theme("Coral Reef",     0xFFFFF4F0, 0xFF431407, 0xFFEA580C, 0xFFF97316, false),
    theme("Sandstone",      0xFFFAF6F0, 0xFF422006, 0xFFA16207, 0xFFCA8A04, false),
    theme("Sakura",         0xFFFDF2F8, 0xFF500724, 0xFFDB2777, 0xFFF472B6, false),
    theme("Glacier",        0xFFF0F9FB, 0xFF164E63, 0xFF0891B2, 0xFF06B6D4, false),
    theme("Olive Grove",    0xFFF7F8EC, 0xFF1A2E05, 0xFF4D7C0F, 0xFF84CC16, false),
    theme("Copper Slate",   0xFFF6F7F8, 0xFF292524, 0xFFB45309, 0xFF57534E, false),
    theme("Cobalt",         0xFFF2F6FF, 0xFF172554, 0xFF2563EB, 0xFF3B82F6, false),
    theme("Graphite",       0xFFF7F7F8, 0xFF18181B, 0xFF3F3F46, 0xFF71717A, false),

    // ---- Dark (15) ----
    theme("Midnight Indigo", 0xFF11122B, 0xFFE0E7FF, 0xFF818CF8, 0xFFA5B4FC, true),
    theme("Obsidian Teal",   0xFF091514, 0xFFCCFBF1, 0xFF2DD4BF, 0xFF5EEAD4, true),
    theme("Ember",           0xFF1B100C, 0xFFFFEDD5, 0xFFF97316, 0xFFFB923C, true),
    theme("Deep Forest",     0xFF0B140E, 0xFFDCFCE7, 0xFF4ADE80, 0xFF86EFAC, true),
    theme("Midnight Ocean",  0xFF081420, 0xFFE0F2FE, 0xFF38BDF8, 0xFF7DD3FC, true),
    theme("Velvet Grape",    0xFF16101F, 0xFFF3E8FF, 0xFFA78BFA, 0xFFC4B5FD, true),
    theme("Carbon Rose",     0xFF1A0E13, 0xFFFFE4E6, 0xFFFB7185, 0xFFFDA4AF, true),
    theme("Nordic Night",    0xFF10151D, 0xFFECEFF4, 0xFF88C0D0, 0xFF81A1C1, true),
    theme("Espresso",        0xFF171210, 0xFFF5E9DC, 0xFFE0A458, 0xFFC68A4E, true),
    theme("Neon Noir",       0xFF0D0B14, 0xFFFAE8FF, 0xFFE879F9, 0xFFF0ABFC, true),
    theme("Abyss",           0xFF061218, 0xFFD1FAE5, 0xFF34D399, 0xFF6EE7B7, true),
    theme("Pitch Black",     0xFF000000, 0xFFE4E4E7, 0xFF60A5FA, 0xFF93C5FD, true),
    theme("Aurora",          0xFF0C1322, 0xFFCCFBF1, 0xFF5EEAD4, 0xFFA78BFA, true),
    theme("Honey Amber",     0xFF15110A, 0xFFFEF3C7, 0xFFFBBF24, 0xFFFCD34D, true),
    theme("Steel",           0xFF0D1117, 0xFFC9D1D9, 0xFF58A6FF, 0xFF79C0FF, true)
)

private fun onColor(c: Color): Color = if (c.luminance() > 0.5f) Color(0xFF101014) else Color.White

/** Build a full Material 3 ColorScheme from the four theme colors + dark flag. */
private fun schemeFrom(t: AppColorScheme): ColorScheme {
    val bg = t.background
    val fg = t.foreground
    val base = if (t.dark) darkColorScheme() else lightColorScheme()
    fun mix(toward: Color, amt: Float) = lerp(bg, toward, amt)
    return base.copy(
        primary = t.primary,
        onPrimary = onColor(t.primary),
        primaryContainer = mix(t.primary, if (t.dark) 0.34f else 0.20f),
        onPrimaryContainer = fg,
        secondary = t.accent,
        onSecondary = onColor(t.accent),
        secondaryContainer = mix(t.accent, if (t.dark) 0.34f else 0.20f),
        onSecondaryContainer = fg,
        tertiary = t.accent,
        onTertiary = onColor(t.accent),
        tertiaryContainer = mix(t.accent, if (t.dark) 0.28f else 0.16f),
        onTertiaryContainer = fg,
        background = bg,
        onBackground = fg,
        surface = bg,
        onSurface = fg,
        surfaceVariant = mix(fg, 0.10f),
        onSurfaceVariant = lerp(fg, bg, 0.30f),
        surfaceContainerLowest = mix(fg, if (t.dark) 0.02f else 0.015f),
        surfaceContainerLow = mix(fg, if (t.dark) 0.05f else 0.03f),
        surfaceContainer = mix(fg, if (t.dark) 0.08f else 0.05f),
        surfaceContainerHigh = mix(fg, if (t.dark) 0.12f else 0.08f),
        surfaceContainerHighest = mix(fg, if (t.dark) 0.16f else 0.11f),
        outline = mix(fg, 0.40f),
        outlineVariant = mix(fg, 0.18f),
        inverseSurface = fg,
        inverseOnSurface = bg
    )
}

@Composable
fun HomeBoyTheme(
    themeIndex: Int = THEME_MATERIAL_YOU,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val isDynamic = themeIndex == THEME_MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colorScheme = if (isDynamic) {
        val ctx = LocalContext.current
        if (systemDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        schemeFrom(APP_THEMES.getOrNull(themeIndex) ?: APP_THEMES[1])
    }

    // Status/nav bar icons must contrast the theme background (light theme → dark icons),
    // independent of the system dark-mode setting, to match the iOS behavior.
    val darkScheme = if (isDynamic) systemDark else (APP_THEMES.getOrNull(themeIndex)?.dark ?: false)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkScheme
            controller.isAppearanceLightNavigationBars = !darkScheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
