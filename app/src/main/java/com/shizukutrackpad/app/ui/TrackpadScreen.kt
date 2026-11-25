package com.shizukutrackpad.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shizukutrackpad.app.data.DisplayTarget
import com.shizukutrackpad.app.shizuku.ShizukuConnectionStatus
import com.shizukutrackpad.app.ui.theme.TrackpadTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class TapPulseKind {
    LEFT_CLICK,
    RIGHT_CLICK
}

private data class TapPulseEvent(
    val position: Offset,
    val kind: TapPulseKind,
    val id: Long
)

@Composable
fun TrackpadScreen(viewModel: TrackpadViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showSettingsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    DisposableEffect(context, uiState.settings.keepScreenOn) {
        val window = context.findActivity()?.window
        if (uiState.settings.keepScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val isCompactHeight = LocalConfiguration.current.screenHeightDp < 500
    var isDragLockedUi by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TrackpadTokens.ChassisBlack)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Unified borderless/hairline top hardware chrome strip
        HardwareTopStrip(
            compact = isCompactHeight,
            status = uiState.shizuku.status,
            errorMessage = uiState.shizuku.errorMessage,
            displays = uiState.displays,
            selectedDisplay = uiState.selectedDisplay,
            isShortcutExpanded = uiState.isShortcutToolbarExpanded,
            hasLatchedModifier = uiState.stickyModifiers.anyActive,
            isKeyboardActive = uiState.isKeyboardBarVisible,
            isRealtimeAsciiMode = uiState.isRealtimeAsciiMode,
            onRequestPermission = viewModel::requestShizukuPermission,
            onReconnect = viewModel::reconnectService,
            onSelectDisplay = viewModel::selectDisplay,
            onRefreshDisplays = viewModel::refreshDisplays,
            onToggleKeyboard = viewModel::toggleKeyboardBar,
            onToggleRealtimeMode = viewModel::toggleRealtimeAsciiMode,
            onToggleShortcutTray = viewModel::toggleShortcutToolbar,
            onOpenSettings = { showSettingsSheet = true }
        )

        // Recessed hardware shortcut tray (1 row in landscape, 2 rows in portrait)
        PcShortcutToolbar(
            expanded = uiState.isShortcutToolbarExpanded,
            stickyModifiers = uiState.stickyModifiers,
            onToggleModifier = viewModel::toggleStickyModifier,
            onSendKey = viewModel::sendKeyShortcut
        )

        // Streamlined single-line Korean input bar (or 1dp invisible direct ASCII field)
        KoreanInputBar(
            visible = uiState.isKeyboardBarVisible,
            isRealtimeAsciiMode = uiState.isRealtimeAsciiMode,
            onToggleRealtimeMode = viewModel::toggleRealtimeAsciiMode,
            onAutoSwitchToKoreanBar = { viewModel.setRealtimeAsciiMode(false) },
            onSendPasteText = viewModel::sendPasteText,
            onRealtimeAsciiChar = viewModel::sendRealtimeAsciiChar,
            onRealtimeBackspace = viewModel::sendRealtimeBackspace,
            onRealtimeEnter = viewModel::sendRealtimeEnter
        )

        // Unified Trackpad + Integrated Hardware Clickpad Monolith
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = if (isCompactHeight) 6.dp else 8.dp)
        ) {
            val monolithBorderColor = if (isDragLockedUi) {
                TrackpadTokens.AccentSignal
            } else {
                TrackpadTokens.Hairline
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(TrackpadTokens.ChassisBlack)
                    .border(
                        width = if (isDragLockedUi) 1.5.dp else 1.dp,
                        color = monolithBorderColor,
                        shape = RoundedCornerShape(16.dp)
                    )
            ) {
                TrackpadCanvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    status = uiState.shizuku.status,
                    cursorX = uiState.shizuku.cursorX,
                    cursorY = uiState.shizuku.cursorY,
                    isDragLocked = isDragLockedUi,
                    onDragLockChanged = { isDragLockedUi = it },
                    onRequestPermission = viewModel::requestShizukuPermission,
                    onMove = viewModel::onTrackpadMove,
                    onDragEnd = viewModel::onTrackpadDragEnd,
                    onLeftClick = viewModel::onTrackpadTap,
                    onRightClick = viewModel::onTwoFingerTap,
                    onTwoFingerScroll = viewModel::onTwoFingerScroll,
                    onBeginDragAndDrop = viewModel::onBeginLeftDrag,
                    onDragAndDropMove = viewModel::onLeftDragMove,
                    onEndDragAndDrop = viewModel::onEndLeftDrag
                )

                AnimatedVisibility(
                    visible = uiState.settings.showPhysicalButtons,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    IntegratedClickpadFloor(
                        compact = isCompactHeight,
                        onLeftButtonDown = viewModel::onBeginLeftDrag,
                        onLeftButtonUp = viewModel::onEndLeftDrag,
                        onRightClick = viewModel::onTwoFingerTap
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        TrackpadSettingsBottomSheet(
            settings = uiState.settings,
            onDismiss = { showSettingsSheet = false },
            onSensitivityChange = viewModel::setSensitivity,
            onNaturalScrollChange = viewModel::setNaturalScroll,
            onShowPhysicalButtonsChange = viewModel::setShowPhysicalButtons,
            onKeepScreenOnChange = viewModel::setKeepScreenOn,
            onResetCursor = viewModel::resetCursorToCenter
        )
    }
}

@Composable
private fun HardwareTopStrip(
    compact: Boolean,
    status: ShizukuConnectionStatus,
    errorMessage: String?,
    displays: List<DisplayTarget>,
    selectedDisplay: DisplayTarget?,
    isShortcutExpanded: Boolean,
    hasLatchedModifier: Boolean,
    isKeyboardActive: Boolean,
    isRealtimeAsciiMode: Boolean,
    onRequestPermission: () -> Unit,
    onReconnect: () -> Unit,
    onSelectDisplay: (DisplayTarget) -> Unit,
    onRefreshDisplays: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onToggleRealtimeMode: () -> Unit,
    onToggleShortcutTray: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val view = LocalView.current
    var displayMenuExpanded by remember { mutableStateOf(false) }

    val triggerHaptic: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    val ledColor = when (status) {
        ShizukuConnectionStatus.CONNECTED -> TrackpadTokens.LedOnline
        ShizukuConnectionStatus.CONNECTING,
        ShizukuConnectionStatus.PERMISSION_REQUIRED -> TrackpadTokens.LedWarn
        ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING,
        ShizukuConnectionStatus.ERROR -> TrackpadTokens.LedError
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrackpadTokens.DeckSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 38.dp else 44.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Connection status: quiet 6dp LED when connected; high-contrast action pill only when action needed
            when (status) {
                ShizukuConnectionStatus.CONNECTED -> {
                    Box(
                        modifier = Modifier
                            .padding(start = 2.dp, end = 4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ledColor)
                    )
                }

                ShizukuConnectionStatus.PERMISSION_REQUIRED,
                ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING -> {
                    Surface(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                triggerHaptic()
                                onRequestPermission()
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = TrackpadTokens.InkPrimary
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(ledColor)
                            )
                            Text(
                                text = if (status == ShizukuConnectionStatus.PERMISSION_REQUIRED) {
                                    "권한 허용"
                                } else {
                                    "Shizuku 확인"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TrackpadTokens.InkInverse
                            )
                        }
                    }
                }

                ShizukuConnectionStatus.CONNECTING,
                ShizukuConnectionStatus.ERROR -> {
                    Surface(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                triggerHaptic()
                                onReconnect()
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = TrackpadTokens.KeycapSurface
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(ledColor)
                            )
                            Text(
                                text = if (status == ShizukuConnectionStatus.CONNECTING) "연결 중" else "재연결",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = TrackpadTokens.InkPrimary
                            )
                        }
                    }
                }
            }

            // Flat, borderless display selector chip with tabular monospace resolution
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(TrackpadTokens.KeycapModifier)
                        .clickable {
                            triggerHaptic()
                            displayMenuExpanded = true
                        }
                        .padding(horizontal = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val displayTag = when {
                        selectedDisplay == null -> "NONE"
                        selectedDisplay.isExternal -> "EXT"
                        else -> "INT"
                    }
                    Text(
                        text = displayTag,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        color = if (selectedDisplay?.isExternal == true) {
                            TrackpadTokens.AccentSignal
                        } else {
                            TrackpadTokens.InkMuted
                        }
                    )
                    Text(
                        text = selectedDisplay?.let {
                            "D${it.displayId} · ${it.width}×${it.height}"
                        } ?: "디스플레이 없음",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TrackpadTokens.InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "▾",
                        fontSize = 10.sp,
                        color = TrackpadTokens.InkMuted
                    )
                }

                DropdownMenu(
                    expanded = displayMenuExpanded,
                    onDismissRequest = { displayMenuExpanded = false },
                    containerColor = TrackpadTokens.KeycapSurface
                ) {
                    displays.forEach { display ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = display.label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TrackpadTokens.InkPrimary,
                                        fontWeight = if (display.isExternal) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        }
                                    )
                                    Text(
                                        text = display.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TrackpadTokens.InkSecondary
                                    )
                                }
                            },
                            onClick = {
                                displayMenuExpanded = false
                                onSelectDisplay(display)
                            }
                        )
                    }
                    HorizontalDivider(color = TrackpadTokens.Hairline)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "디스플레이 목록 갱신",
                                color = TrackpadTokens.AccentSignal,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp
                            )
                        },
                        onClick = {
                            displayMenuExpanded = false
                            onRefreshDisplays()
                        }
                    )
                }
            }

            // Right utility icon cluster (Keyboard + mode micro-pill, Shortcut tray, Settings)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isKeyboardActive && isRealtimeAsciiMode) {
                    Surface(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable {
                                triggerHaptic()
                                onToggleRealtimeMode()
                            },
                        shape = RoundedCornerShape(7.dp),
                        color = TrackpadTokens.KeycapSurface
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 7.dp)
                        ) {
                            Text(
                                text = "EN",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = TrackpadTokens.AccentSignal
                            )
                        }
                    }
                }

                // Keyboard icon button
                ChromeIconButton(
                    active = isKeyboardActive,
                    showSignalDot = false,
                    description = "키보드 토글",
                    onClick = {
                        triggerHaptic()
                        onToggleKeyboard()
                    }
                ) { tint ->
                    KeyboardVectorIcon(tint = tint, modifier = Modifier.size(18.dp))
                }

                // Shortcut tray collapse/expand icon button (calm recessed active fill; reserves stark white for active input states)
                ChromeIconButton(
                    active = false,
                    subtleActive = isShortcutExpanded,
                    showSignalDot = !isShortcutExpanded && hasLatchedModifier,
                    description = if (isShortcutExpanded) "단축키 툴바 접기" else "단축키 툴바 펼치기",
                    onClick = {
                        triggerHaptic()
                        onToggleShortcutTray()
                    }
                ) { tint ->
                    ShortcutTrayVectorIcon(
                        tint = tint,
                        expanded = isShortcutExpanded,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Settings sliders icon button
                ChromeIconButton(
                    active = false,
                    showSignalDot = false,
                    description = "트랙패드 설정",
                    onClick = {
                        triggerHaptic()
                        onOpenSettings()
                    }
                ) { tint ->
                    SlidersVectorIcon(tint = tint, modifier = Modifier.size(17.dp))
                }
            }
        }

        if (!errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = TrackpadTokens.LedError,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 5.dp)
            )
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.75.dp)
                .background(TrackpadTokens.Hairline)
        )
    }
}

