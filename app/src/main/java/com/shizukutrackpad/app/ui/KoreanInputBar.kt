package com.shizukutrackpad.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shizukutrackpad.app.ui.theme.TrackpadTokens
import kotlinx.coroutines.delay

private const val REALTIME_SENTINEL = "\u200B"

/**
 * Streamlined dual-mode soft keyboard bridge:
 * 1. Direct Hidden Mode (isRealtimeAsciiMode = true): focuses an invisible 1dp BasicTextField
 *    with zero vertical footprint, injecting ASCII/digits/symbols/Backspace/Enter immediately.
 *    Auto-promotes to the visible Korean Input Bar if Hangul/non-ASCII is entered.
 * 2. Korean Input Bar Mode (isRealtimeAsciiMode = false): single-line 48dp recessed hardware strip
 *    ([한/EN] toggle + inset black field + Send button, disabled while empty).
 */
@Composable
fun KoreanInputBar(
    visible: Boolean,
    isRealtimeAsciiMode: Boolean,
    onToggleRealtimeMode: () -> Unit,
    onAutoSwitchToKoreanBar: () -> Unit,
    onSendPasteText: (String) -> Unit,
    onRealtimeAsciiChar: (Char) -> Unit,
    onRealtimeBackspace: () -> Unit,
    onRealtimeEnter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var fieldValue by remember {
        mutableStateOf(
            if (isRealtimeAsciiMode) {
                TextFieldValue(REALTIME_SENTINEL, TextRange(1))
            } else {
                TextFieldValue("")
            }
        )
    }

    val triggerHaptic: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    LaunchedEffect(isRealtimeAsciiMode, visible) {
        if (!visible) {
            keyboardController?.hide()
            return@LaunchedEffect
        }
        if (isRealtimeAsciiMode) {
            if (fieldValue.text != REALTIME_SENTINEL) {
                fieldValue = TextFieldValue(REALTIME_SENTINEL, TextRange(1))
            }
        } else if (fieldValue.text.contains(REALTIME_SENTINEL)) {
            val cleaned = fieldValue.text.replace(REALTIME_SENTINEL, "")
            fieldValue = TextFieldValue(cleaned, TextRange(cleaned.length))
        }
        delay(35L)
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    if (!visible) return

    fun submitCurrentInput() {
        val raw = fieldValue.text.replace(REALTIME_SENTINEL, "")
        if (isRealtimeAsciiMode || raw.isNotEmpty()) {
            triggerHaptic()
        }
        if (isRealtimeAsciiMode) {
            if (raw.isNotEmpty()) {
                onSendPasteText(raw)
            } else {
                onRealtimeEnter()
            }
            fieldValue = TextFieldValue(REALTIME_SENTINEL, TextRange(1))
        } else {
            if (raw.isNotEmpty()) {
                onSendPasteText(raw)
                fieldValue = TextFieldValue("")
            }
        }
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    fun handleValueChange(newValue: TextFieldValue) {
        if (!isRealtimeAsciiMode) {
            fieldValue = newValue
            return
        }

        if (newValue.text.isEmpty()) {
            onRealtimeBackspace()
            fieldValue = TextFieldValue(REALTIME_SENTINEL, TextRange(1))
            return
        }

        val typed = newValue.text.replace(REALTIME_SENTINEL, "")
        if (typed.isEmpty()) {
            fieldValue = TextFieldValue(REALTIME_SENTINEL, TextRange(1))
            return
        }

        val allDirectAscii = typed.all { ch -> ch in '\u0020'..'\u007E' || ch == '\n' }
        if (allDirectAscii) {
            for (ch in typed) {
                if (ch == '\n') {
                    onRealtimeEnter()
                } else {
                    onRealtimeAsciiChar(ch)
                }
            }
            fieldValue = TextFieldValue(REALTIME_SENTINEL, TextRange(1))
        } else {
            fieldValue = TextFieldValue(typed, TextRange(typed.length))
            onAutoSwitchToKoreanBar()
        }
    }

    val textFieldContent: @Composable (Modifier) -> Unit = { fieldModifier ->
        BasicTextField(
            value = fieldValue,
            onValueChange = ::handleValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = TrackpadTokens.InkPrimary,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(TrackpadTokens.AccentSignal),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                autoCorrectEnabled = !isRealtimeAsciiMode,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { submitCurrentInput() }
            ),
            modifier = fieldModifier.focusRequester(focusRequester)
        )
    }

    if (isRealtimeAsciiMode) {
        Box(
            modifier = modifier
                .size(1.dp)
                .alpha(0f)
        ) {
            textFieldContent(Modifier.size(1.dp))
        }
    } else {
        val hasPendingText = fieldValue.text.replace(REALTIME_SENTINEL, "").isNotEmpty()
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(TrackpadTokens.DeckSurface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Single-tap mode switch back to hidden direct EN
                Surface(
                    modifier = Modifier
                        .width(44.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button) {
                            triggerHaptic()
                            onToggleRealtimeMode()
                        }
                        .semantics {
                            contentDescription = "한글 입력창 사용 중. 탭하면 영문 직접 입력으로 전환"
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = TrackpadTokens.KeycapSurface,
                    border = BorderStroke(1.dp, TrackpadTokens.KeycapBorder)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "한",
                            fontSize = 14.sp,
                            modifier = Modifier.clearAndSetSemantics { },
                            fontWeight = FontWeight.SemiBold,
                            color = TrackpadTokens.AccentSignal
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TrackpadTokens.ChassisBlack)
                        .border(1.dp, TrackpadTokens.KeycapBorder, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (fieldValue.text.replace(REALTIME_SENTINEL, "").isEmpty()) {
                        Text(
                            text = "외장 화면에 보낼 글자 입력",
                            fontSize = 14.sp,
                            color = TrackpadTokens.InkMuted,
                            maxLines = 1
                        )
                    }
                    textFieldContent(Modifier.fillMaxWidth())
                }

                Surface(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = hasPendingText, role = Role.Button) { submitCurrentInput() },
                    shape = RoundedCornerShape(8.dp),
                    color = if (hasPendingText) TrackpadTokens.InkPrimary else TrackpadTokens.SurfacePressed
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "전송",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasPendingText) TrackpadTokens.InkInverse else TrackpadTokens.InkSecondary
                        )
                    }
                }
            }

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(TrackpadTokens.Hairline)
            )
        }
    }
}
