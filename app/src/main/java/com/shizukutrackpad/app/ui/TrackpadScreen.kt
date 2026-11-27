package com.shizukutrackpad.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shizukutrackpad.app.R
import com.shizukutrackpad.app.data.DisplayTarget
import com.shizukutrackpad.app.shizuku.ShizukuConnectionStatus
import com.shizukutrackpad.app.shizuku.ShizukuServiceManager
import com.shizukutrackpad.app.shizuku.TOUCHPAD_LOGICAL_MAX
import com.shizukutrackpad.app.shizuku.TOUCHPAD_PHYSICAL_SIZE_MM
import com.shizukutrackpad.app.shizuku.TouchContact
import com.shizukutrackpad.app.shizuku.TouchpadSystemSettings
import com.shizukutrackpad.app.update.UpdateState
import com.shizukutrackpad.app.ui.theme.TrackpadRadius
import com.shizukutrackpad.app.ui.theme.TrackpadTokens
import com.shizukutrackpad.app.ui.theme.TrackpadType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
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
    val updateState by viewModel.updateState.collectAsState()
    val dismissedUpdateVersion by viewModel.dismissedUpdateVersion.collectAsState()
    val touchpadSettings by viewModel.touchpadSettings.collectAsState()
    val isTouchpadMode = uiState.shizuku.isTouchpadActive
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
            // System bars + display cutout (landscape camera hole) + IME
            .safeDrawingPadding()
    ) {
        // Unified borderless/hairline top hardware chrome strip
        HardwareTopStrip(
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

        UpdateBanner(
            state = updateState,
            dismissedVersion = dismissedUpdateVersion,
            onUpdate = viewModel::startUpdate,
            onDismiss = viewModel::dismissUpdateBanner
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
            onImeDismissed = viewModel::onKeyboardDismissed,
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
                    .clip(RoundedCornerShape(TrackpadRadius.L))
                    .background(TrackpadTokens.ChassisBlack)
                    .border(
                        width = if (isDragLockedUi) 2.dp else 1.dp,
                        color = monolithBorderColor,
                        shape = RoundedCornerShape(TrackpadRadius.L)
                    )
            ) {
                TrackpadCanvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    status = uiState.shizuku.status,
                    errorMessage = uiState.shizuku.errorMessage,
                    selectedDisplay = uiState.selectedDisplay,
                    hasExternalDisplay = uiState.displays.any { it.isExternal },
                    cursorX = uiState.shizuku.cursorX,
                    cursorY = uiState.shizuku.cursorY,
                    isDragLocked = isDragLockedUi,
                    isTouchpadMode = isTouchpadMode,
                    onTouchpadFrame = viewModel::onTouchpadFrame,
                    onDragLockChanged = { isDragLockedUi = it },
                    onRequestPermission = viewModel::requestShizukuPermission,
                    onReconnect = viewModel::reconnectService,
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
                        onLeftButtonDown = viewModel::onClickpadLeftDown,
                        onLeftButtonUp = viewModel::onClickpadLeftUp,
                        onRightClick = viewModel::onClickpadRightClick
                    )
                }
            }
        }
    }

    if (showSettingsSheet) {
        LaunchedEffect(Unit) { viewModel.refreshTouchpadSettings() }
        TrackpadSettingsBottomSheet(
            settings = uiState.settings,
            isTouchpadMode = isTouchpadMode,
            touchpadSettings = touchpadSettings ?: TouchpadSystemSettings(),
            onTouchpadPointerSpeedChange = viewModel::setTouchpadPointerSpeed,
            onTouchpadNaturalScrollingChange = viewModel::setTouchpadNaturalScrolling,
            onTouchpadTapToClickChange = viewModel::setTouchpadTapToClick,
            onTouchpadTapDraggingChange = viewModel::setTouchpadTapDragging,
            onDismiss = { showSettingsSheet = false },
            onSensitivityChange = viewModel::setSensitivity,
            onSensitivityChangeFinished = viewModel::commitSensitivity,
            onNaturalScrollChange = viewModel::setNaturalScroll,
            onShowPhysicalButtonsChange = viewModel::setShowPhysicalButtons,
            onKeepScreenOnChange = viewModel::setKeepScreenOn,
            onResetCursor = viewModel::resetCursorToCenter,
            updateState = updateState,
            currentVersion = viewModel.currentAppVersion,
            onCheckForUpdate = viewModel::checkForUpdate,
            onStartUpdate = viewModel::startUpdate
        )
    }
}