@Composable
private fun ChromeIconButton(
    active: Boolean,
    subtleActive: Boolean = false,
    showSignalDot: Boolean,
    description: String,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit
) {
    val bgColor = when {
        active -> TrackpadTokens.InkPrimary
        subtleActive -> TrackpadTokens.SurfacePressed
        else -> TrackpadTokens.KeycapModifier
    }
    val tintColor = when {
        active -> TrackpadTokens.InkInverse
        subtleActive -> TrackpadTokens.InkPrimary
        else -> TrackpadTokens.InkSecondary
    }

    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .semantics { contentDescription = description }
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.align(Alignment.Center)) {
            icon(tintColor)
        }
        if (showSignalDot) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(TrackpadTokens.AccentSignal)
            )
        }
    }
}

@Composable
private fun KeyboardVectorIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.4.dp.toPx()
        val padY = size.height * 0.16f
        val rectH = size.height - padY * 2f
        drawRoundRect(
            color = tint,
            topLeft = Offset(stroke / 2f, padY),
            size = Size(size.width - stroke, rectH),
            cornerRadius = CornerRadius(2.5.dp.toPx(), 2.5.dp.toPx()),
            style = Stroke(width = stroke)
        )
        val r = 0.85.dp.toPx()
        val row1 = padY + rectH * 0.34f
        val row2 = padY + rectH * 0.68f
        for (xFrac in listOf(0.24f, 0.41f, 0.59f, 0.76f)) {
            drawCircle(color = tint, radius = r, center = Offset(size.width * xFrac, row1))
        }
        drawLine(
            color = tint,
            start = Offset(size.width * 0.28f, row2),
            end = Offset(size.width * 0.72f, row2),
            strokeWidth = 1.3.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun ShortcutTrayVectorIcon(tint: Color, expanded: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.4.dp.toPx()
        val w = size.width
        val h = size.height
        // Top key-bar rail
        drawLine(
            color = tint,
            start = Offset(w * 0.16f, h * 0.28f),
            end = Offset(w * 0.84f, h * 0.28f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        // Minimal directional chevron (up when expanded, down when collapsed)
        val midY = if (expanded) h * 0.54f else h * 0.72f
        val wingY = if (expanded) h * 0.74f else h * 0.52f
        drawLine(
            color = tint,
            start = Offset(w * 0.30f, wingY),
            end = Offset(w * 0.50f, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, midY),
            end = Offset(w * 0.70f, wingY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun SlidersVectorIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.35.dp.toPx()
        val w = size.width
        val h = size.height
        val knobR = 2.0.dp.toPx()

        val rows = listOf(0.28f to 0.36f, 0.50f to 0.68f, 0.72f to 0.44f)
        for ((yFrac, knobFrac) in rows) {
            val y = h * yFrac
            val kx = w * knobFrac
            drawLine(
                color = tint,
                start = Offset(w * 0.12f, y),
                end = Offset(kx - knobR, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = tint,
                start = Offset(kx + knobR, y),
                end = Offset(w * 0.88f, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = tint,
                radius = knobR,
                center = Offset(kx, y),
                style = Stroke(width = stroke)
            )
        }
    }
}

@Composable
private fun TrackpadCanvas(
    modifier: Modifier = Modifier,
    status: ShizukuConnectionStatus,
    cursorX: Float,
    cursorY: Float,
    isDragLocked: Boolean,
    onDragLockChanged: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    onTwoFingerScroll: (dx: Float, dy: Float) -> Unit,
    onBeginDragAndDrop: () -> Unit,
    onDragAndDropMove: (dx: Float, dy: Float) -> Unit,
    onEndDragAndDrop: () -> Unit
) {
    val view = LocalView.current
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnLeftClick by rememberUpdatedState(onLeftClick)
    val latestOnRightClick by rememberUpdatedState(onRightClick)
    val latestOnTwoFingerScroll by rememberUpdatedState(onTwoFingerScroll)
    val latestOnBeginDragAndDrop by rememberUpdatedState(onBeginDragAndDrop)
    val latestOnDragAndDropMove by rememberUpdatedState(onDragAndDropMove)
    val latestOnEndDragAndDrop by rememberUpdatedState(onEndDragAndDrop)
    val latestOnDragLockChanged by rememberUpdatedState(onDragLockChanged)

    var lastPulse by remember { mutableStateOf<TapPulseEvent?>(null) }
    var isCursorActive by remember { mutableStateOf(false) }
    val tapPulse = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val coordAlpha by animateFloatAsState(
        targetValue = if (isCursorActive) 0.82f else 0.32f,
        animationSpec = tween(durationMillis = 320),
        label = "coordAlpha"
    )

    LaunchedEffect(cursorX, cursorY) {
        isCursorActive = true
        delay(900L)
        isCursorActive = false
    }

    LaunchedEffect(lastPulse?.id) {
        if (lastPulse != null) {
            tapPulse.snapTo(0f)
            tapPulse.animateTo(1f, animationSpec = tween(durationMillis = 240))
            lastPulse = null
        }
    }

    Box(
        modifier = modifier
            .background(TrackpadTokens.ChassisBlack)
            .pointerInput(Unit) {
                val singleTapSlopPx = 12f
                val singleTapTimeoutMs = 260L
                val doubleTapWindowMs = 280L
                val doubleTapMaxDistPx = 85f
                val dragLockSlopPx = 8f
                val twoFingerScrollSlopPx = 10f
                val twoFingerTapTimeoutMs = 320L

                var lastSingleTapUpTime = 0L
                var lastSingleTapPos = Offset.Zero

                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val downPos = firstDown.position
                    val downTime = firstDown.uptimeMillis

                    val isDoubleTapCandidate =
                        (downTime - lastSingleTapUpTime) <= doubleTapWindowMs &&
                            hypot(
                                (downPos.x - lastSingleTapPos.x).toDouble(),
                                (downPos.y - lastSingleTapPos.y).toDouble()
                            ).toFloat() <= doubleTapMaxDistPx

                    var maxPointerCount = 1
                    var isOneFingerMoving = false
                    var isDragAndDropActive = false
                    var isTwoFingerScrolling = false
                    var twoFingerTravel = 0f
                    var lastTwoFingerCenter = downPos

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size > maxPointerCount) {
                            maxPointerCount = pressed.size
                        }

                        if (pressed.isEmpty()) {
                            val upChange = event.changes.firstOrNull { it.id == firstDown.id }
                                ?: event.changes.firstOrNull()
                            val upTime = upChange?.uptimeMillis ?: downTime
                            val elapsed = upTime - downTime

                            when {
                                isDragAndDropActive -> {
                                    latestOnEndDragAndDrop()
                                    latestOnDragLockChanged(false)
                                    lastSingleTapUpTime = 0L
                                }

                                maxPointerCount == 2 && !isTwoFingerScrolling && !isOneFingerMoving && elapsed <= twoFingerTapTimeoutMs -> {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    latestOnRightClick()
                                    lastSingleTapUpTime = 0L
                                    coroutineScope.launch {
                                        lastPulse = TapPulseEvent(
                                            position = lastTwoFingerCenter,
                                            kind = TapPulseKind.RIGHT_CLICK,
                                            id = System.nanoTime()
                                        )
                                    }
                                }

                                maxPointerCount == 1 && !isOneFingerMoving && elapsed <= singleTapTimeoutMs -> {
                                    latestOnLeftClick()
                                    if (isDoubleTapCandidate) {
                                        lastSingleTapUpTime = 0L
                                    } else {
                                        lastSingleTapUpTime = upTime
                                        lastSingleTapPos = downPos
                                    }
                                    coroutineScope.launch {
                                        lastPulse = TapPulseEvent(
                                            position = downPos,
                                            kind = TapPulseKind.LEFT_CLICK,
                                            id = System.nanoTime()
                                        )
                                    }
                                }

                                isOneFingerMoving -> {
                                    latestOnDragEnd()
                                    lastSingleTapUpTime = 0L
                                }

                                else -> {
                                    lastSingleTapUpTime = 0L
                                }
                            }
                            break
                        }

                        if (pressed.size == 2 && !isDragAndDropActive) {
                            val p0 = pressed[0]
                            val p1 = pressed[1]
                            val d0 = p0.positionChange()
                            val d1 = p1.positionChange()
                            val avgDx = (d0.x + d1.x) / 2f
                            val avgDy = (d0.y + d1.y) / 2f
                            lastTwoFingerCenter = Offset(
                                (p0.position.x + p1.position.x) / 2f,
                                (p0.position.y + p1.position.y) / 2f
                            )

                            if (avgDx != 0f || avgDy != 0f) {
                                twoFingerTravel += hypot(avgDx.toDouble(), avgDy.toDouble()).toFloat()
                                if (!isTwoFingerScrolling && twoFingerTravel >= twoFingerScrollSlopPx) {
                                    isTwoFingerScrolling = true
                                }
                                if (isTwoFingerScrolling) {
                                    p0.consume()
                                    p1.consume()
                                    latestOnTwoFingerScroll(avgDx, avgDy)
                                }
                            }
                            continue
                        }

                        if (pressed.size == 1 && maxPointerCount == 1) {
                            val primary = pressed.first()
                            val delta = primary.positionChange()
                            if (delta != Offset.Zero) {
                                val distFromDown = hypot(
                                    (primary.position.x - downPos.x).toDouble(),
                                    (primary.position.y - downPos.y).toDouble()
                                ).toFloat()

                                if (isDoubleTapCandidate) {
                                    if (!isDragAndDropActive && distFromDown >= dragLockSlopPx) {
                                        isDragAndDropActive = true
                                        latestOnDragLockChanged(true)
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        latestOnBeginDragAndDrop()
                                    }
                                    if (isDragAndDropActive) {
                                        primary.consume()
                                        latestOnDragAndDropMove(delta.x, delta.y)
                                    }
                                } else {
                                    if (!isOneFingerMoving && distFromDown >= singleTapSlopPx) {
                                        isOneFingerMoving = true
                                    }
                                    if (isOneFingerMoving) {
                                        primary.consume()
                                        latestOnMove(delta.x, delta.y)
                                    }
                                }
                            }
                        }
                    }
                }
            }
    ) {
        // Ultra-subtle OLED micro-dot matrix + crisp monochrome/signal stroke-ring pulse
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 32.dp.toPx()
            val dotRadius = 0.85.dp.toPx()
            var y = spacing
            while (y < size.height) {
                var x = spacing
                while (x < size.width) {
                    drawCircle(
                        color = TrackpadTokens.TrackpadDot,
                        radius = dotRadius,
                        center = Offset(x, y)
                    )
                    x += spacing
                }
                y += spacing
            }

            val pulse = lastPulse
            if (pulse != null) {
                val p = tapPulse.value
                val alpha = (1f - p) * 0.72f
                val strokeColor = when (pulse.kind) {
                    TapPulseKind.LEFT_CLICK -> TrackpadTokens.InkPrimary.copy(alpha = alpha)
                    TapPulseKind.RIGHT_CLICK -> TrackpadTokens.AccentSignal.copy(alpha = alpha)
                }
                val outerR = (12.dp.toPx() + p * 26.dp.toPx())
                drawCircle(
                    color = strokeColor,
                    radius = outerR,
                    center = pulse.position,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                if (pulse.kind == TapPulseKind.RIGHT_CLICK) {
                    drawCircle(
                        color = strokeColor.copy(alpha = alpha * 0.55f),
                        radius = outerR * 0.62f,
                        center = pulse.position,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }
        }

        // Whisper-quiet raw coordinates in top-right (zero box clutter) + signal DRAG indicator
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isDragLocked) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(TrackpadTokens.AccentSignal)
                    )
                    Text(
                        text = "DRAG",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.8.sp,
                        color = TrackpadTokens.AccentSignal
                    )
                }
            }

            Text(
                text = "${cursorX.roundToInt()}, ${cursorY.roundToInt()}",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TrackpadTokens.InkSecondary,
                modifier = Modifier.alpha(coordAlpha)
            )
        }

        // Clean centered call-to-action only when disconnected
        if (status != ShizukuConnectionStatus.CONNECTED) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = when (status) {
                        ShizukuConnectionStatus.PERMISSION_REQUIRED -> "Shizuku 권한이 필요합니다"
                        ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING -> "Shizuku 서비스가 실행 중이 아닙니다"
                        ShizukuConnectionStatus.CONNECTING -> "입력 서비스 연결 중..."
                        else -> "Shizuku 연결 대기 중"
                    },
                    fontSize = 13.sp,
                    color = TrackpadTokens.InkSecondary
                )
                if (status == ShizukuConnectionStatus.PERMISSION_REQUIRED ||
                    status == ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING
                ) {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable(onClick = onRequestPermission),
                        shape = RoundedCornerShape(9.dp),
                        color = TrackpadTokens.InkPrimary
                    ) {
                        Text(
                            text = "권한 허용",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TrackpadTokens.InkInverse,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IntegratedClickpadFloor(
    compact: Boolean,
    onLeftButtonDown: () -> Unit,
    onLeftButtonUp: () -> Unit,
    onRightClick: () -> Unit
) {
    val view = LocalView.current
    val latestLeftDown by rememberUpdatedState(onLeftButtonDown)
    val latestLeftUp by rememberUpdatedState(onLeftButtonUp)
    val latestRightClick by rememberUpdatedState(onRightClick)

    var leftPressed by remember { mutableStateOf(false) }
    var rightPressed by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Flush hairline separating touch canvas from clickpad floor
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.75.dp)
                .background(TrackpadTokens.Hairline)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 38.dp else 44.dp)
                .background(TrackpadTokens.ClickpadFloor),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left click zone (supports tap and press-and-hold drag)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (leftPressed) TrackpadTokens.ClickpadPressed else TrackpadTokens.ClickpadFloor
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            leftPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            latestLeftDown()
                            try {
                                waitForUpOrCancellation()
                            } finally {
                                leftPressed = false
                                latestLeftUp()
                            }
                        }
                    }
            ) {
                if (leftPressed) {
                    Spacer(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(1.5.dp)
                            .background(TrackpadTokens.InkPrimary)
                    )
                }
                Text(
                    text = "L",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (leftPressed) TrackpadTokens.InkPrimary else TrackpadTokens.InkMuted,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Precision vertical hardware clickpad tick
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(TrackpadTokens.ClickpadSplit)
            )

            // Right click zone
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        if (rightPressed) TrackpadTokens.ClickpadPressed else TrackpadTokens.ClickpadFloor
                    )
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            rightPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            val up = waitForUpOrCancellation()
                            rightPressed = false
                            if (up != null) {
                                latestRightClick()
                            }
                        }
                    }
            ) {
                if (rightPressed) {
                    Spacer(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(1.5.dp)
                            .background(TrackpadTokens.AccentSignal)
                    )
                }
                Text(
                    text = "R",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (rightPressed) TrackpadTokens.InkPrimary else TrackpadTokens.InkMuted,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackpadSettingsBottomSheet(
    settings: TrackpadSettings,
    onDismiss: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onNaturalScrollChange: (Boolean) -> Unit,
    onShowPhysicalButtonsChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onResetCursor: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrackpadTokens.DeckSurface,
        contentColor = TrackpadTokens.InkPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = TrackpadTokens.InkGhost)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "설정",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TrackpadTokens.InkPrimary
            )

            // Sensitivity slider (DPI 0.5x ~ 3.0x)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "포인터 감도",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = TrackpadTokens.InkPrimary
                    )
                    Text(
                        text = "${String.format("%.1f", settings.sensitivity)}x",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TrackpadTokens.AccentSignal
                    )
                }
                Slider(
                    value = settings.sensitivity,
                    onValueChange = onSensitivityChange,
                    valueRange = 0.5f..3.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = TrackpadTokens.InkPrimary,
                        activeTrackColor = TrackpadTokens.AccentSignal,
                        inactiveTrackColor = TrackpadTokens.SurfacePressed
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.5x", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TrackpadTokens.InkMuted)
                    Text("3.0x", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TrackpadTokens.InkMuted)
                }
            }

            HorizontalDivider(color = TrackpadTokens.Hairline)

            SettingSwitchRow(
                title = "자연스러운 스크롤",
                subtitle = if (settings.naturalScroll) "손가락 이동 방향과 동일" else "PC 휠 방향 (반전)",
                checked = settings.naturalScroll,
                onCheckedChange = onNaturalScrollChange
            )

            SettingSwitchRow(
                title = "하단 클릭패드 (L / R)",
                subtitle = "트랙패드 하단에 물리 좌/우 버튼 영역 표시",
                checked = settings.showPhysicalButtons,
                onCheckedChange = onShowPhysicalButtonsChange
            )

            SettingSwitchRow(
                title = "화면 꺼짐 방지",
                subtitle = "사용 중 디스플레이 켜짐 유지",
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange
            )

            HorizontalDivider(color = TrackpadTokens.Hairline)

            // Concise gesture reference (moved off the silent trackpad floor)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "제스처",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TrackpadTokens.InkMuted
                )
                GestureGuideRow("1손가락 탭 / 드래그", "좌클릭 / 커서 이동")
                GestureGuideRow("2손가락 탭 / 드래그", "우클릭 / 상하·좌우 스크롤")
                GestureGuideRow("더블탭 후 드래그", "창 이동 / 텍스트 선택")
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        onResetCursor()
                        onDismiss()
                    },
                shape = RoundedCornerShape(10.dp),
                color = TrackpadTokens.KeycapSurface,
                border = BorderStroke(0.75.dp, TrackpadTokens.KeycapBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "커서 중앙으로 리셋",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TrackpadTokens.InkPrimary
                    )
                }
            }
        }
    }
}

@Composable
private fun GestureGuideRow(gesture: String, action: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = gesture,
            fontSize = 12.sp,
            color = TrackpadTokens.InkSecondary
        )
        Text(
            text = action,
            fontSize = 12.sp,
            color = TrackpadTokens.InkPrimary
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = TrackpadTokens.InkPrimary
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = TrackpadTokens.InkSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TrackpadTokens.InkInverse,
                checkedTrackColor = TrackpadTokens.InkPrimary,
                uncheckedThumbColor = TrackpadTokens.InkSecondary,
                uncheckedTrackColor = TrackpadTokens.SurfacePressed,
                uncheckedBorderColor = TrackpadTokens.KeycapBorder
            )
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
