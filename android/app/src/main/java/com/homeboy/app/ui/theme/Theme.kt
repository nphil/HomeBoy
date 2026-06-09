package com.homeboy.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class AppColorScheme(val name: String, val seed: Color)

/** Index 0 is Material You: dynamic color derived from the wallpaper (Android 12+). */
const val THEME_MATERIAL_YOU = 0

val APP_THEMES = listOf(
    AppColorScheme("Material You", Color(0xFF4F46E5)),  // seed = pre-12 fallback
    AppColorScheme("Indigo",      Color(0xFF4F46E5)),
    AppColorScheme("Violet",      Color(0xFF7C3AED)),
    AppColorScheme("Purple",      Color(0xFF9333EA)),
    AppColorScheme("Fuchsia",     Color(0xFFC026D3)),
    AppColorScheme("Pink",        Color(0xFFDB2777)),
    AppColorScheme("Rose",        Color(0xFFE11D48)),
    AppColorScheme("Red",         Color(0xFFDC2626)),
    AppColorScheme("Orange",      Color(0xFFEA580C)),
    AppColorScheme("Amber",       Color(0xFFD97706)),
    AppColorScheme("Yellow",      Color(0xFFCA8A04)),
    AppColorScheme("Lime",        Color(0xFF65A30D)),
    AppColorScheme("Green",       Color(0xFF16A34A)),
    AppColorScheme("Emerald",     Color(0xFF059669)),
    AppColorScheme("Teal",        Color(0xFF0D9488)),
    AppColorScheme("Cyan",        Color(0xFF0891B2)),
    AppColorScheme("Sky",         Color(0xFF0284C7)),
    AppColorScheme("Blue",        Color(0xFF2563EB)),
    AppColorScheme("Slate",       Color(0xFF475569)),
    AppColorScheme("Gray",        Color(0xFF4B5563)),
    AppColorScheme("Zinc",        Color(0xFF52525B)),
    AppColorScheme("Neutral",     Color(0xFF525252)),
    AppColorScheme("Stone",       Color(0xFF57534E)),
    AppColorScheme("Warm Red",    Color(0xFFB91C1C)),
    AppColorScheme("Deep Purple", Color(0xFF6D28D9)),
    AppColorScheme("Ocean",       Color(0xFF0369A1)),
    AppColorScheme("Forest",      Color(0xFF166534)),
    AppColorScheme("Sunset",      Color(0xFFC2410C)),
    AppColorScheme("Lavender",    Color(0xFF7E22CE)),
    AppColorScheme("Coral",       Color(0xFFBE185D)),
    AppColorScheme("Mint",        Color(0xFF047857))
)

private fun seedToScheme(seed: Color, dark: Boolean): ColorScheme {
    // Derive a minimal scheme from seed color
    val primary = seed
    val onPrimary = Color.White
    val primaryContainer = Color(
        red = (seed.red * 0.85f + 0.15f).coerceIn(0f, 1f),
        green = (seed.green * 0.85f + 0.15f).coerceIn(0f, 1f),
        blue = (seed.blue * 0.85f + 0.15f).coerceIn(0f, 1f)
    )
    return if (dark) {
        darkColorScheme(
            primary = primaryContainer,
            onPrimary = Color(0xFF1A1A2E),
            primaryContainer = seed,
            onPrimaryContainer = Color.White,
            secondary = primaryContainer
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = Color(0xFF1A1A2E),
            secondary = primary
        )
    }
}

@Composable
fun HomeBoyTheme(
    themeIndex: Int = THEME_MATERIAL_YOU,
    content: @Composable () -> Unit
) {
    val dark = isSystemInDarkTheme()

    // Dynamic (wallpaper-based) color only when the user picks "Material You";
    // every other theme uses its own seed so the picker actually takes effect.
    val colorScheme = if (themeIndex == THEME_MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        val seed = APP_THEMES.getOrNull(themeIndex)?.seed ?: APP_THEMES[0].seed
        seedToScheme(seed, dark)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