@Composable
private fun HardwareTopStrip(
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
    val connectedDescription = stringResource(R.string.status_connected_description)
    val englishModeDescription = stringResource(R.string.input_mode_english_description)
    val switchToKoreanLabel = stringResource(R.string.input_switch_to_korean)

    val triggerHaptic: () -> Unit = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrackpadTokens.DeckSurface)
    ) {
        // 44dp strip: every control gets a full-height touch area around a smaller visual pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Connection status: quiet 6dp LED when connected; labelled pill only when something is wrong
            when (status) {
                ShizukuConnectionStatus.CONNECTED -> {
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp, end = 4.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(TrackpadTokens.LedOnline)
                            .semantics { contentDescription = connectedDescription }
                    )
                }

                ShizukuConnectionStatus.PERMISSION_REQUIRED,
                ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING -> {
                    StatusPill(
                        label = stringResource(
                            if (status == ShizukuConnectionStatus.PERMISSION_REQUIRED) {
                                R.string.action_request_permission
                            } else {
                                R.string.action_check_again
                            }
                        ),
                        ledColor = if (status == ShizukuConnectionStatus.PERMISSION_REQUIRED) {
                            TrackpadTokens.LedWarnOnLight
                        } else {
                            TrackpadTokens.LedError
                        },
                        emphasized = true,
                        onClick = {
                            triggerHaptic()
                            onRequestPermission()
                        }
                    )
                }

                ShizukuConnectionStatus.CONNECTING -> {
                    // Binding is already in progress: informative only, not tappable
                    StatusPill(
                        label = stringResource(R.string.status_connecting),
                        ledColor = TrackpadTokens.LedWarn,
                        emphasized = false,
                        onClick = null
                    )
                }

                ShizukuConnectionStatus.ERROR -> {
                    StatusPill(
                        label = stringResource(R.string.action_reconnect),
                        ledColor = TrackpadTokens.LedError,
                        emphasized = false,
                        onClick = {
                            triggerHaptic()
                            onReconnect()
                        }
                    )
                }
            }

            // Display selector chip (full-height touch area, 32dp visual)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                val displayTag = selectedDisplay?.let {
                    stringResource(
                        if (it.isExternal) R.string.display_tag_external else R.string.display_tag_internal
                    )
                }
                val displayText = selectedDisplay?.let {
                    "D${it.displayId} · ${it.width}×${it.height}"
                } ?: stringResource(R.string.display_none)
                val displayDescription = selectedDisplay?.let {
                    stringResource(
                        R.string.display_target_description,
                        stringResource(displayKindRes(it)),
                        it.displayId,
                        it.width,
                        it.height
                    )
                } ?: stringResource(R.string.display_target_none_description)

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(TrackpadRadius.S))
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.display_change_action)) {
                            triggerHaptic()
                            displayMenuExpanded = true
                        }
                        .semantics { contentDescription = displayDescription },
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier
                            .height(32.dp)
                            .clip(RoundedCornerShape(TrackpadRadius.S))
                            .background(TrackpadTokens.KeycapModifier)
                            .padding(horizontal = 8.dp)
                            .clearAndSetSemantics { },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (displayTag != null) {
                            Text(
                                text = displayTag,
                                fontSize = TrackpadType.Tag,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedDisplay?.isExternal == true) {
                                    TrackpadTokens.InkPrimary
                                } else {
                                    TrackpadTokens.LedWarn
                                },
                                maxLines = 1
                            )
                        }
                        Text(
                            text = displayText,
                            fontSize = TrackpadType.Caption,
                            fontFamily = FontFamily.Monospace,
                            color = TrackpadTokens.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = "▾",
                            fontSize = TrackpadType.Caption,
                            color = TrackpadTokens.InkMuted
                        )
                    }
                }

                DropdownMenu(
                    expanded = displayMenuExpanded,
                    onDismissRequest = { displayMenuExpanded = false },
                    containerColor = TrackpadTokens.KeycapSurface
                ) {
                    displays.forEach { display ->
                        val isSelected = display.displayId == selectedDisplay?.displayId
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = stringResource(
                                            R.string.display_menu_item,
                                            stringResource(displayKindRes(display)),
                                            display.displayId,
                                            display.width,
                                            display.height
                                        ),
                                        fontSize = TrackpadType.Body,
                                        color = TrackpadTokens.InkPrimary,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    Text(
                                        text = display.name,
                                        fontSize = TrackpadType.Caption,
                                        color = TrackpadTokens.InkSecondary
                                    )
                                }
                            },
                            trailingIcon = {
                                if (isSelected) {
                                    val selectedLabel = stringResource(R.string.display_selected)
                                    Text(
                                        text = "✓",
                                        fontSize = TrackpadType.Title,
                                        fontWeight = FontWeight.Bold,
                                        color = TrackpadTokens.AccentSignal,
                                        modifier = Modifier.semantics { contentDescription = selectedLabel }
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
                                text = stringResource(R.string.display_refresh),
                                color = TrackpadTokens.AccentSignal,
                                fontWeight = FontWeight.Medium,
                                fontSize = TrackpadType.Body
                            )
                        },
                        onClick = {
                            displayMenuExpanded = false
                            onRefreshDisplays()
                        }
                    )
                }
            }

            // Right utility cluster (EN mode pill, Keyboard, Shortcut tray, Settings); 44dp touch areas
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isKeyboardActive && isRealtimeAsciiMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(TrackpadRadius.S))
                            .clickable(role = Role.Button, onClickLabel = switchToKoreanLabel) {
                                triggerHaptic()
                                onToggleRealtimeMode()
                            }
                            .semantics {
                                contentDescription = englishModeDescription
                            }
                            .padding(horizontal = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clearAndSetSemantics { },
                            shape = RoundedCornerShape(TrackpadRadius.S),
                            color = TrackpadTokens.KeycapSurface
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "EN",
                                    fontSize = TrackpadType.Caption,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = TrackpadTokens.AccentSignal
                                )
                            }
                        }
                    }
                }

                ChromeIconButton(
                    active = isKeyboardActive,
                    showSignalDot = false,
                    description = stringResource(R.string.keyboard),
                    toggleState = isKeyboardActive,
                    onClick = {
                        triggerHaptic()
                        onToggleKeyboard()
                    }
                ) { tint ->
                    KeyboardVectorIcon(tint = tint, modifier = Modifier.size(18.dp))
                }

                // Calm recessed fill when expanded; stark white is reserved for active input states
                ChromeIconButton(
                    active = false,
                    subtleActive = isShortcutExpanded,
                    showSignalDot = !isShortcutExpanded && hasLatchedModifier,
                    description = if (isShortcutExpanded) stringResource(R.string.shortcut_tray_collapse) else stringResource(R.string.shortcut_tray_expand),
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

                ChromeIconButton(
                    active = false,
                    showSignalDot = false,
                    description = stringResource(R.string.trackpad_settings),
                    onClick = {
                        triggerHaptic()
                        onOpenSettings()
                    }
                ) { tint ->
                    SlidersVectorIcon(tint = tint, modifier = Modifier.size(17.dp))
                }
            }
        }

        // While connected, runtime errors (e.g. IPC failures) surface here; otherwise they show in the trackpad
        if (status == ShizukuConnectionStatus.CONNECTED && !errorMessage.isNullOrBlank()) {
            Text(
                text = errorMessage,
                color = TrackpadTokens.LedError,
                fontSize = TrackpadType.Caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
            )
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TrackpadTokens.Hairline)
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    ledColor: Color,
    emphasized: Boolean,
    onClick: (() -> Unit)?
) {
    val touchModifier = if (onClick != null) {
        Modifier
            .clip(RoundedCornerShape(TrackpadRadius.S))
            .clickable(role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .then(touchModifier)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.heightIn(min = 32.dp),
            shape = RoundedCornerShape(TrackpadRadius.S),
            color = if (emphasized) TrackpadTokens.InkPrimary else TrackpadTokens.KeycapSurface
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ledColor)
                )
                Text(
                    text = label,
                    fontSize = TrackpadType.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = if (emphasized) TrackpadTokens.InkInverse else TrackpadTokens.InkPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ChromeIconButton(
    active: Boolean,
    subtleActive: Boolean = false,
    showSignalDot: Boolean,
    description: String,
    toggleState: Boolean? = null,
    onClick: () -> Unit,
    icon: @Composable (tint: Color) -> Unit
) {
    val bgColor = when {
        active -> TrackpadTokens.InkPrimary
        subtleActive -> TrackpadTokens.SurfacePressed
        else -> TrackpadTokens.KeycapModifier
    }
    val onLabel = stringResource(R.string.state_on)
    val offLabel = stringResource(R.string.state_off)
    val tintColor = when {
        active -> TrackpadTokens.InkInverse
        subtleActive -> TrackpadTokens.InkPrimary
        else -> TrackpadTokens.InkSecondary
    }

    // 44dp touch target around a 32dp visual keycap
    Box(
        modifier = Modifier
            .size(44.dp)
            // Concentric with the 32dp visual: its 8dp radius + the 6dp inset
            .clip(RoundedCornerShape(TrackpadRadius.S + 6.dp))
            .clickable(
                role = if (toggleState != null) Role.Switch else Role.Button,
                onClick = onClick
            )
            .semantics {
                contentDescription = description
                if (toggleState != null) {
                    stateDescription = if (toggleState) onLabel else offLabel
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(TrackpadRadius.S))
                .background(bgColor)
        ) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                icon(tintColor)
            }
            if (showSignalDot) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TrackpadTokens.AccentSignal)
                )
            }
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TrackpadCanvas(
    modifier: Modifier = Modifier,
    status: ShizukuConnectionStatus,
    errorMessage: String?,
    selectedDisplay: DisplayTarget?,
    hasExternalDisplay: Boolean,
    cursorX: Float,
    cursorY: Float,
    isDragLocked: Boolean,
    isTouchpadMode: Boolean,
    onTouchpadFrame: (List<TouchContact>) -> Unit,
    onDragLockChanged: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onReconnect: () -> Unit,
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
    val latestOnTouchpadFrame by rememberUpdatedState(onTouchpadFrame)

    // Touchpad mode: phone pixels -> touchpad units, so finger travel in mm matches a real pad.
    val metrics = LocalContext.current.resources.displayMetrics
    val unitsPerPxX = 25.4f / metrics.xdpi / TOUCHPAD_PHYSICAL_SIZE_MM * TOUCHPAD_LOGICAL_MAX
    val unitsPerPxY = 25.4f / metrics.ydpi / TOUCHPAD_PHYSICAL_SIZE_MM * TOUCHPAD_LOGICAL_MAX
    var touchPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val touchSlots = remember { HashMap<Int, Int>() }
    LaunchedEffect(isTouchpadMode) {
        touchSlots.clear()
        touchPoints = emptyList()
    }
    // Remembered so recomposition (e.g. finger dots) never resets the filter mid-gesture.
    val touchpadInput = remember(isTouchpadMode, unitsPerPxX, unitsPerPxY) {
        if (isTouchpadMode) {
            // Forward raw fingers; Android's touchpad stack does pointer + gestures.
            // Only touchscreen events count: the touchpad's own pointer can sit over
            // this canvas (phone display) and its clicks must not loop back in.
            Modifier.pointerInteropFilter { ev ->
                if (!ev.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return@pointerInteropFilter false
                val frame = buildTouchpadFrame(ev, touchSlots, unitsPerPxX, unitsPerPxY)
                if (frame.isNotEmpty()) latestOnTouchpadFrame(frame)
                touchPoints = touchSlots.keys.mapNotNull { id ->
                    val index = ev.findPointerIndex(id)
                    if (index >= 0) Offset(ev.getX(index), ev.getY(index)) else null
                }
                true
            }
        } else {
            Modifier
        }
    }

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
            .then(touchpadInput)
            .pointerInput(isTouchpadMode) {
                if (isTouchpadMode) return@pointerInput
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
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
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
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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

            for (point in touchPoints) {
                drawCircle(
                    color = TrackpadTokens.InkPrimary.copy(alpha = 0.10f),
                    radius = 22.dp.toPx(),
                    center = point
                )
                drawCircle(
                    color = TrackpadTokens.InkPrimary.copy(alpha = 0.35f),
                    radius = 22.dp.toPx(),
                    center = point,
                    style = Stroke(width = 1.dp.toPx())
                )
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
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(TrackpadTokens.AccentSignal)
                    )
                    Text(
                        text = stringResource(R.string.drag_active),
                        fontSize = TrackpadType.Caption,
                        fontWeight = FontWeight.Bold,
                        color = TrackpadTokens.AccentSignal
                    )
                }
            }

            if (!isTouchpadMode) Text(
                text = "${cursorX.roundToInt()}, ${cursorY.roundToInt()}",
                fontSize = TrackpadType.Micro,
                fontFamily = FontFamily.Monospace,
                color = TrackpadTokens.InkSecondary,
                // Decorative readout: hidden from screen readers
                modifier = Modifier
                    .alpha(coordAlpha)
                    .clearAndSetSemantics { }
            )
        }

        // Centered status / recovery card. Disconnected: what is wrong + one clear next step.
        // Connected but not on an external monitor: a quiet hint, so input never silently lands on the phone.
        val notice: CanvasNotice? = when (status) {
            ShizukuConnectionStatus.PERMISSION_REQUIRED -> CanvasNotice(
                title = stringResource(R.string.notice_permission_title),
                helper = stringResource(R.string.notice_permission_helper),
                actionLabel = stringResource(R.string.action_request_permission),
                onAction = onRequestPermission
            )

            ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING -> CanvasNotice(
                title = stringResource(R.string.notice_not_running_title),
                helper = stringResource(R.string.notice_not_running_helper),
                actionLabel = stringResource(R.string.action_check_again),
                onAction = onRequestPermission
            )

            ShizukuConnectionStatus.CONNECTING -> CanvasNotice(
                title = stringResource(R.string.notice_connecting_title),
                helper = null,
                actionLabel = null,
                onAction = null
            )

            ShizukuConnectionStatus.ERROR -> CanvasNotice(
                title = stringResource(R.string.notice_error_title),
                helper = null,
                actionLabel = stringResource(R.string.action_reconnect),
                onAction = onReconnect
            )

            ShizukuConnectionStatus.CONNECTED -> null
        }

        if (notice != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = notice.title,
                    fontSize = TrackpadType.Title,
                    fontWeight = FontWeight.SemiBold,
                    color = TrackpadTokens.InkPrimary,
                    textAlign = TextAlign.Center
                )
                if (notice.helper != null) {
                    Text(
                        text = notice.helper,
                        fontSize = TrackpadType.Label,
                        color = TrackpadTokens.InkSecondary,
                        textAlign = TextAlign.Center
                    )
                }
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        fontSize = TrackpadType.Label,
                        color = TrackpadTokens.LedError,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (notice.actionLabel != null && notice.onAction != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(TrackpadRadius.M))
                            .clickable(role = Role.Button, onClick = notice.onAction),
                        shape = RoundedCornerShape(TrackpadRadius.M),
                        color = TrackpadTokens.InkPrimary
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        ) {
                            Text(
                                text = notice.actionLabel,
                                fontSize = TrackpadType.Body,
                                fontWeight = FontWeight.SemiBold,
                                color = TrackpadTokens.InkInverse
                            )
                        }
                    }
                }
            }
        } else if (selectedDisplay?.isExternal != true) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(TrackpadTokens.LedWarn)
                )
                Text(
                    text = if (hasExternalDisplay) {
                        stringResource(R.string.hint_internal_with_external)
                    } else {
                        stringResource(R.string.hint_no_external)
                    },
                    fontSize = TrackpadType.Label,
                    color = TrackpadTokens.InkSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@StringRes
private fun displayKindRes(display: DisplayTarget): Int =
    if (display.isExternal) R.string.display_kind_external else R.string.display_kind_internal

private data class CanvasNotice(
    val title: String,
    val helper: String?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?
)

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
    val leftDescription = stringResource(R.string.clickpad_left_description)
    val rightDescription = stringResource(R.string.clickpad_right_description)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Visible edge separating touch canvas from the clickpad floor (L holds the mouse button on touch-down)
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(TrackpadTokens.ClickpadEdge)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 44.dp else 48.dp)
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
                    .semantics {
                        contentDescription = leftDescription
                        role = Role.Button
                        onClick {
                            latestLeftDown()
                            latestLeftUp()
                            true
                        }
                    }
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
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_RELEASE)
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
                            .height(2.dp)
                            .background(TrackpadTokens.InkPrimary)
                    )
                }
                Text(
                    text = "L",
                    fontSize = TrackpadType.Label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (leftPressed) TrackpadTokens.InkPrimary else TrackpadTokens.InkMuted,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clearAndSetSemantics { }
                )
            }

            // Precision vertical hardware clickpad tick
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
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
                    .semantics {
                        contentDescription = rightDescription
                        role = Role.Button
                        onClick {
                            latestRightClick()
                            true
                        }
                    }
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
                            .height(2.dp)
                            .background(TrackpadTokens.AccentSignal)
                    )
                }
                Text(
                    text = "R",
                    fontSize = TrackpadType.Label,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (rightPressed) TrackpadTokens.InkPrimary else TrackpadTokens.InkMuted,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clearAndSetSemantics { }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackpadSettingsBottomSheet(
    settings: TrackpadSettings,
    isTouchpadMode: Boolean,
    touchpadSettings: TouchpadSystemSettings,
    onTouchpadPointerSpeedChange: (Int) -> Unit,
    onTouchpadNaturalScrollingChange: (Boolean) -> Unit,
    onTouchpadTapToClickChange: (Boolean) -> Unit,
    onTouchpadTapDraggingChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSensitivityChange: (Float) -> Unit,
    onSensitivityChangeFinished: () -> Unit,
    onNaturalScrollChange: (Boolean) -> Unit,
    onShowPhysicalButtonsChange: (Boolean) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onResetCursor: () -> Unit,
    updateState: UpdateState,
    currentVersion: String,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sensitivityLabel = stringResource(R.string.settings_sensitivity)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrackpadTokens.DeckSurface,
        contentColor = TrackpadTokens.InkPrimary,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = TrackpadTokens.InkSecondary)
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
                text = stringResource(R.string.settings_title),
                fontSize = TrackpadType.Title,
                fontWeight = FontWeight.SemiBold,
                color = TrackpadTokens.InkPrimary
            )

            if (isTouchpadMode) {
                TouchpadSystemSettingsSection(
                    settings = touchpadSettings,
                    onPointerSpeedChange = onTouchpadPointerSpeedChange,
                    onNaturalScrollingChange = onTouchpadNaturalScrollingChange,
                    onTapToClickChange = onTouchpadTapToClickChange,
                    onTapDraggingChange = onTouchpadTapDraggingChange
                )
            } else {
            // Sensitivity slider (DPI 0.5x ~ 3.0x)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sensitivityLabel,
                        fontSize = TrackpadType.Body,
                        fontWeight = FontWeight.Medium,
                        color = TrackpadTokens.InkPrimary
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", settings.sensitivity),
                        fontSize = TrackpadType.Body,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        color = TrackpadTokens.AccentSignal
                    )
                }
                Slider(
                    value = settings.sensitivity,
                    onValueChange = onSensitivityChange,
                    onValueChangeFinished = onSensitivityChangeFinished,
                    valueRange = TrackpadSettingsStore.MIN_SENSITIVITY..TrackpadSettingsStore.MAX_SENSITIVITY,
                    // Clean 0.1x increments
                    steps = 24,
                    modifier = Modifier.semantics { contentDescription = sensitivityLabel },
                    colors = SliderDefaults.colors(
                        thumbColor = TrackpadTokens.InkPrimary,
                        activeTrackColor = TrackpadTokens.AccentSignal,
                        inactiveTrackColor = TrackpadTokens.TrackInactive,
                        activeTickColor = Color.Transparent,
                        inactiveTickColor = Color.Transparent
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1fx", TrackpadSettingsStore.MIN_SENSITIVITY),
                        fontSize = TrackpadType.Caption,
                        fontFamily = FontFamily.Monospace,
                        color = TrackpadTokens.InkMuted
                    )
                    Text(
                        text = String.format(Locale.US, "%.1fx", TrackpadSettingsStore.MAX_SENSITIVITY),
                        fontSize = TrackpadType.Caption,
                        fontFamily = FontFamily.Monospace,
                        color = TrackpadTokens.InkMuted
                    )
                }
            }

            HorizontalDivider(color = TrackpadTokens.Hairline)

            SettingSwitchRow(
                title = stringResource(R.string.settings_natural_scroll),
                subtitle = if (settings.naturalScroll) stringResource(R.string.settings_natural_scroll_on) else stringResource(R.string.settings_natural_scroll_off),
                checked = settings.naturalScroll,
                onCheckedChange = onNaturalScrollChange
            )
            }

            SettingSwitchRow(
                title = stringResource(R.string.settings_clickpad),
                subtitle = stringResource(R.string.settings_clickpad_subtitle),
                checked = settings.showPhysicalButtons,
                onCheckedChange = onShowPhysicalButtonsChange
            )

            SettingSwitchRow(
                title = stringResource(R.string.settings_keep_screen_on),
                subtitle = stringResource(R.string.settings_keep_screen_on_subtitle),
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange
            )

            HorizontalDivider(color = TrackpadTokens.Hairline)

            // Concise gesture reference (moved off the silent trackpad floor)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.settings_gestures),
                    fontSize = TrackpadType.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = TrackpadTokens.InkMuted
                )
                if (isTouchpadMode) {
                    GestureGuideRow(stringResource(R.string.gesture_one_finger), stringResource(R.string.gesture_one_finger_action))
                    GestureGuideRow(stringResource(R.string.gesture_two_finger), stringResource(R.string.gesture_two_finger_action))
                    GestureGuideRow(stringResource(R.string.gesture_pinch), stringResource(R.string.gesture_pinch_action))
                    GestureGuideRow(stringResource(R.string.gesture_three_finger), stringResource(R.string.gesture_three_finger_action))
                    GestureGuideRow(stringResource(R.string.gesture_four_finger), stringResource(R.string.gesture_four_finger_action))
                } else {
                    GestureGuideRow(stringResource(R.string.gesture_one_finger), stringResource(R.string.gesture_one_finger_action))
                    GestureGuideRow(stringResource(R.string.gesture_two_finger), stringResource(R.string.gesture_two_finger_action))
                    GestureGuideRow(stringResource(R.string.gesture_double_tap_drag), stringResource(R.string.gesture_double_tap_drag_action))
                }
                GestureGuideRow(stringResource(R.string.gesture_left_hold), stringResource(R.string.gesture_left_hold_action))
                GestureGuideRow(stringResource(R.string.gesture_sticky_modifiers), stringResource(R.string.gesture_sticky_modifiers_action))
            }

            // The system pointer of the virtual touchpad is not ours to recenter.
            if (!isTouchpadMode) Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(TrackpadRadius.M))
                    .clickable(role = Role.Button) {
                        onResetCursor()
                        onDismiss()
                    },
                shape = RoundedCornerShape(TrackpadRadius.M),
                color = TrackpadTokens.KeycapSurface,
                border = BorderStroke(1.dp, TrackpadTokens.KeycapBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.settings_reset_cursor),
                        fontSize = TrackpadType.Body,
                        fontWeight = FontWeight.Medium,
                        color = TrackpadTokens.InkPrimary
                    )
                }
            }

            HorizontalDivider(color = TrackpadTokens.Hairline)

            UpdateSettingsSection(
                state = updateState,
                currentVersion = currentVersion,
                onCheck = onCheckForUpdate,
                onUpdate = onStartUpdate
            )
        }
    }
}

