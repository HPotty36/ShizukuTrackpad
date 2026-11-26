package com.shizukutrackpad.app.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shizukutrackpad.app.data.DisplayRepository
import com.shizukutrackpad.app.data.DisplayTarget
import com.shizukutrackpad.app.shizuku.ShizukuServiceManager
import com.shizukutrackpad.app.shizuku.ShizukuState
import com.shizukutrackpad.app.update.ReleaseInfo
import com.shizukutrackpad.app.update.UpdateManager
import com.shizukutrackpad.app.update.UpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class StickyModifier {
    CTRL,
    ALT,
    SHIFT,
    WIN
}

data class StickyModifiersState(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val win: Boolean = false
) {
    val anyActive: Boolean
        get() = ctrl || alt || shift || win

    fun toMetaState(): Int {
        var meta = 0
        if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (win) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        return meta
    }

    fun activeSummary(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (win) add("Win")
    }.joinToString("+")
}

data class TrackpadSettings(
    val sensitivity: Float = 1.5f,
    val naturalScroll: Boolean = true,
    val showPhysicalButtons: Boolean = true,
    val keepScreenOn: Boolean = true
)

private data class ToolbarControlState(
    val stickyModifiers: StickyModifiersState = StickyModifiersState(),
    val isShortcutToolbarExpanded: Boolean = true,
    val isKeyboardBarVisible: Boolean = false,
    val isRealtimeAsciiMode: Boolean = true
)

data class TrackpadUiState(
    val shizuku: ShizukuState = ShizukuState(),
    val displays: List<DisplayTarget> = emptyList(),
    val selectedDisplay: DisplayTarget? = null,
    val settings: TrackpadSettings = TrackpadSettings(),
    val stickyModifiers: StickyModifiersState = StickyModifiersState(),
    val isShortcutToolbarExpanded: Boolean = true,
    val isKeyboardBarVisible: Boolean = false,
    val isRealtimeAsciiMode: Boolean = true
)

class TrackpadViewModel(application: Application) : AndroidViewModel(application) {

    private val displayRepository = DisplayRepository(application)
    private val shizukuManager = ShizukuServiceManager(application)
    private val clipboardManager =
        application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val virtualKeyMap: KeyCharacterMap =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    private val settingsStore = TrackpadSettingsStore(application)
    private val updateManager = UpdateManager.get(application)

    val updateState: StateFlow<UpdateState> = updateManager.state
    val dismissedUpdateVersion: StateFlow<String?> = updateManager.dismissedVersion
    val currentAppVersion: String = updateManager.currentVersion

    private val selectedDisplayId = MutableStateFlow<Int?>(null)
    private val settings = MutableStateFlow(settingsStore.loadSettings())
    private val toolbarControl = MutableStateFlow(
        ToolbarControlState(isShortcutToolbarExpanded = settingsStore.loadShortcutTrayExpanded())
    )
    private var userManuallySelectedDefault = false

