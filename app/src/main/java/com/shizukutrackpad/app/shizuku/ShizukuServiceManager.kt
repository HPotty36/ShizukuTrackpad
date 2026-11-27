package com.shizukutrackpad.app.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.shizukutrackpad.app.BuildConfig
import com.shizukutrackpad.app.IInputInjectorService
import com.shizukutrackpad.app.R
import com.shizukutrackpad.app.data.DisplayTarget
import com.shizukutrackpad.app.service.InputInjectorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ShizukuConnectionStatus {
    SHIZUKU_NOT_RUNNING,
    PERMISSION_REQUIRED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ShizukuState(
    val status: ShizukuConnectionStatus = ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING,
    val isBinderAlive: Boolean = false,
    val hasPermission: Boolean = false,
    val isServiceBound: Boolean = false,
    val cursorX: Float = 0f,
    val cursorY: Float = 0f,
    val errorMessage: String? = null,
    /** True while the virtual uhid touchpad is up; raw touches are forwarded to it. */
    val isTouchpadActive: Boolean = false
)

/** One finger in touchpad device units (0..[TOUCHPAD_LOGICAL_MAX] on both axes). */
data class TouchContact(val id: Int, val x: Int, val y: Int, val tip: Boolean)

/** Android's system-wide touchpad settings (Settings > System > Touchpad). */
data class TouchpadSystemSettings(
    val pointerSpeed: Int = 0,
    val naturalScrolling: Boolean = true,
    val tapToClick: Boolean = true,
    val tapDragging: Boolean = true
)

const val TOUCHPAD_LOGICAL_MAX = 4095
const val TOUCHPAD_PHYSICAL_SIZE_MM = 160f
const val TOUCHPAD_BUTTON_LEFT = 0x01
const val TOUCHPAD_BUTTON_RIGHT = 0x02

class ShizukuServiceManager(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile
    private var injectorService: IInputInjectorService? = null

    @Volatile
    private var currentDisplay: DisplayTarget? = null

    private var isBindingRequested: Boolean = false

    private val _touchpadSettings = MutableStateFlow<TouchpadSystemSettings?>(null)
    val touchpadSettings: StateFlow<TouchpadSystemSettings?> = _touchpadSettings.asStateFlow()

    private val prefs = appContext.getSharedPreferences("touchpad", Context.MODE_PRIVATE)

    // Touchpad frame state, only touched on ipcExecutor.
    private var touchButtons = 0
    private var activeContacts: List<TouchContact> = emptyList()
    @Volatile
    private var touchpadStartRequested = false

    // Single-threaded executor guarantees FIFO ordering for pointer/click IPC calls off the main thread
    private val ipcExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ShizukuInputIpc").apply { isDaemon = true }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                InputInjectorService::class.java.name
            )
        )
            .daemon(false)
            .processNameSuffix("input_injector")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isBindingRequested = false
            if (service == null || !service.pingBinder()) {
                _state.update {
                    it.copy(
                        status = ShizukuConnectionStatus.ERROR,
                        isServiceBound = false,
                        errorMessage = appContext.getString(R.string.error_service_invalid)
                    )
                }
                return
            }

            val proxy = IInputInjectorService.Stub.asInterface(service)
            injectorService = proxy

            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.CONNECTED,
                    isServiceBound = true,
                    errorMessage = null
                )
            }

            touchpadStartRequested = false
            currentDisplay?.let { display ->
                setTargetDisplay(display)
            }

            ipcExecutor.execute {
                val initError = runCatching { proxy.lastError }.getOrNull().orEmpty()
                if (initError.isNotBlank()) {
                    _state.update { it.copy(errorMessage = initError) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            injectorService = null
            isBindingRequested = false
            touchpadStartRequested = false
            _state.update { it.copy(isTouchpadActive = false) }
            refreshStatus(disconnected = true)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshStatus()
        if (checkPermissionGranted()) {
            bindService()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        injectorService = null
        isBindingRequested = false
        touchpadStartRequested = false
        _state.update {
            it.copy(
                isTouchpadActive = false,
                status = ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING,
                isBinderAlive = false,
                isServiceBound = false,
                errorMessage = appContext.getString(R.string.error_shizuku_stopped)
            )
        }
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                _state.update {
                    it.copy(
                        hasPermission = granted,
                        status = if (granted) {
                            ShizukuConnectionStatus.CONNECTING
                        } else {
                            ShizukuConnectionStatus.PERMISSION_REQUIRED
                        },
                        errorMessage = if (granted) null else appContext.getString(R.string.error_permission_denied)
                    )
                }
                if (granted) {
                    bindService()
                }
            }
        }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refreshStatus()
        if (Shizuku.pingBinder() && checkPermissionGranted()) {
            bindService()
        }
    }

    fun release() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        unbindService()
        ipcExecutor.shutdownNow()
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING,
                    isBinderAlive = false,
                    errorMessage = appContext.getString(R.string.error_shizuku_not_running)
                )
            }
            return
        }

        if (Shizuku.isPreV11()) {
            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.ERROR,
                    errorMessage = appContext.getString(R.string.error_shizuku_outdated)
                )
            }
            return
        }

        if (checkPermissionGranted()) {
            bindService()
        } else {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    fun bindService() {
        if (!Shizuku.pingBinder()) {
            refreshStatus()
            return
        }
        if (!checkPermissionGranted()) {
            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.PERMISSION_REQUIRED,
                    isBinderAlive = true,
                    hasPermission = false
                )
            }
            return
        }
        if (injectorService != null || isBindingRequested) {
            return
        }

        try {
            isBindingRequested = true
            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.CONNECTING,
                    isBinderAlive = true,
                    hasPermission = true,
                    errorMessage = null
                )
            }
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } catch (t: Throwable) {
            isBindingRequested = false
            Log.e(TAG, "Failed to bind UserService", t)
            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.ERROR,
                    isServiceBound = false,
                    errorMessage = appContext.getString(R.string.error_service_bind_failed)
                )
            }
        }
    }

    fun unbindService() {
        if (Shizuku.pingBinder()) {
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }
        }
        injectorService = null
        isBindingRequested = false
    }

    fun setTargetDisplay(display: DisplayTarget) {
        currentDisplay = display
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.setTargetDisplay(display.displayId, display.width, display.height)
                val x = service.cursorX
                val y = service.cursorY
                val err = service.lastError
                _state.update {
                    it.copy(
                        cursorX = x,
                        cursorY = y,
                        errorMessage = err.takeIf { msg -> msg.isNotBlank() }
                    )
                }
            }.onFailure { handleIpcFailure(it) }
        }
        // The touchpad's pointer only makes sense on another screen: on the phone display it
        // would share the window with the fingers driving it, and Android lets one device win.
        if (display.isExternal) startTouchpadIfNeeded(service, display) else stopTouchpad(service)
    }

    private fun stopTouchpad(service: IInputInjectorService) {
        if (!touchpadStartRequested && !_state.value.isTouchpadActive) return
        touchpadStartRequested = false
        ipcExecutor.execute {
            runCatching { service.stopTouchpad() }.onFailure { handleIpcFailure(it) }
            activeContacts = emptyList()
            touchButtons = 0
            _state.update { it.copy(isTouchpadActive = false) }
        }
    }

    /**
     * Brings up the virtual touchpad once per connection (external displays only). If the device cannot be created
     * (no uhid access on this ROM), the app keeps using injected mouse events.
     */
    private fun startTouchpadIfNeeded(service: IInputInjectorService, display: DisplayTarget) {
        if (touchpadStartRequested) return
        touchpadStartRequested = true
        ipcExecutor.execute {
            val started = runCatching { service.startTouchpad(display.displayId) }
                .onFailure { Log.e(TAG, "startTouchpad failed", it) }
                .getOrDefault(false)
            if (!started) {
                val err = runCatching { service.lastError }.getOrNull().orEmpty()
                Log.w(TAG, "Virtual touchpad unavailable: $err")
            }
            _state.update { it.copy(isTouchpadActive = started) }
            if (started) {
                applyTouchpadDefaultsOnce(service)
                loadTouchpadSettingsLocked(service)
            }
        }
    }

    /** Forwards one touch frame (current fingers + just-lifted ones) to the virtual touchpad. */
    fun sendTouchFrame(contacts: List<TouchContact>) {
        val service = injectorService ?: return
        ipcExecutor.execute {
            activeContacts = contacts.filter { it.tip }
            sendFrameLocked(service, contacts)
        }
    }

    /** Presses or releases a touchpad button (the L / R clickpad floor). */
    fun setTouchpadButton(mask: Int, pressed: Boolean) {
        val service = injectorService ?: return
        ipcExecutor.execute {
            touchButtons = if (pressed) touchButtons or mask else touchButtons and mask.inv()
            sendFrameLocked(service, activeContacts)
        }
    }

    private fun sendFrameLocked(service: IInputInjectorService, contacts: List<TouchContact>) {
        runCatching {
            service.sendTouchpadFrame(
                IntArray(contacts.size) { contacts[it].id },
                IntArray(contacts.size) { contacts[it].x },
                IntArray(contacts.size) { contacts[it].y },
                BooleanArray(contacts.size) { contacts[it].tip },
                touchButtons
            )
        }.onFailure { handleIpcFailure(it) }
    }

    fun refreshTouchpadSettings() {
        val service = injectorService ?: return
        ipcExecutor.execute { loadTouchpadSettingsLocked(service) }
    }

    fun updateTouchpadSettings(transform: (TouchpadSystemSettings) -> TouchpadSystemSettings) {
        val service = injectorService ?: return
        val current = _touchpadSettings.value ?: TouchpadSystemSettings()
        val updated = transform(current)
        _touchpadSettings.value = updated
        ipcExecutor.execute {
            runCatching {
                if (updated.pointerSpeed != current.pointerSpeed) {
                    service.putTouchpadSetting(KEY_POINTER_SPEED, updated.pointerSpeed.toString())
                }
                if (updated.naturalScrolling != current.naturalScrolling) {
                    service.putTouchpadSetting(KEY_NATURAL_SCROLLING, updated.naturalScrolling.toFlag())
                }
                if (updated.tapToClick != current.tapToClick) {
                    service.putTouchpadSetting(KEY_TAP_TO_CLICK, updated.tapToClick.toFlag())
                }
                if (updated.tapDragging != current.tapDragging) {
                    service.putTouchpadSetting(KEY_TAP_DRAGGING, updated.tapDragging.toFlag())
                }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    private fun loadTouchpadSettingsLocked(service: IInputInjectorService) {
        runCatching {
            val defaults = TouchpadSystemSettings()
            TouchpadSystemSettings(
                pointerSpeed = service.getTouchpadSetting(KEY_POINTER_SPEED)?.toIntOrNull()
                    ?.coerceIn(MIN_POINTER_SPEED, MAX_POINTER_SPEED) ?: defaults.pointerSpeed,
                naturalScrolling = service.getTouchpadSetting(KEY_NATURAL_SCROLLING)?.let { it == "1" }
                    ?: defaults.naturalScrolling,
                tapToClick = service.getTouchpadSetting(KEY_TAP_TO_CLICK)?.let { it == "1" }
                    ?: defaults.tapToClick,
                tapDragging = service.getTouchpadSetting(KEY_TAP_DRAGGING)?.let { it == "1" }
                    ?: defaults.tapDragging
            )
        }.onSuccess { _touchpadSettings.value = it }
            .onFailure { Log.w(TAG, "Could not read touchpad settings", it) }
    }

    /**
     * Tap to click and tap dragging were how this app always behaved; turn them on for the
     * system touchpad once, unless the user has already chosen a value in Android settings.
     */
    private fun applyTouchpadDefaultsOnce(service: IInputInjectorService) {
        if (prefs.getBoolean(PREF_DEFAULTS_APPLIED, false)) return
        runCatching {
            for (key in listOf(KEY_TAP_TO_CLICK, KEY_TAP_DRAGGING)) {
                if (service.getTouchpadSetting(key) == null) {
                    service.putTouchpadSetting(key, "1")
                }
            }
            prefs.edit().putBoolean(PREF_DEFAULTS_APPLIED, true).apply()
        }.onFailure { Log.w(TAG, "Could not apply touchpad defaults", it) }
    }

    private fun Boolean.toFlag(): String = if (this) "1" else "0"

    fun moveCursor(dx: Float, dy: Float) {
        val service = injectorService ?: return
        val display = currentDisplay
        if (display != null) {
            val maxX = (display.width - 1).coerceAtLeast(0).toFloat()
            val maxY = (display.height - 1).coerceAtLeast(0).toFloat()
            _state.update {
                it.copy(
                    cursorX = (it.cursorX + dx).coerceIn(0f, maxX),
                    cursorY = (it.cursorY + dy).coerceIn(0f, maxY)
                )
            }
        }

        ipcExecutor.execute {
            runCatching {
                service.moveCursor(dx, dy)
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun syncCursorFromService() {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                val x = service.cursorX
                val y = service.cursorY
                val err = service.lastError
                _state.update {
                    it.copy(
                        cursorX = x,
                        cursorY = y,
                        errorMessage = err.takeIf { msg -> msg.isNotBlank() }
                    )
                }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun leftClick() {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.leftClick()
                val err = service.lastError
                if (err.isNotBlank()) {
                    _state.update { it.copy(errorMessage = err) }
                }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun rightClick() {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.rightClick()
                val err = service.lastError
                if (err.isNotBlank()) {
                    _state.update { it.copy(errorMessage = err) }
                }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun scroll(hScroll: Float, vScroll: Float) {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.scroll(hScroll, vScroll)
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun beginLeftDrag() {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.beginLeftDrag()
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun dragMove(dx: Float, dy: Float) {
        val service = injectorService ?: return
        val display = currentDisplay
        if (display != null) {
            val maxX = (display.width - 1).coerceAtLeast(0).toFloat()
            val maxY = (display.height - 1).coerceAtLeast(0).toFloat()
            _state.update {
                it.copy(
                    cursorX = (it.cursorX + dx).coerceIn(0f, maxX),
                    cursorY = (it.cursorY + dy).coerceIn(0f, maxY)
                )
            }
        }
        ipcExecutor.execute {
            runCatching {
                service.dragMove(dx, dy)
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun endLeftDrag() {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.endLeftDrag()
                val x = service.cursorX
                val y = service.cursorY
                _state.update { it.copy(cursorX = x, cursorY = y) }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun injectKeyEvent(keyCode: Int, metaState: Int = 0, displayId: Int? = null) {
        val service = injectorService ?: return
        val targetId = displayId ?: currentDisplay?.displayId ?: 0
        ipcExecutor.execute {
            runCatching {
                service.injectKeyEvent(keyCode, metaState, targetId)
                val err = service.lastError
                if (err.isNotBlank()) {
                    _state.update { it.copy(errorMessage = err) }
                }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun injectPasteText(text: String, targetDisplayId: Int) {
        if (text.isEmpty()) return
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.injectPasteText(text, targetDisplayId)
                val err = service.lastError
                if (err.isNotBlank()) {
                    _state.update { it.copy(errorMessage = err) }
                }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    fun resetCursorToCenter() {
        val service = injectorService ?: return
        ipcExecutor.execute {
            runCatching {
                service.resetCursorToCenter()
                val x = service.cursorX
                val y = service.cursorY
                _state.update { it.copy(cursorX = x, cursorY = y) }
            }.onFailure { handleIpcFailure(it) }
        }
    }

    private fun refreshStatus(disconnected: Boolean = false) {
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = alive && checkPermissionGranted()
        val connected = !disconnected && injectorService != null

        val status = when {
            !alive -> ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING
            !granted -> ShizukuConnectionStatus.PERMISSION_REQUIRED
            connected -> ShizukuConnectionStatus.CONNECTED
            isBindingRequested -> ShizukuConnectionStatus.CONNECTING
            else -> ShizukuConnectionStatus.CONNECTING
        }

        _state.update {
            it.copy(
                status = status,
                isBinderAlive = alive,
                hasPermission = granted,
                isServiceBound = connected
            )
        }
    }

    private fun checkPermissionGranted(): Boolean {
        return try {
            !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleIpcFailure(t: Throwable) {
        Log.e(TAG, "AIDL call failed", t)
        _state.update {
            it.copy(errorMessage = appContext.getString(R.string.error_ipc))
        }
    }

    companion object {
        private const val TAG = "ShizukuServiceManager"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val PREF_DEFAULTS_APPLIED = "tap_defaults_applied"
        private const val KEY_POINTER_SPEED = "touchpad_pointer_speed"
        private const val KEY_NATURAL_SCROLLING = "touchpad_natural_scrolling"
        private const val KEY_TAP_TO_CLICK = "touchpad_tap_to_click"
        private const val KEY_TAP_DRAGGING = "touchpad_tap_dragging"
        const val MIN_POINTER_SPEED = -7
        const val MAX_POINTER_SPEED = 7
    }
}
