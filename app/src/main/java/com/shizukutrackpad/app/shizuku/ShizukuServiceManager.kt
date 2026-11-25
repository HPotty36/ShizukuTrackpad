package com.shizukutrackpad.app.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.shizukutrackpad.app.BuildConfig
import com.shizukutrackpad.app.IInputInjectorService
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
    val errorMessage: String? = null
)

class ShizukuServiceManager {

    private val _state = MutableStateFlow(ShizukuState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    @Volatile
    private var injectorService: IInputInjectorService? = null

    @Volatile
    private var currentDisplay: DisplayTarget? = null

    private var isBindingRequested: Boolean = false

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
                        errorMessage = "UserService binder is invalid"
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
        _state.update {
            it.copy(
                status = ShizukuConnectionStatus.SHIZUKU_NOT_RUNNING,
                isBinderAlive = false,
                isServiceBound = false,
                errorMessage = "Shizuku 서비스가 종료되었습니다."
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
                        errorMessage = if (granted) null else "Shizuku 권한이 거부되었습니다."
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
                    errorMessage = "Shizuku 앱이 실행 중인지 확인해 주세요."
                )
            }
            return
        }

        if (Shizuku.isPreV11()) {
            _state.update {
                it.copy(
                    status = ShizukuConnectionStatus.ERROR,
                    errorMessage = "Shizuku v11 이상이 필요합니다."
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
                    errorMessage = "서비스 바인딩 실패: ${t.message}"
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
    }

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
            it.copy(errorMessage = "IPC 오류: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "ShizukuServiceManager"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }
}
