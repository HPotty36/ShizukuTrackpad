package com.shizukutrackpad.app.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import com.shizukutrackpad.app.R
import com.shizukutrackpad.app.ui.theme.TrackpadRadius
import com.shizukutrackpad.app.ui.theme.TrackpadTokens
import com.shizukutrackpad.app.ui.theme.TrackpadType

private val KeycapShape = RoundedCornerShape(TrackpadRadius.S)
private val KeycapFaceHeight = 38.dp
private val KeycapSkirtHeight = 2.dp

private enum class KeyKind {
    /** Single key or preset combo (Esc, Tab, Ctrl+C ...). */
    ACTION,

    /** Glyph keys (arrows, Backspace, Enter) that need a spoken description. */
    NAV,

    /** Latching one-shot modifier (Ctrl, Alt, Shift, Win). */
    STICKY
}

private data class KeySpec(
    val label: String,
    val kind: KeyKind,
    val keyCode: Int = 0,
    val metaState: Int = 0,
    val sticky: StickyModifier? = null,
    @StringRes val descriptionRes: Int? = null,
    /** Minimum width when the row scrolls (wide layout). */
    val minWidth: Dp = 40.dp,
    /** Share of the row width when the row is distributed (portrait layout). */
    val weight: Float = 1f,
    val fontSize: TextUnit = TrackpadType.Label
)

private sealed interface TrayItem
private data class KeyItem(val spec: KeySpec) : TrayItem
private data object DividerItem : TrayItem
private data class GapItem(val width: Dp) : TrayItem

private val CtrlMeta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
private val AltMeta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
private val MetaMeta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON

private val KeyEsc = KeySpec("Esc", KeyKind.ACTION, KeyEvent.KEYCODE_ESCAPE, minWidth = 44.dp, weight = 0.85f)
private val KeyTab = KeySpec("Tab", KeyKind.ACTION, KeyEvent.KEYCODE_TAB, minWidth = 44.dp, weight = 0.85f)
private val KeyCopy = KeySpec("Ctrl+C", KeyKind.ACTION, KeyEvent.KEYCODE_C, CtrlMeta, minWidth = 56.dp, weight = 1.25f, fontSize = TrackpadType.Caption)
private val KeyPaste = KeySpec("Ctrl+V", KeyKind.ACTION, KeyEvent.KEYCODE_V, CtrlMeta, minWidth = 56.dp, weight = 1.25f, fontSize = TrackpadType.Caption)
private val KeyAltTab = KeySpec("Alt+Tab", KeyKind.ACTION, KeyEvent.KEYCODE_TAB, AltMeta, minWidth = 62.dp, weight = 1.35f, fontSize = TrackpadType.Caption)
private val KeyDesktop = KeySpec("Win+D", KeyKind.ACTION, KeyEvent.KEYCODE_D, MetaMeta, minWidth = 56.dp, weight = 1.25f, fontSize = TrackpadType.Caption)

private val KeyCtrl = KeySpec("Ctrl", KeyKind.STICKY, sticky = StickyModifier.CTRL, minWidth = 44.dp, fontSize = TrackpadType.Caption)
private val KeyAlt = KeySpec("Alt", KeyKind.STICKY, sticky = StickyModifier.ALT, minWidth = 40.dp, fontSize = TrackpadType.Caption)
private val KeyShift = KeySpec("Shift", KeyKind.STICKY, sticky = StickyModifier.SHIFT, minWidth = 48.dp, weight = 1.2f, fontSize = TrackpadType.Caption)
private val KeyWin = KeySpec("Win", KeyKind.STICKY, sticky = StickyModifier.WIN, minWidth = 42.dp, fontSize = TrackpadType.Caption)