    val uiState: StateFlow<TrackpadUiState> = combine(
        shizukuManager.state,
        displayRepository.displays,
        selectedDisplayId,
        settings,
        toolbarControl
    ) { shizukuState, displays, currentSelectedId, currentSettings, toolbar ->
        val resolvedDisplay = displays.firstOrNull { it.displayId == currentSelectedId }
            ?: displays.firstOrNull { it.isExternal }
            ?: displays.firstOrNull()
        TrackpadUiState(
            shizuku = shizukuState,
            displays = displays,
            selectedDisplay = resolvedDisplay,
            settings = currentSettings,
            stickyModifiers = toolbar.stickyModifiers,
            isShortcutToolbarExpanded = toolbar.isShortcutToolbarExpanded,
            isKeyboardBarVisible = toolbar.isKeyboardBarVisible,
            isRealtimeAsciiMode = toolbar.isRealtimeAsciiMode
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        // Seed the first frame with persisted values so a collapsed tray / hidden clickpad
        // doesn't animate closed on every cold start.
        initialValue = TrackpadUiState(
            settings = settings.value,
            isShortcutToolbarExpanded = toolbarControl.value.isShortcutToolbarExpanded
        )
    )

    init {
        shizukuManager.start()
        updateManager.checkIfDue()

        viewModelScope.launch {
            displayRepository.displays.collect { displays ->
                syncSelectedDisplay(displays)
            }
        }
    }

    private fun syncSelectedDisplay(displays: List<DisplayTarget>) {
        if (displays.isEmpty()) return

        val currentId = selectedDisplayId.value
        val firstExternal = displays.firstOrNull { it.isExternal }
        val matchingCurrent = displays.firstOrNull { it.displayId == currentId }

        val chosen = when {
            firstExternal != null &&
                (matchingCurrent == null || (!matchingCurrent.isExternal && !userManuallySelectedDefault)) -> {
                firstExternal
            }
            matchingCurrent != null -> matchingCurrent
            else -> displays.first()
        }

        val idChanged = selectedDisplayId.value != chosen.displayId
        if (idChanged) {
            selectedDisplayId.value = chosen.displayId
        }
        shizukuManager.setTargetDisplay(chosen)
    }

    fun selectDisplay(display: DisplayTarget) {
        userManuallySelectedDefault = !display.isExternal
        selectedDisplayId.value = display.displayId
        shizukuManager.setTargetDisplay(display)
    }

    fun refreshDisplays() {
        displayRepository.refreshDisplays()
        syncSelectedDisplay(displayRepository.displays.value)
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun reconnectService() {
        shizukuManager.bindService()
    }

    /** Live slider updates: in memory only; [commitSensitivity] persists when the drag ends. */
    fun setSensitivity(value: Float) {
        val rounded = (value * 10f).roundToInt() / 10f
        settings.update {
            it.copy(
                sensitivity = rounded.coerceIn(
                    TrackpadSettingsStore.MIN_SENSITIVITY,
                    TrackpadSettingsStore.MAX_SENSITIVITY
                )
            )
        }
    }

    fun commitSensitivity() {
        settingsStore.saveSettings(settings.value)
    }

    fun setNaturalScroll(enabled: Boolean) {
        updateSettings { it.copy(naturalScroll = enabled) }
    }

    fun setShowPhysicalButtons(enabled: Boolean) {
        updateSettings { it.copy(showPhysicalButtons = enabled) }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        updateSettings { it.copy(keepScreenOn = enabled) }
    }

    private inline fun updateSettings(transform: (TrackpadSettings) -> TrackpadSettings) {
        val updated = settings.updateAndGet(transform)
        settingsStore.saveSettings(updated)
    }

    fun onTrackpadMove(dx: Float, dy: Float) {
        val factor = settings.value.sensitivity
        shizukuManager.moveCursor(dx * factor, dy * factor)
    }

    fun onTrackpadDragEnd() {
        shizukuManager.syncCursorFromService()
    }

    fun onTrackpadTap() {
        shizukuManager.leftClick()
    }

    fun onTwoFingerTap() {
        shizukuManager.rightClick()
    }

    fun onTwoFingerScroll(dx: Float, dy: Float) {
        val natural = settings.value.naturalScroll
        // Natural scroll ON: dragging fingers up pushes content up (vScroll < 0, hScroll opposite of dx)
        // Natural scroll OFF: traditional PC wheel direction (inverted)
        val vScroll = if (natural) dy / SCROLL_DIVISOR else -dy / SCROLL_DIVISOR
        val hScroll = if (natural) -dx / SCROLL_DIVISOR else dx / SCROLL_DIVISOR
        if (hScroll != 0f || vScroll != 0f) {
            shizukuManager.scroll(hScroll, vScroll)
        }
    }

    fun onBeginLeftDrag() {
        shizukuManager.beginLeftDrag()
    }

    fun onLeftDragMove(dx: Float, dy: Float) {
        val factor = settings.value.sensitivity
        shizukuManager.dragMove(dx * factor, dy * factor)
    }

    fun onEndLeftDrag() {
        shizukuManager.endLeftDrag()
        shizukuManager.syncCursorFromService()
    }

    fun resetCursorToCenter() {
        shizukuManager.resetCursorToCenter()
    }

    fun toggleShortcutToolbar() {
        val updated = toolbarControl.updateAndGet {
            it.copy(isShortcutToolbarExpanded = !it.isShortcutToolbarExpanded)
        }
        settingsStore.saveShortcutTrayExpanded(updated.isShortcutToolbarExpanded)
    }

    fun toggleKeyboardBar() {
        toolbarControl.update { it.copy(isKeyboardBarVisible = !it.isKeyboardBarVisible) }
    }

    /** The system keyboard was closed outside the app's toggle (e.g. Back); keep the toggle in sync. */
    fun onKeyboardDismissed() {
        toolbarControl.update { it.copy(isKeyboardBarVisible = false) }
    }

    fun toggleRealtimeAsciiMode() {
        toolbarControl.update { it.copy(isRealtimeAsciiMode = !it.isRealtimeAsciiMode) }
    }

    fun setRealtimeAsciiMode(enabled: Boolean) {
        toolbarControl.update { it.copy(isRealtimeAsciiMode = enabled) }
    }

    fun toggleStickyModifier(modifier: StickyModifier) {
        toolbarControl.update { state ->
            val current = state.stickyModifiers
            val updated = when (modifier) {
                StickyModifier.CTRL -> current.copy(ctrl = !current.ctrl)
                StickyModifier.ALT -> current.copy(alt = !current.alt)
                StickyModifier.SHIFT -> current.copy(shift = !current.shift)
                StickyModifier.WIN -> current.copy(win = !current.win)
            }
            state.copy(stickyModifiers = updated)
        }
    }

    fun sendKeyShortcut(keyCode: Int, presetMetaState: Int = 0) {
        val sticky = toolbarControl.value.stickyModifiers
        val combinedMeta = presetMetaState or sticky.toMetaState()
        val targetDisplayId = uiState.value.selectedDisplay?.displayId ?: selectedDisplayId.value ?: 0
        shizukuManager.injectKeyEvent(keyCode, combinedMeta, targetDisplayId)
        if (sticky.anyActive) {
            toolbarControl.update { it.copy(stickyModifiers = StickyModifiersState()) }
        }
    }

    fun sendPasteText(text: String) {
        if (text.isEmpty()) return
        val targetDisplayId = uiState.value.selectedDisplay?.displayId ?: selectedDisplayId.value ?: 0
        runCatching {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("ShizukuTrackpad", text))
        }
        shizukuManager.injectPasteText(text, targetDisplayId)
    }

    fun sendRealtimeAsciiChar(ch: Char) {
        val events = virtualKeyMap.getEvents(charArrayOf(ch))
        val downEvent = events?.firstOrNull { it.action == KeyEvent.ACTION_DOWN } ?: return
        sendKeyShortcut(downEvent.keyCode, downEvent.metaState)
    }

    fun sendRealtimeBackspace() {
        sendKeyShortcut(KeyEvent.KEYCODE_DEL, 0)
    }

    fun sendRealtimeEnter() {
        sendKeyShortcut(KeyEvent.KEYCODE_ENTER, 0)
    }

    fun checkForUpdate() {
        updateManager.checkNow()
    }

    fun startUpdate() {
        updateManager.downloadAndInstall()
    }

    fun dismissUpdateBanner(release: ReleaseInfo) {
        updateManager.dismissBanner(release)
    }

    override fun onCleared() {
        super.onCleared()
        displayRepository.release()
        shizukuManager.release()
    }

    companion object {
        private const val SCROLL_DIVISOR = 42f
    }
}
