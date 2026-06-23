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
 * A theme ported 1:1 from the iOS app (`Theme.swift`), which in turn mirrors
 * Homebox's web frontend. Each theme is self-contained: it carries its own
 * background/foreground and a `dark` flag, so picking "Light" stays light even
 * when the system is in dark mode (matching iOS).
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

/** hsl helper — h in 0..360, s/l in 0..100 (percent), matching the iOS source. */
private fun hsl(h: Float, s: Float, l: Float): Color = Color.hsl(h, s / 100f, l / 100f)

private fun theme(
    name: String,
    bg: Color, fg: Color, primary: Color, accent: Color, dark: Boolean
) = AppColorScheme(name, primary, bg, fg, primary, accent, dark)

val APP_THEMES: List<AppColorScheme> = listOf(
    // Index 0 — Android-native dynamic color (handled specially in HomeBoyTheme).
    AppColorScheme("Material You", Color(0xFF4F46E5), hsl(0f, 0f, 100f), hsl(0f, 0f, 20f), Color(0xFF4F46E5), Color(0xFF7C3AED), false),
    // Ported from iOS (Theme.swift) — order matches the iOS picker.
    theme("Homebox",   hsl(0f,0f,100f),    hsl(0f,0f,20f),     hsl(139f,16f,43f),  hsl(97f,37f,93f),  false),
    theme("Light",     hsl(0f,0f,100f),    hsl(215f,28f,17f),  hsl(259f,94f,51f),  hsl(314f,100f,47f), false),
    theme("Dark",      hsl(0f,0f,11f),     hsl(0f,0f,90f),     hsl(259f,94f,70f),  hsl(314f,100f,70f), true),
    theme("Forest",    hsl(0f,12f,8f),     hsl(0f,12f,82f),    hsl(141f,72f,42f),  hsl(141f,75f,48f), true),
    theme("Garden",    hsl(0f,4f,91f),     hsl(0f,3f,6f),      hsl(139f,16f,43f),  hsl(97f,37f,93f),  false),
    theme("Emerald",   hsl(0f,0f,100f),    hsl(219f,20f,25f),  hsl(141f,50f,60f),  hsl(219f,96f,60f), false),
    theme("Aqua",      hsl(219f,53f,43f),  hsl(218f,100f,89f), hsl(182f,93f,49f),  hsl(274f,31f,57f), true),
    theme("Ocean",     hsl(207f,50f,14f),  hsl(207f,30f,90f),  hsl(199f,89f,64f),  hsl(259f,50f,67f), true),
    theme("Night",     hsl(222f,47f,11f),  hsl(222f,65f,82f),  hsl(198f,93f,60f),  hsl(234f,89f,74f), true),
    theme("Dracula",   hsl(231f,15f,18f),  hsl(60f,30f,96f),   hsl(326f,100f,74f), hsl(265f,89f,78f), true),
    theme("Synthwave", hsl(254f,59f,26f),  hsl(260f,60f,98f),  hsl(321f,70f,69f),  hsl(197f,87f,65f), true),
    theme("Halloween", hsl(0f,0f,13f),     hsl(0f,0f,83f),     hsl(32f,89f,52f),   hsl(271f,46f,42f), true),
    theme("Coffee",    hsl(306f,19f,11f),  hsl(37f,30f,70f),   hsl(30f,67f,58f),   hsl(182f,25f,50f), true),
    theme("Business",  hsl(0f,0f,13f),     hsl(0f,0f,82f),     hsl(210f,64f,55f),  hsl(200f,13f,65f), true),
    theme("Luxury",    hsl(240f,10f,4f),   hsl(37f,67f,58f),   hsl(0f,0f,100f),    hsl(218f,54f,50f), true),
    theme("Black",     hsl(0f,0f,0f),      hsl(0f,0f,80f),     hsl(0f,0f,70f),     hsl(0f,0f,50f),    true),
    theme("Cupcake",   hsl(24f,33f,97f),   hsl(280f,46f,14f),  hsl(183f,47f,59f),  hsl(338f,71f,78f), false),
    theme("Valentine", hsl(318f,46f,89f),  hsl(344f,38f,28f),  hsl(353f,74f,67f),  hsl(254f,86f,77f), false),
    theme("Pastel",    hsl(0f,0f,100f),    hsl(0f,0f,20f),     hsl(284f,22f,70f),  hsl(352f,70f,80f), false),
    theme("Fantasy",   hsl(0f,0f,100f),    hsl(215f,28f,17f),  hsl(296f,83f,35f),  hsl(200f,100f,37f), false),
    theme("Retro",     hsl(45f,47f,80f),   hsl(345f,5f,15f),   hsl(3f,60f,55f),    hsl(145f,35f,50f), false),
    theme("Bumblebee", hsl(0f,0f,100f),    hsl(0f,0f,20f),     hsl(41f,74f,53f),   hsl(50f,94f,58f),  false),
    theme("Lemonade",  hsl(0f,0f,100f),    hsl(0f,0f,20f),     hsl(89f,96f,31f),   hsl(60f,81f,45f),  false),
    theme("Corporate", hsl(0f,0f,100f),    hsl(233f,27f,13f),  hsl(229f,96f,64f),  hsl(215f,26f,59f), false),
    theme("CMYK",      hsl(0f,0f,100f),    hsl(0f,0f,20f),     hsl(203f,83f,60f),  hsl(335f,78f,60f), false),
    theme("Autumn",    hsl(0f,0f,95f),     hsl(0f,0f,19f),     hsl(344f,96f,38f),  hsl(0f,63f,50f),   false),
    theme("Winter",    hsl(0f,0f,100f),    hsl(214f,30f,32f),  hsl(212f,100f,51f), hsl(247f,47f,43f), false),
    theme("Acid",      hsl(0f,0f,98f),     hsl(0f,0f,20f),     hsl(303f,90f,45f),  hsl(27f,100f,50f), false),
    theme("Cyberpunk", hsl(56f,100f,50f),  hsl(56f,100f,10f),  hsl(345f,100f,50f), hsl(195f,80f,55f), true),
    theme("Wireframe", hsl(0f,0f,100f),    hsl(0f,0f,20f),     hsl(0f,0f,40f),     hsl(0f,0f,60f),    false),
    theme("Lofi",      hsl(0f,0f,100f),    hsl(0f,0f,0f),      hsl(0f,0f,5f),      hsl(0f,2f,30f),    false)
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
