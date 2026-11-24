package com.ytgrab.app

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

class Palette(val id: String, val name: String, val seed: Color)

val PALETTES = listOf(
    Palette("ocean", "Ocean", Color(0xFF1E6FD9)),
    Palette("emerald", "Emerald", Color(0xFF1F8A5B)),
    Palette("sunset", "Sunset", Color(0xFFE8590C)),
    Palette("rose", "Rose", Color(0xFFD6336C)),
    Palette("violet", "Violet", Color(0xFF7048E8)),
    Palette("crimson", "Crimson", Color(0xFFC92A2A)),
    Palette("teal", "Teal", Color(0xFF0C8599)),
    Palette("gold", "Gold", Color(0xFFB8860B)),
    Palette("slate", "Slate", Color(0xFF495E78)),
    Palette("mint", "Mint", Color(0xFF2B9348)),
    Palette("sakura", "Sakura", Color(0xFFE56B9B)),
    Palette("graphite", "Graphite", Color(0xFF555B66)),
)

private fun mix(a: Color, b: Color, f: Float) = lerp(a, b, f)

fun schemeFor(seed: Color, dark: Boolean, amoled: Boolean): ColorScheme {
    val white = Color.White
    val black = Color.Black
    if (!dark) {
        val secondary = mix(seed, Color(0xFF6B6F78), 0.55f)
        val tertiary = mix(seed, Color(0xFF8E4585), 0.45f)
        return lightColorScheme(
            primary = seed,
            onPrimary = white,
            primaryContainer = mix(seed, white, 0.82f),
            onPrimaryContainer = mix(seed, black, 0.72f),
            secondary = secondary,
            onSecondary = white,
            secondaryContainer = mix(secondary, white, 0.84f),
            onSecondaryContainer = mix(secondary, black, 0.72f),
            tertiary = tertiary,
            onTertiary = white,
            tertiaryContainer = mix(tertiary, white, 0.84f),
            onTertiaryContainer = mix(tertiary, black, 0.72f),
            background = mix(white, seed, 0.03f),
            onBackground = Color(0xFF1B1C20),
            surface = mix(white, seed, 0.03f),
            onSurface = Color(0xFF1B1C20),
            surfaceVariant = mix(white, seed, 0.12f),
            onSurfaceVariant = Color(0xFF464A53),
            outline = Color(0xFF777B85),
            outlineVariant = mix(white, seed, 0.22f),
            surfaceContainerLowest = white,
            surfaceContainerLow = mix(white, seed, 0.04f),
            surfaceContainer = mix(white, seed, 0.07f),
            surfaceContainerHigh = mix(white, seed, 0.10f),
            surfaceContainerHighest = mix(white, seed, 0.13f),
        )
    }
    val secondary = mix(seed, Color(0xFFB8BCC6), 0.6f)
    val tertiary = mix(seed, Color(0xFFD08BC6), 0.5f)
    val bg = if (amoled) black else mix(Color(0xFF111216), seed, 0.07f)
    return darkColorScheme(
        primary = mix(seed, white, 0.45f),
        onPrimary = mix(seed, black, 0.78f),
        primaryContainer = mix(seed, black, 0.52f),
        onPrimaryContainer = mix(seed, white, 0.82f),
        secondary = secondary,
        onSecondary = Color(0xFF1B1D24),
        secondaryContainer = mix(Color(0xFF30333B), seed, 0.18f),
        onSecondaryContainer = mix(seed, white, 0.85f),
        tertiary = tertiary,
        onTertiary = Color(0xFF2A1327),
        tertiaryContainer = mix(Color(0xFF3A2438), seed, 0.1f),
        onTertiaryContainer = mix(tertiary, white, 0.8f),
        background = bg,
        onBackground = Color(0xFFE4E2E6),
        surface = bg,
        onSurface = Color(0xFFE4E2E6),
        surfaceVariant = mix(Color(0xFF2B2D34), seed, 0.10f),
        onSurfaceVariant = Color(0xFFC6C8D1),
        outline = Color(0xFF8E9099),
        outlineVariant = mix(Color(0xFF42454D), seed, 0.1f),
        surfaceContainerLowest = if (amoled) black else mix(Color(0xFF0C0D10), seed, 0.04f),
        surfaceContainerLow = if (amoled) mix(Color(0xFF0A0A0B), seed, 0.04f) else mix(Color(0xFF16171B), seed, 0.06f),
        surfaceContainer = if (amoled) mix(Color(0xFF101012), seed, 0.05f) else mix(Color(0xFF1B1C21), seed, 0.08f),
        surfaceContainerHigh = if (amoled) mix(Color(0xFF17171A), seed, 0.06f) else mix(Color(0xFF25262C), seed, 0.09f),
        surfaceContainerHighest = if (amoled) mix(Color(0xFF1E1E22), seed, 0.07f) else mix(Color(0xFF30313A), seed, 0.10f),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun YTGrabTheme(content: @Composable () -> Unit) {
    val sysDark = isSystemInDarkTheme()
    val dark = when (Prefs.appearance) {
        "light" -> false
        "dark" -> true
        else -> sysDark
    }
    val ctx = LocalContext.current
    val palette = PALETTES.firstOrNull { it.id == Prefs.themeId }
    val scheme: ColorScheme = if (palette == null && Build.VERSION.SDK_INT >= 31) {
        val base = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        if (dark && Prefs.amoled) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceContainerLowest = Color.Black,
                surfaceContainerLow = Color(0xFF0A0A0A),
                surfaceContainer = Color(0xFF101010),
                surfaceContainerHigh = Color(0xFF161616),
                surfaceContainerHighest = Color(0xFF1D1D1D),
            )
        } else base
    } else {
        schemeFor((palette ?: PALETTES[0]).seed, dark, Prefs.amoled && dark)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
