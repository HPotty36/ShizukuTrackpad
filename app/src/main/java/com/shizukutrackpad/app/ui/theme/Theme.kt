package com.shizukutrackpad.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Achromatic anodized-graphite + true OLED black hardware palette.
 * Single restrained hardware accent (#FF6B2C) + 6dp semantic status LEDs only.
 */
object TrackpadTokens {
    val ChassisBlack = Color(0xFF000000)
    val DeckSurface = Color(0xFF0E0E10)
    // Clickpad floor sits one step above the black canvas so the hold-to-drag L zone is visible
    val ClickpadFloor = Color(0xFF0E0E10)
    val KeycapSurface = Color(0xFF18181B)
    val KeycapModifier = Color(0xFF141417)
    val SurfacePressed = Color(0xFF27272A)
    val ClickpadPressed = Color(0xFF27272A)

    val Hairline = Color(0xFF222226)
    val ClickpadSplit = Color(0xFF3F3F46)
    val ClickpadEdge = Color(0xFF5C5C66)
    val KeycapBorder = Color(0xFF2C2C31)
    val KeycapSkirt = Color(0xFF09090B)
    val TrackpadDot = Color(0xFF141417)

    val InkPrimary = Color(0xFFF4F4F5)
    val InkSecondary = Color(0xFF8E8E93)
    // >= 4.5:1 on every surface (was 0xFF52525B, ~2.5:1)
    val InkMuted = Color(0xFF80808A)
    val InkGhost = Color(0xFF3A3A42)
    val InkInverse = Color(0xFF09090B)

    // Visible inactive track for sliders (3.1:1 on DeckSurface)
    val TrackInactive = Color(0xFF60606A)

    // Single restrained hardware signal accent
    val AccentSignal = Color(0xFFFF6B2C)

    // Strictly for 6dp hardware status LED dots & error copy
    val LedOnline = Color(0xFF22C55E)
    val LedWarn = Color(0xFFF59E0B)
    // Warn LED variant for light (InkPrimary) pills
    val LedWarnOnLight = Color(0xFFB45309)
    val LedError = Color(0xFFEF4444)
}

private val HardwareDarkColors = darkColorScheme(
    primary = TrackpadTokens.InkPrimary,
    onPrimary = TrackpadTokens.InkInverse,
    secondary = TrackpadTokens.AccentSignal,
    onSecondary = TrackpadTokens.InkInverse,
    background = TrackpadTokens.ChassisBlack,
    onBackground = TrackpadTokens.InkPrimary,
    surface = TrackpadTokens.DeckSurface,
    onSurface = TrackpadTokens.InkPrimary,
    surfaceVariant = TrackpadTokens.KeycapSurface,
    onSurfaceVariant = TrackpadTokens.InkSecondary,
    outline = TrackpadTokens.Hairline,
    outlineVariant = TrackpadTokens.KeycapBorder,
    error = TrackpadTokens.LedError,
    onError = TrackpadTokens.InkPrimary
)

@Composable
fun ShizukuTrackpadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HardwareDarkColors,
        content = content
    )
}