/** Android's own touchpad settings, applied system-wide like for a physical touchpad. */
@Composable
private fun TouchpadSystemSettingsSection(
    settings: TouchpadSystemSettings,
    onPointerSpeedChange: (Int) -> Unit,
    onNaturalScrollingChange: (Boolean) -> Unit,
    onTapToClickChange: (Boolean) -> Unit,
    onTapDraggingChange: (Boolean) -> Unit
) {
    val speedLabel = stringResource(R.string.settings_touchpad_speed)
    var speed by remember(settings.pointerSpeed) { mutableStateOf(settings.pointerSpeed.toFloat()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = speedLabel,
                fontSize = TrackpadType.Body,
                fontWeight = FontWeight.Medium,
                color = TrackpadTokens.InkPrimary
            )
            Text(
                text = String.format(Locale.US, "%+d", speed.roundToInt()),
                fontSize = TrackpadType.Body,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = TrackpadTokens.AccentSignal
            )
        }
        Slider(
            value = speed,
            onValueChange = { speed = it },
            onValueChangeFinished = { onPointerSpeedChange(speed.roundToInt()) },
            valueRange = ShizukuServiceManager.MIN_POINTER_SPEED.toFloat()..ShizukuServiceManager.MAX_POINTER_SPEED.toFloat(),
            steps = ShizukuServiceManager.MAX_POINTER_SPEED - ShizukuServiceManager.MIN_POINTER_SPEED - 1,
            modifier = Modifier.semantics { contentDescription = speedLabel },
            colors = SliderDefaults.colors(
                thumbColor = TrackpadTokens.InkPrimary,
                activeTrackColor = TrackpadTokens.AccentSignal,
                inactiveTrackColor = TrackpadTokens.TrackInactive,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )
        Text(
            text = stringResource(R.string.settings_touchpad_system_note),
            fontSize = TrackpadType.Caption,
            color = TrackpadTokens.InkMuted
        )
    }

    HorizontalDivider(color = TrackpadTokens.Hairline)

    SettingSwitchRow(
        title = stringResource(R.string.settings_natural_scroll),
        subtitle = if (settings.naturalScrolling) stringResource(R.string.settings_natural_scroll_on) else stringResource(R.string.settings_natural_scroll_off),
        checked = settings.naturalScrolling,
        onCheckedChange = onNaturalScrollingChange
    )
    SettingSwitchRow(
        title = stringResource(R.string.settings_tap_to_click),
        subtitle = stringResource(R.string.settings_tap_to_click_subtitle),
        checked = settings.tapToClick,
        onCheckedChange = onTapToClickChange
    )
    SettingSwitchRow(
        title = stringResource(R.string.settings_tap_dragging),
        subtitle = stringResource(R.string.settings_tap_dragging_subtitle),
        checked = settings.tapDragging,
        onCheckedChange = onTapDraggingChange
    )
}

