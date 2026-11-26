package com.shizukutrackpad.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

/**
 * Type scale. Interactive and informative text never goes below [Caption] (12sp);
 * [Micro] is reserved for the decorative cursor-coordinate readout, which is hidden from screen readers.
 */
object TrackpadType {
    val Micro = 9.sp
    val Tag = 11.sp
    val Caption = 12.sp
    val Label = 13.sp
    val Body = 14.sp
    val Title = 16.sp
}

/** Corner radii: S for controls and keycaps, M for large (48dp) buttons, L for the trackpad monolith. */
object TrackpadRadius {
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
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

/**
 * Text sizes come from [TrackpadType]. The Material base styles only supply the font, so drop their
 * fixed line heights (e.g. bodyLarge's 24sp) and letter-spacing, which bloat and loosen small labels.
 */
private val HardwareTypography = Typography().let { base ->
    base.copy(
        bodyLarge = base.bodyLarge.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
        bodyMedium = base.bodyMedium.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
        bodySmall = base.bodySmall.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp),
        labelLarge = base.labelLarge.copy(lineHeight = TextUnit.Unspecified, letterSpacing = 0.sp)
    )
}

@Composable
fun ShizukuTrackpadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HardwareDarkColors,
        typography = HardwareTypography,
        content = content
    )
}
