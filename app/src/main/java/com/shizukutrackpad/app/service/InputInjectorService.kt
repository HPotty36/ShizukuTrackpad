package com.shizukutrackpad.app.service

import android.content.ClipData
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.annotation.Keep
import com.shizukutrackpad.app.IInputInjectorService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Shizuku UserService that injects mouse MotionEvents and KeyEvents
 * into a target display via hidden InputManager APIs.
 */
class InputInjectorService : IInputInjectorService.Stub {

    @Keep
    constructor() {
        initializeHiddenApis()
    }

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    private val stateLock = Any()

    private var targetDisplayId: Int = -1
    private var displayWidth: Int = 1920
    private var displayHeight: Int = 1080
    private var cursorX: Float = 960f
    private var cursorY: Float = 540f
    private var isLeftDragActive: Boolean = false
    private var dragDownTime: Long = 0L
    private var lastError: String = ""

    private var touchpad: VirtualTouchpad? = null
    private var touchpadDisplayId: Int = Int.MIN_VALUE

    private var inputManagerInstance: Any? = null
    private var injectInputEventMethod: Method? = null
    private var setDisplayIdMethod: Method? = null
    private var setActionButtonMethod: Method? = null

    private fun initializeHiddenApis() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("")
            }

            resolveInputManager()

            setDisplayIdMethod = InputEvent::class.java.getMethod(
                "setDisplayId",
                Int::class.javaPrimitiveType
            )

            setActionButtonMethod = runCatching {
                MotionEvent::class.java.getMethod(
                    "setActionButton",
                    Int::class.javaPrimitiveType
                )
            }.getOrNull()

            lastError = ""
            Log.i(TAG, "InputInjectorService initialized successfully")
        } catch (t: Throwable) {
            lastError = "Init failed: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, lastError, t)
        }
    }

    private fun resolveInputManager() {
        // Primary: InputManager.getInstance().injectInputEvent(InputEvent, int)
        val inputManagerResult = runCatching {
            val clazz = Class.forName("android.hardware.input.InputManager")
            val instance = clazz.getMethod("getInstance").invoke(null)
                ?: error("InputManager.getInstance() returned null")
            val injectMethod = clazz.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            instance to injectMethod
        }

        if (inputManagerResult.isSuccess) {
            val (instance, method) = inputManagerResult.getOrThrow()
            inputManagerInstance = instance
            injectInputEventMethod = method
            return
        }

        // Fallback for Android 14+ where InputManagerGlobal may host getInstance/injectInputEvent
        val globalResult = runCatching {
            val clazz = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = clazz.getMethod("getInstance").invoke(null)
                ?: error("InputManagerGlobal.getInstance() returned null")
            val injectMethod = clazz.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            )
            instance to injectMethod
        }

        if (globalResult.isSuccess) {
            val (instance, method) = globalResult.getOrThrow()
            inputManagerInstance = instance
            injectInputEventMethod = method
            return
        }

        throw inputManagerResult.exceptionOrNull()
            ?: globalResult.exceptionOrNull()
            ?: IllegalStateException("Unable to resolve InputManager.injectInputEvent")
    }

    override fun destroy() {
        Log.i(TAG, "Destroying InputInjectorService")
        synchronized(stateLock) { stopTouchpadLocked() }
        exitProcess(0)
    }

    // ---- Virtual touchpad ----

    override fun startTouchpad(displayId: Int): Boolean {
        synchronized(stateLock) {
            targetDisplayId = displayId
            val existing = touchpad
            if (existing != null && existing.isOpen) {
                associateTouchpadLocked(displayId)
                return true
            }
            return try {
                // Bind the port to the display first so the device is configured for it from the start.
                associateTouchpadLocked(displayId)
                val device = VirtualTouchpad(TOUCHPAD_PHYS)
                device.create()
                touchpad = device
                if (!device.awaitStarted(TOUCHPAD_START_TIMEOUT_MS)) {
                    Log.w(TAG, "uhid touchpad created but not started yet")
                }
                lastError = ""
                true
            } catch (t: Throwable) {
                lastError = "Touchpad failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, lastError, t)
                stopTouchpadLocked()
                false
            }
        }
    }

    override fun stopTouchpad() {
        synchronized(stateLock) { stopTouchpadLocked() }
    }

    override fun sendTouchpadFrame(
        contactIds: IntArray,
        xs: IntArray,
        ys: IntArray,
        tips: BooleanArray,
        buttons: Int
    ) {
        val device = touchpad ?: return
        val count = minOf(contactIds.size, xs.size, ys.size, tips.size)
        val contacts = List(count) { i -> VirtualTouchpad.Contact(contactIds[i], xs[i], ys[i], tips[i]) }
        device.sendFrame(contacts, buttons)
    }

    override fun getTouchpadSetting(key: String): String? {
        if (key !in TOUCHPAD_SETTING_KEYS) return null
        val output = runShell("settings", "get", "system", key)?.trim()
        return output?.takeUnless { it.isEmpty() || it == "null" }
    }

    override fun putTouchpadSetting(key: String, value: String): Boolean {
        if (key !in TOUCHPAD_SETTING_KEYS || !SETTING_VALUE.matches(value)) return false
        return runShell("settings", "put", "system", key, value) != null
    }

    private fun stopTouchpadLocked() {
        touchpad?.destroy()
        touchpad = null
        if (touchpadDisplayId != Int.MIN_VALUE) {
            callInputManager(REMOVE_ASSOCIATION, TOUCHPAD_PHYS)
            touchpadDisplayId = Int.MIN_VALUE
        }
    }

    /** Pins the touchpad (and so its pointer) to displayId through its input port. */
    private fun associateTouchpadLocked(displayId: Int) {
        if (displayId == touchpadDisplayId) return
        val uniqueId = displayUniqueId(displayId)
        if (uniqueId == null) {
            Log.w(TAG, "No uniqueId for display $displayId; pointer stays on the default display")
            return
        }
        callInputManager(REMOVE_ASSOCIATION, TOUCHPAD_PHYS)
        if (callInputManager(ADD_ASSOCIATION, TOUCHPAD_PHYS, uniqueId)) {
            touchpadDisplayId = displayId
            Log.i(TAG, "Touchpad bound to display $displayId ($uniqueId)")
        }
    }

    private fun displayUniqueId(displayId: Int): String? = runCatching {
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
        val info = globalClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
            .invoke(global, displayId) ?: return null
        info.javaClass.getField("uniqueId").get(info) as String?
    }.onFailure { Log.w(TAG, "displayUniqueId failed: ${it.message}") }.getOrNull()

    /** Calls the first IInputManager method in names that takes args.size Strings. */
    private fun callInputManager(names: List<String>, vararg args: String): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, Context.INPUT_SERVICE) as IBinder
        val service = Class.forName("android.hardware.input.IInputManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return false
        for (name in names) {
            val method = service.javaClass.methods.firstOrNull { m ->
                m.name == name && m.parameterTypes.size == args.size &&
                    m.parameterTypes.all { it == String::class.java }
            } ?: continue
            method.invoke(service, *args)
            return true
        }
        Log.w(TAG, "IInputManager has none of $names")
        false
    }.onFailure { Log.w(TAG, "IInputManager call ${names.first()} failed: ${it.cause ?: it}") }
        .getOrDefault(false)

    private fun runShell(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output else null
    }.getOrNull()

    override fun setTargetDisplay(displayId: Int, width: Int, height: Int) {
        synchronized(stateLock) {
            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val displayChanged = targetDisplayId != displayId

            if (displayChanged && isLeftDragActive) {
                endLeftDragLocked()
            }

            targetDisplayId = displayId
            displayWidth = safeWidth
            displayHeight = safeHeight

            if (displayChanged) {
                cursorX = safeWidth / 2f
                cursorY = safeHeight / 2f
            } else {
                clampCursorLocked()
            }

            if (touchpad?.isOpen == true) {
                // The system pointer belongs to the virtual touchpad; move it with the display.
                associateTouchpadLocked(displayId)
            } else {
                // Send an initial hover event so the pointer appears on the target display
                injectHoverMoveLocked(cursorX, cursorY, targetDisplayId)
            }
        }
    }

    override fun moveCursor(dx: Float, dy: Float) {
        synchronized(stateLock) {
            cursorX += dx
            cursorY += dy
            clampCursorLocked()
            if (isLeftDragActive) {
                injectMouseEvent(
                    downTime = dragDownTime,
                    eventTime = SystemClock.uptimeMillis(),
                    action = MotionEvent.ACTION_MOVE,
                    x = cursorX,
                    y = cursorY,
                    buttonState = MotionEvent.BUTTON_PRIMARY,
                    actionButton = 0,
                    pressure = 1.0f,
                    displayId = targetDisplayId
                )
            } else {
                injectHoverMoveLocked(cursorX, cursorY, targetDisplayId)
            }
        }
    }

    override fun leftClick() {
        synchronized(stateLock) {
            injectButtonClickLocked(MotionEvent.BUTTON_PRIMARY)
        }
    }

    override fun rightClick() {
        synchronized(stateLock) {
            injectButtonClickLocked(MotionEvent.BUTTON_SECONDARY)
        }
    }

    override fun scroll(hScroll: Float, vScroll: Float) {
        synchronized(stateLock) {
            if (hScroll == 0f && vScroll == 0f) return
            val now = SystemClock.uptimeMillis()
            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = cursorX
                    this.y = cursorY
                    this.pressure = 0.0f
                    this.size = 1.0f
                    setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                    setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
                }
            )
            val event = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_SCROLL,
                1,
                properties,
                coords,
                0,
                0,
                1.0f,
                1.0f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0
            )
            try {
                routeAndInject(event, targetDisplayId)
            } finally {
                event.recycle()
            }
        }
    }

    override fun beginLeftDrag() {
        synchronized(stateLock) {
            beginLeftDragLocked()
        }
    }

    override fun dragMove(dx: Float, dy: Float) {
        synchronized(stateLock) {
            if (!isLeftDragActive) {
                beginLeftDragLocked()
            }
            cursorX += dx
            cursorY += dy
            clampCursorLocked()
            val now = SystemClock.uptimeMillis()
            injectMouseEvent(
                downTime = dragDownTime,
                eventTime = now,
                action = MotionEvent.ACTION_MOVE,
                x = cursorX,
                y = cursorY,
                buttonState = MotionEvent.BUTTON_PRIMARY,
                actionButton = 0,
                pressure = 1.0f,
                displayId = targetDisplayId
            )
        }
    }

    override fun endLeftDrag() {
        synchronized(stateLock) {
            endLeftDragLocked()
        }
    }

    private fun beginLeftDragLocked() {
        if (isLeftDragActive) return
        val downTime = SystemClock.uptimeMillis()
        dragDownTime = downTime
        isLeftDragActive = true

        injectMouseEvent(
            downTime = downTime,
            eventTime = downTime,
            action = MotionEvent.ACTION_DOWN,
            x = cursorX,
            y = cursorY,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            actionButton = 0,
            pressure = 1.0f,
            displayId = targetDisplayId
        )

        injectMouseEvent(
            downTime = downTime,
            eventTime = downTime,
            action = MotionEvent.ACTION_BUTTON_PRESS,
            x = cursorX,
            y = cursorY,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            pressure = 1.0f,
            displayId = targetDisplayId
        )
    }

    private fun endLeftDragLocked() {
        if (!isLeftDragActive) return
        val upTime = SystemClock.uptimeMillis()

        injectMouseEvent(
            downTime = dragDownTime,
            eventTime = upTime,
            action = MotionEvent.ACTION_BUTTON_RELEASE,
            x = cursorX,
            y = cursorY,
            buttonState = 0,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            pressure = 0.0f,
            displayId = targetDisplayId
        )

        injectMouseEvent(
            downTime = dragDownTime,
            eventTime = upTime,
            action = MotionEvent.ACTION_UP,
            x = cursorX,
            y = cursorY,
            buttonState = 0,
            actionButton = 0,
            pressure = 0.0f,
            displayId = targetDisplayId
        )
        isLeftDragActive = false
    }

    private fun injectButtonClickLocked(button: Int) {
        val x = cursorX
        val y = cursorY
        val displayId = targetDisplayId
        val downTime = SystemClock.uptimeMillis()

        // Standard Android mouse click sequence:
        // ACTION_DOWN -> ACTION_BUTTON_PRESS -> ACTION_BUTTON_RELEASE -> ACTION_UP
        injectMouseEvent(
            downTime = downTime,
            eventTime = downTime,
            action = MotionEvent.ACTION_DOWN,
            x = x,
            y = y,
            buttonState = button,
            actionButton = 0,
            pressure = 1.0f,
            displayId = displayId
        )

        injectMouseEvent(
            downTime = downTime,
            eventTime = downTime,
            action = MotionEvent.ACTION_BUTTON_PRESS,
            x = x,
            y = y,
            buttonState = button,
            actionButton = button,
            pressure = 1.0f,
            displayId = displayId
        )

        SystemClock.sleep(15L)
        val upTime = SystemClock.uptimeMillis()

        injectMouseEvent(
            downTime = downTime,
            eventTime = upTime,
            action = MotionEvent.ACTION_BUTTON_RELEASE,
            x = x,
            y = y,
            buttonState = 0,
            actionButton = button,
            pressure = 0.0f,
            displayId = displayId
        )

        injectMouseEvent(
            downTime = downTime,
            eventTime = upTime,
            action = MotionEvent.ACTION_UP,
            x = x,
            y = y,
            buttonState = 0,
            actionButton = 0,
            pressure = 0.0f,
            displayId = displayId
        )
    }

    override fun injectKeyEvent(keyCode: Int, metaState: Int, displayId: Int) {
        synchronized(stateLock) {
            val resolvedDisplayId = if (displayId >= 0) displayId else targetDisplayId
            injectKeyComboOnDisplayLocked(keyCode, metaState, resolvedDisplayId)
        }
    }

    override fun injectPasteText(text: String, targetDisplayId: Int) {
        if (text.isEmpty()) return
        synchronized(stateLock) {
            setSystemClipboardBestEffort(text)
            SystemClock.sleep(40L)
            injectKeyComboOnDisplayLocked(
                keyCode = KeyEvent.KEYCODE_V,
                metaState = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
                displayId = targetDisplayId
            )
        }
    }

    private fun injectKeyComboOnDisplayLocked(keyCode: Int, metaState: Int, displayId: Int) {
        val downTime = SystemClock.uptimeMillis()

        val modifiers = buildList {
            if ((metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_RIGHT_ON)) != 0) {
                add(KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            }
            if ((metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_RIGHT_ON)) != 0) {
                add(KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
            }
            if ((metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON)) != 0) {
                add(KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
            }
            if ((metaState and (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_RIGHT_ON)) != 0) {
                add(KeyEvent.KEYCODE_META_LEFT to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON))
            }
        }

        var currentMeta = 0
        for ((modKeyCode, modMeta) in modifiers) {
            currentMeta = currentMeta or modMeta
            val modDown = KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_DOWN,
                modKeyCode,
                0,
                currentMeta,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
            )
            routeAndInject(modDown, displayId)
        }

        val finalMeta = KeyEvent.normalizeMetaState(currentMeta or metaState)
        val keyDown = KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            finalMeta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        routeAndInject(keyDown, displayId)

        SystemClock.sleep(15L)

        val keyUp = KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            finalMeta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD
        )
        routeAndInject(keyUp, displayId)

        for ((modKeyCode, modMeta) in modifiers.asReversed()) {
            currentMeta = currentMeta and modMeta.inv()
            val modUp = KeyEvent(
                downTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                modKeyCode,
                0,
                currentMeta,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                0,
                InputDevice.SOURCE_KEYBOARD
            )
            routeAndInject(modUp, displayId)
        }
    }

    private fun setSystemClipboardBestEffort(text: String) {
        runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val binder = serviceManagerClass
                .getMethod("getService", String::class.java)
                .invoke(null, Context.CLIPBOARD_SERVICE) as? IBinder ?: return

            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val clipboard = stubClass
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder) ?: return

            val clipData = ClipData.newPlainText("ShizukuTrackpad", text)
            val candidates = clipboard.javaClass.methods.filter {
                it.name == "setPrimaryClip" &&
                    it.parameterTypes.isNotEmpty() &&
                    it.parameterTypes[0] == ClipData::class.java
            }

            for (method in candidates) {
                var stringIndex = 0
                val args = Array<Any?>(method.parameterTypes.size) { index ->
                    when (method.parameterTypes[index]) {
                        ClipData::class.java -> clipData
                        String::class.java -> {
                            val value = if (stringIndex == 0) "com.android.shell" else null
                            stringIndex++
                            value
                        }
                        Int::class.javaPrimitiveType, Int::class.javaObjectType -> 0
                        Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> false
                        else -> null
                    }
                }
                val invoked = runCatching { method.invoke(clipboard, *args) }
                if (invoked.isSuccess) return
            }
        }.onFailure {
            Log.w(TAG, "IClipboard fallback skipped: ${it.message}")
        }
    }

    override fun getCursorX(): Float = synchronized(stateLock) { cursorX }

    override fun getCursorY(): Float = synchronized(stateLock) { cursorY }

    override fun resetCursorToCenter() {
        synchronized(stateLock) {
            if (isLeftDragActive) {
                endLeftDragLocked()
            }
            cursorX = displayWidth / 2f
            cursorY = displayHeight / 2f
            clampCursorLocked()
            injectHoverMoveLocked(cursorX, cursorY, targetDisplayId)
        }
    }

    override fun getLastError(): String = synchronized(stateLock) { lastError }

    private fun clampCursorLocked() {
        val maxX = (displayWidth - 1).coerceAtLeast(0).toFloat()
        val maxY = (displayHeight - 1).coerceAtLeast(0).toFloat()
        cursorX = cursorX.coerceIn(0f, maxX)
        cursorY = cursorY.coerceIn(0f, maxY)
    }

    private fun injectHoverMoveLocked(x: Float, y: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        injectMouseEvent(
            downTime = now,
            eventTime = now,
            action = MotionEvent.ACTION_HOVER_MOVE,
            x = x,
            y = y,
            buttonState = 0,
            actionButton = 0,
            pressure = 0.0f,
            displayId = displayId
        )
    }

    private fun injectMouseEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        actionButton: Int,
        pressure: Float,
        displayId: Int
    ) {
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                this.pressure = pressure
                this.size = 1.0f
            }
        )

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            buttonState,
            1.0f,
            1.0f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )

        try {
            if (actionButton != 0) {
                setActionButtonMethod?.invoke(event, actionButton)
            }
            routeAndInject(event, displayId)
        } finally {
            event.recycle()
        }
    }

    private fun routeAndInject(event: InputEvent, displayId: Int) {
        try {
            val manager = inputManagerInstance
            val injectMethod = injectInputEventMethod
            val displayMethod = setDisplayIdMethod

            if (manager == null || injectMethod == null || displayMethod == null) {
                lastError = "InputManager reflection not initialized"
                return
            }

            // Route both MotionEvent and KeyEvent to the target display before injection
            displayMethod.invoke(event, displayId)
            injectMethod.invoke(manager, event, INJECT_INPUT_EVENT_MODE_ASYNC)
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            lastError = "Inject failed: ${cause.javaClass.simpleName}: ${cause.message}"
            Log.e(TAG, lastError, t)
        }
    }

    companion object {
        private const val TAG = "InputInjectorService"
        private const val INJECT_INPUT_EVENT_MODE_ASYNC = 0
        private const val TOUCHPAD_PHYS = "shizukutrackpad/touchpad0"
        private const val TOUCHPAD_START_TIMEOUT_MS = 3_000L
        private val ADD_ASSOCIATION = listOf("addUniqueIdAssociationByPort", "addUniqueIdAssociation")
        private val REMOVE_ASSOCIATION = listOf("removeUniqueIdAssociationByPort", "removeUniqueIdAssociation")
        private val SETTING_VALUE = Regex("-?\\d{1,2}")

        /** System settings Android reads for every touchpad (Settings > System > Touchpad). */
        private val TOUCHPAD_SETTING_KEYS = setOf(
            "touchpad_pointer_speed",
            "touchpad_natural_scrolling",
            "touchpad_tap_to_click",
            "touchpad_tap_dragging",
            "touchpad_right_click_zone"
        )
    }
}