private val KeyLeft = KeySpec("←", KeyKind.NAV, KeyEvent.KEYCODE_DPAD_LEFT, descriptionRes = R.string.key_left_arrow, minWidth = 38.dp, weight = 0.9f)
private val KeyUp = KeySpec("↑", KeyKind.NAV, KeyEvent.KEYCODE_DPAD_UP, descriptionRes = R.string.key_up_arrow, minWidth = 38.dp, weight = 0.9f)
private val KeyDown = KeySpec("↓", KeyKind.NAV, KeyEvent.KEYCODE_DPAD_DOWN, descriptionRes = R.string.key_down_arrow, minWidth = 38.dp, weight = 0.9f)
private val KeyRight = KeySpec("→", KeyKind.NAV, KeyEvent.KEYCODE_DPAD_RIGHT, descriptionRes = R.string.key_right_arrow, minWidth = 38.dp, weight = 0.9f)
private val KeyBackspace = KeySpec("⌫", KeyKind.NAV, KeyEvent.KEYCODE_DEL, descriptionRes = R.string.key_backspace, minWidth = 42.dp, weight = 1.05f)
private val KeyEnter = KeySpec("↵", KeyKind.NAV, KeyEvent.KEYCODE_ENTER, descriptionRes = R.string.key_enter, minWidth = 44.dp, weight = 1.1f)

// Backspace is fenced off from the arrows and kept apart from Enter so a mis-tap never sends Enter.
private val EditingCluster: List<TrayItem> = listOf(
    DividerItem,
    KeyItem(KeyBackspace),
    GapItem(4.dp),
    KeyItem(KeyEnter)
)

private val WideRow: List<TrayItem> = listOf(
    KeyItem(KeyEsc), KeyItem(KeyTab),
    DividerItem,
    KeyItem(KeyCtrl), KeyItem(KeyAlt), KeyItem(KeyShift), KeyItem(KeyWin),
    DividerItem,
    KeyItem(KeyCopy), KeyItem(KeyPaste), KeyItem(KeyAltTab), KeyItem(KeyDesktop),
    DividerItem,
    KeyItem(KeyLeft), KeyItem(KeyUp), KeyItem(KeyDown), KeyItem(KeyRight)
) + EditingCluster

private val PortraitRowPresets: List<TrayItem> = listOf(
    KeyItem(KeyEsc), KeyItem(KeyTab),
    DividerItem,
    KeyItem(KeyCopy), KeyItem(KeyPaste), KeyItem(KeyAltTab), KeyItem(KeyDesktop)
)

private val PortraitRowKeys: List<TrayItem> = listOf(
    KeyItem(KeyCtrl), KeyItem(KeyAlt), KeyItem(KeyShift), KeyItem(KeyWin),
    DividerItem,
    KeyItem(KeyLeft), KeyItem(KeyUp), KeyItem(KeyDown), KeyItem(KeyRight)
) + EditingCluster

/**
 * Recessed hardware shortcut tray:
 * - Portrait (< 600dp wide): 2 rows whose keys share the full width, so every key
 *   (including Backspace / Enter) is always on screen without horizontal scrolling.
 * - Landscape (>= 600dp wide): 1 unified row (scrolls only if the screen is too narrow).
 */
@Composable
fun PcShortcutToolbar(
    expanded: Boolean,
    stickyModifiers: StickyModifiersState,
    onToggleModifier: (StickyModifier) -> Unit,
    onSendKey: (keyCode: Int, presetMetaState: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val isWideLayout = LocalConfiguration.current.screenWidthDp >= 600

    val onKey: (KeySpec) -> Unit = { spec ->
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val sticky = spec.sticky
        if (sticky != null) {
            onToggleModifier(sticky)
        } else {
            onSendKey(spec.keyCode, spec.metaState)
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(TrackpadTokens.DeckSurface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isWideLayout) {
                    TrayRow(
                        items = WideRow,
                        distribute = false,
                        stickyModifiers = stickyModifiers,
                        onKey = onKey
                    )
                } else {
                    TrayRow(
                        items = PortraitRowPresets,
                        distribute = true,
                        stickyModifiers = stickyModifiers,
                        onKey = onKey
                    )
                    TrayRow(
                        items = PortraitRowKeys,
                        distribute = true,
                        stickyModifiers = stickyModifiers,
                        onKey = onKey
                    )
                }
            }

            // Structural hairline separating tray from trackpad
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TrackpadTokens.Hairline)
            )
        }
    }
}