@Composable
private fun GestureGuideRow(gesture: String, action: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = gesture,
            fontSize = TrackpadType.Label,
            color = TrackpadTokens.InkSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Text(
            text = action,
            fontSize = TrackpadType.Label,
            color = TrackpadTokens.InkPrimary,
            textAlign = TextAlign.End
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(TrackpadRadius.S))
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
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
                fontSize = TrackpadType.Body,
                fontWeight = FontWeight.Medium,
                color = TrackpadTokens.InkPrimary
            )
            Text(
                text = subtitle,
                fontSize = TrackpadType.Caption,
                color = TrackpadTokens.InkSecondary
            )
        }
        Switch(
            checked = checked,
            // Row handles the toggle so the whole row is one labelled target
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TrackpadTokens.InkInverse,
                checkedTrackColor = TrackpadTokens.InkPrimary,
                uncheckedThumbColor = TrackpadTokens.InkSecondary,
                uncheckedTrackColor = TrackpadTokens.SurfacePressed,
                uncheckedBorderColor = TrackpadTokens.InkSecondary
            )
        )
    }
}

/**
 * Turns one touchscreen MotionEvent into a touchpad frame: every finger still down plus,
 * once, each finger that lifted. [slots] maps MotionEvent pointer ids to contact ids 0..7.
 */
