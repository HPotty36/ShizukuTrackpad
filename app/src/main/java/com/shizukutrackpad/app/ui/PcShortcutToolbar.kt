package com.shizukutrackpad.app.ui

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shizukutrackpad.app.ui.theme.TrackpadTokens

/**
 * Recessed hardware shortcut tray:
 * - Portrait (< 600dp wide): 2 balanced tactile keycap rows.
 * - Landscape (>= 600dp wide): 1 unified full-width hardware key row reclaiming ~78dp for trackpad.
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

    val triggerHaptic: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
            if (isWideLayout) {
                // Single unified full-width row on landscape phones / tablets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    HardwareKeycap(
                        label = "Esc",
                        minWidth = 38.dp,
                        isModifierStyle = false,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_ESCAPE, 0)
                        }
                    )
                    HardwareKeycap(
                        label = "Tab",
                        minWidth = 38.dp,
                        isModifierStyle = false,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_TAB, 0)
                        }
                    )

                    TrayDivider()

                    StickyHardwareKeycap(
                        label = "Ctrl",
                        active = stickyModifiers.ctrl,
                        minWidth = 40.dp,
                        onClick = {
                            triggerHaptic()
                            onToggleModifier(StickyModifier.CTRL)
                        }
                    )
                    StickyHardwareKeycap(
                        label = "Alt",
                        active = stickyModifiers.alt,
                        minWidth = 36.dp,
                        onClick = {
                            triggerHaptic()
                            onToggleModifier(StickyModifier.ALT)
                        }
                    )
                    StickyHardwareKeycap(
                        label = "Shift",
                        active = stickyModifiers.shift,
                        minWidth = 44.dp,
                        onClick = {
                            triggerHaptic()
                            onToggleModifier(StickyModifier.SHIFT)
                        }
                    )
                    StickyHardwareKeycap(
                        label = "Win",
                        active = stickyModifiers.win,
                        minWidth = 38.dp,
                        onClick = {
                            triggerHaptic()
                            onToggleModifier(StickyModifier.WIN)
                        }
                    )

                    TrayDivider()

                    HardwareKeycap(
                        label = "Ctrl+C",
                        minWidth = 52.dp,
                        isModifierStyle = false,
                        onClick = {
                            triggerHaptic()
                            onSendKey(
                                KeyEvent.KEYCODE_C,
                                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                            )
                        }
                    )
                    HardwareKeycap(
                        label = "Ctrl+V",
                        minWidth = 52.dp,
                        isModifierStyle = false,
                        onClick = {
                            triggerHaptic()
                            onSendKey(
                                KeyEvent.KEYCODE_V,
                                KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                            )
                        }
                    )
                    HardwareKeycap(
                        label = "Alt+Tab",
                        minWidth = 56.dp,
                        isModifierStyle = false,
                        onClick = {
                            triggerHaptic()
                            onSendKey(
                                KeyEvent.KEYCODE_TAB,
                                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                            )
                        }
                    )
                    HardwareKeycap(
                        label = "Win+D",
                        minWidth = 52.dp,
                        isModifierStyle = false,
                        onClick = {
                            triggerHaptic()
                            onSendKey(
                                KeyEvent.KEYCODE_D,
                                KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                            )
                        }
                    )

                    TrayDivider()

                    NavHardwareKeycap(
                        label = "←",
                        description = "Left Arrow",
                        minWidth = 34.dp,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_DPAD_LEFT, 0)
                        }
                    )
                    NavHardwareKeycap(
                        label = "↑",
                        description = "Up Arrow",
                        minWidth = 34.dp,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_DPAD_UP, 0)
                        }
                    )
                    NavHardwareKeycap(
                        label = "↓",
                        description = "Down Arrow",
                        minWidth = 34.dp,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
                        }
                    )
                    NavHardwareKeycap(
                        label = "→",
                        description = "Right Arrow",
                        minWidth = 34.dp,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                        }
                    )
                    NavHardwareKeycap(
                        label = "⌫",
                        description = "Backspace",
                        minWidth = 38.dp,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_DEL, 0)
                        }
                    )
                    NavHardwareKeycap(
                        label = "↵",
                        description = "Enter",
                        minWidth = 40.dp,
                        onClick = {
                            triggerHaptic()
                            onSendKey(KeyEvent.KEYCODE_ENTER, 0)
                        }
                    )
                }
            } else {
                // Portrait 2-row hardware tray
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    // Row 1: Esc, Tab + scrollable preset macros
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        HardwareKeycap(
                            label = "Esc",
                            minWidth = 44.dp,
                            isModifierStyle = false,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_ESCAPE, 0)
                            }
                        )
                        HardwareKeycap(
                            label = "Tab",
                            minWidth = 44.dp,
                            isModifierStyle = false,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_TAB, 0)
                            }
                        )

                        TrayDivider()

                        HardwareKeycap(
                            label = "Ctrl+C",
                            minWidth = 56.dp,
                            isModifierStyle = false,
                            onClick = {
                                triggerHaptic()
                                onSendKey(
                                    KeyEvent.KEYCODE_C,
                                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                                )
                            }
                        )
                        HardwareKeycap(
                            label = "Ctrl+V",
                            minWidth = 56.dp,
                            isModifierStyle = false,
                            onClick = {
                                triggerHaptic()
                                onSendKey(
                                    KeyEvent.KEYCODE_V,
                                    KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                                )
                            }
                        )
                        HardwareKeycap(
                            label = "Alt+Tab",
                            minWidth = 62.dp,
                            isModifierStyle = false,
                            onClick = {
                                triggerHaptic()
                                onSendKey(
                                    KeyEvent.KEYCODE_TAB,
                                    KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                                )
                            }
                        )
                        HardwareKeycap(
                            label = "Win+D",
                            minWidth = 56.dp,
                            isModifierStyle = false,
                            onClick = {
                                triggerHaptic()
                                onSendKey(
                                    KeyEvent.KEYCODE_D,
                                    KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
                                )
                            }
                        )
                    }

                    // Row 2: Sticky modifiers + Arrows + Backspace/Enter
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StickyHardwareKeycap(
                            label = "Ctrl",
                            active = stickyModifiers.ctrl,
                            minWidth = 40.dp,
                            onClick = {
                                triggerHaptic()
                                onToggleModifier(StickyModifier.CTRL)
                            }
                        )
                        StickyHardwareKeycap(
                            label = "Alt",
                            active = stickyModifiers.alt,
                            minWidth = 36.dp,
                            onClick = {
                                triggerHaptic()
                                onToggleModifier(StickyModifier.ALT)
                            }
                        )
                        StickyHardwareKeycap(
                            label = "Shift",
                            active = stickyModifiers.shift,
                            minWidth = 44.dp,
                            onClick = {
                                triggerHaptic()
                                onToggleModifier(StickyModifier.SHIFT)
                            }
                        )
                        StickyHardwareKeycap(
                            label = "Win",
                            active = stickyModifiers.win,
                            minWidth = 38.dp,
                            onClick = {
                                triggerHaptic()
                                onToggleModifier(StickyModifier.WIN)
                            }
                        )

                        TrayDivider()

                        NavHardwareKeycap(
                            label = "←",
                            description = "Left Arrow",
                            minWidth = 34.dp,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_DPAD_LEFT, 0)
                            }
                        )
                        NavHardwareKeycap(
                            label = "↑",
                            description = "Up Arrow",
                            minWidth = 34.dp,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_DPAD_UP, 0)
                            }
                        )
                        NavHardwareKeycap(
                            label = "↓",
                            description = "Down Arrow",
                            minWidth = 34.dp,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_DPAD_DOWN, 0)
                            }
                        )
                        NavHardwareKeycap(
                            label = "→",
                            description = "Right Arrow",
                            minWidth = 34.dp,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0)
                            }
                        )
                        NavHardwareKeycap(
                            label = "⌫",
                            description = "Backspace",
                            minWidth = 38.dp,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_DEL, 0)
                            }
                        )
                        NavHardwareKeycap(
                            label = "↵",
                            description = "Enter",
                            minWidth = 40.dp,
                            onClick = {
                                triggerHaptic()
                                onSendKey(KeyEvent.KEYCODE_ENTER, 0)
                            }
                        )
                    }
                }
            }

            // Clean structural hairline separating tray from trackpad
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.75.dp)
                    .background(TrackpadTokens.Hairline)
            )
        }
    }
}

@Composable
private fun TrayDivider() {
    Spacer(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .width(1.dp)
            .height(20.dp)
            .background(TrackpadTokens.Hairline)
    )
}

@Composable
private fun HardwareKeycap(
    label: String,
    minWidth: Dp,
    isModifierStyle: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val faceColor = when {
        pressed -> TrackpadTokens.SurfacePressed
        isModifierStyle -> TrackpadTokens.KeycapModifier
        else -> TrackpadTokens.KeycapSurface
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = minWidth)
            .height(34.dp)
            .drawBehind {
                // Subtle 2dp physical keycap bottom skirt
                if (!pressed) {
                    drawRoundRect(
                        color = TrackpadTokens.KeycapSkirt,
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = Size(size.width, size.height - 2.dp.toPx()),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                }
            }
    ) {
        Surface(
            modifier = Modifier
                .defaultMinSize(minWidth = minWidth)
                .height(32.dp)
                .offset(y = if (pressed) 1.5.dp else 0.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(7.dp),
            color = faceColor,
            border = BorderStroke(0.75.dp, TrackpadTokens.KeycapBorder)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 7.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    color = TrackpadTokens.InkPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
private fun StickyHardwareKeycap(
    label: String,
    active: Boolean,
    minWidth: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    val faceColor = when {
        active -> TrackpadTokens.InkPrimary
        pressed -> TrackpadTokens.SurfacePressed
        else -> TrackpadTokens.KeycapModifier
    }

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = minWidth)
            .height(34.dp)
            .drawBehind {
                if (!pressed && !active) {
                    drawRoundRect(
                        color = TrackpadTokens.KeycapSkirt,
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = Size(size.width, size.height - 2.dp.toPx()),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                }
            }
    ) {
        Surface(
            modifier = Modifier
                .defaultMinSize(minWidth = minWidth)
                .height(32.dp)
                .offset(y = if (pressed || active) 1.dp else 0.dp)
                .clip(RoundedCornerShape(7.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(7.dp),
            color = faceColor,
            border = BorderStroke(
                width = 0.75.dp,
                color = if (active) TrackpadTokens.InkPrimary else TrackpadTokens.KeycapBorder
            )
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = minWidth)
                    .padding(horizontal = 7.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = if (active) TrackpadTokens.InkInverse else TrackpadTokens.InkSecondary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(Alignment.Center)
                )
                // Teenage Engineering style 4dp signal-orange hardware LED when latched
                if (active) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 4.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(TrackpadTokens.AccentSignal)
                    )
                }
            }
        }
    }
}

@Composable
private fun NavHardwareKeycap(
    label: String,
    description: String,
    minWidth: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = minWidth)
            .height(34.dp)
            .drawBehind {
                if (!pressed) {
                    drawRoundRect(
                        color = TrackpadTokens.KeycapSkirt,
                        topLeft = Offset(0f, 2.dp.toPx()),
                        size = Size(size.width, size.height - 2.dp.toPx()),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                }
            }
    ) {
        Surface(
            modifier = Modifier
                .defaultMinSize(minWidth = minWidth)
                .height(32.dp)
                .offset(y = if (pressed) 1.5.dp else 0.dp)
                .clip(RoundedCornerShape(7.dp))
                .semantics { contentDescription = description }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(7.dp),
            color = if (pressed) TrackpadTokens.SurfacePressed else TrackpadTokens.KeycapModifier,
            border = BorderStroke(0.75.dp, TrackpadTokens.KeycapBorder)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = TrackpadTokens.InkPrimary,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