@Composable
private fun TrayRow(
    items: List<TrayItem>,
    distribute: Boolean,
    stickyModifiers: StickyModifiersState,
    onKey: (KeySpec) -> Unit
) {
    val rowModifier = if (distribute) {
        Modifier.fillMaxWidth()
    } else {
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { item ->
            when (item) {
                is KeyItem -> {
                    val spec = item.spec
                    val sizeModifier = if (distribute) {
                        Modifier.weight(spec.weight)
                    } else {
                        Modifier.widthIn(min = spec.minWidth)
                    }
                    HardwareKeycap(
                        spec = spec,
                        active = spec.sticky?.let { stickyModifiers.isActive(it) } ?: false,
                        tightPadding = distribute,
                        onClick = { onKey(spec) },
                        modifier = sizeModifier
                    )
                }

                DividerItem -> TrayDivider()
                is GapItem -> Spacer(modifier = Modifier.width(item.width))
            }
        }
    }
}

private fun StickyModifiersState.isActive(modifier: StickyModifier): Boolean = when (modifier) {
    StickyModifier.CTRL -> ctrl
    StickyModifier.ALT -> alt
    StickyModifier.SHIFT -> shift
    StickyModifier.WIN -> win
}

@Composable
private fun TrayDivider() {
    Spacer(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(22.dp)
            .background(TrackpadTokens.KeycapBorder)
    )
}

/**
 * Tactile keycap: 38dp face over a 2dp skirt (40dp total). Pressed or latched keys sink onto the skirt.
 */
@Composable
private fun HardwareKeycap(
    spec: KeySpec,
    active: Boolean,
    tightPadding: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val isSticky = spec.kind == KeyKind.STICKY
    val sunk = pressed || active
    val latchedLabel = stringResource(R.string.sticky_latched)
    val releasedLabel = stringResource(R.string.sticky_released)
    val description = spec.descriptionRes?.let { stringResource(it) }

    val faceColor = when {
        active -> TrackpadTokens.InkPrimary
        pressed -> TrackpadTokens.SurfacePressed
        spec.kind == KeyKind.ACTION -> TrackpadTokens.KeycapSurface
        else -> TrackpadTokens.KeycapModifier
    }

    val interactionModifier = if (isSticky) {
        Modifier
            .toggleable(
                value = active,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { onClick() }
            )
            .semantics {
                stateDescription = if (active) latchedLabel else releasedLabel
            }
    } else {
        Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                }
            )
    }

    Box(
        modifier = modifier
            .drawBehind {
                // Subtle physical keycap bottom skirt
                if (!sunk) {
                    drawRoundRect(
                        color = TrackpadTokens.KeycapSkirt,
                        topLeft = Offset(0f, KeycapSkirtHeight.toPx()),
                        size = Size(size.width, size.height - KeycapSkirtHeight.toPx()),
                        cornerRadius = CornerRadius(TrackpadRadius.S.toPx(), TrackpadRadius.S.toPx())
                    )
                }
            }
            .padding(bottom = KeycapSkirtHeight),
        propagateMinConstraints = true
    ) {
        Surface(
            modifier = Modifier
                .height(KeycapFaceHeight)
                .offset(y = if (sunk) 1.5.dp else 0.dp)
                .clip(KeycapShape)
                .then(interactionModifier),
            shape = KeycapShape,
            color = faceColor,
            border = BorderStroke(
                width = 1.dp,
                color = if (active) TrackpadTokens.InkPrimary else TrackpadTokens.KeycapBorder
            )
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = if (tightPadding) 3.dp else 8.dp)
            ) {
                Text(
                    text = spec.label,
                    fontSize = spec.fontSize,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) TrackpadTokens.InkInverse else TrackpadTokens.InkPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    // Glyph keys are announced via their description instead of the raw symbol
                    modifier = if (description != null) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    }
                )
                // Signal-orange hardware LED when a sticky modifier is latched
                if (active) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp, end = 2.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(TrackpadTokens.AccentSignal)
                    )
                }
            }
        }
    }
}