private fun buildTouchpadFrame(
    ev: MotionEvent,
    slots: HashMap<Int, Int>,
    unitsPerPxX: Float,
    unitsPerPxY: Float
): List<TouchContact> {
    val action = ev.actionMasked
    val frame = ArrayList<TouchContact>(TOUCHPAD_MAX_CONTACTS)
    for (i in 0 until ev.pointerCount) {
        val pointerId = ev.getPointerId(i)
        val lifted = action == MotionEvent.ACTION_UP ||
            action == MotionEvent.ACTION_CANCEL ||
            (action == MotionEvent.ACTION_POINTER_UP && i == ev.actionIndex)
        var slot = slots[pointerId]
        if (slot == null && !lifted) {
            slot = (0 until TOUCHPAD_CONTACT_IDS).firstOrNull { it !in slots.values }
            if (slot != null) slots[pointerId] = slot
        }
        if (lifted) slots.remove(pointerId)
        if (slot == null || frame.size >= TOUCHPAD_MAX_CONTACTS) continue
        frame += TouchContact(
            id = slot,
            x = (ev.getX(i) * unitsPerPxX).roundToInt().coerceIn(0, TOUCHPAD_LOGICAL_MAX),
            y = (ev.getY(i) * unitsPerPxY).roundToInt().coerceIn(0, TOUCHPAD_LOGICAL_MAX),
            tip = !lifted
        )
    }
    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) slots.clear()
    return frame
}

/** Fingers per touchpad frame and distinct contact ids (matches VirtualTouchpad). */
private const val TOUCHPAD_MAX_CONTACTS = 5
private const val TOUCHPAD_CONTACT_IDS = 8

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
