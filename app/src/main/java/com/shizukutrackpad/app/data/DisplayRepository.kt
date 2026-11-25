package com.shizukutrackpad.app.data

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayTarget(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isExternal: Boolean
) {
    val label: String
        get() {
            val tag = if (isExternal) "외장 모니터" else "기본 화면 (테스트용)"
            return "ID $displayId · $tag (${width}×${height})"
        }
}

class DisplayRepository(context: Context) {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val _displays = MutableStateFlow<List<DisplayTarget>>(emptyList())
    val displays: StateFlow<List<DisplayTarget>> = _displays.asStateFlow()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refreshDisplays()
        override fun onDisplayRemoved(displayId: Int) = refreshDisplays()
        override fun onDisplayChanged(displayId: Int) = refreshDisplays()
    }

    init {
        refreshDisplays()
        displayManager.registerDisplayListener(
            displayListener,
            Handler(Looper.getMainLooper())
        )
    }

    fun refreshDisplays() {
        val list = displayManager.displays
            .map { display -> toDisplayTarget(display) }
            .sortedWith(
                compareByDescending<DisplayTarget> { it.isExternal }
                    .thenBy { it.displayId }
            )
        _displays.value = list
    }

    fun release() {
        displayManager.unregisterDisplayListener(displayListener)
    }

    @Suppress("DEPRECATION")
    private fun toDisplayTarget(display: Display): DisplayTarget {
        val mode = display.mode
        var width = mode?.physicalWidth ?: 0
        var height = mode?.physicalHeight ?: 0

        if (width <= 0 || height <= 0) {
            val size = Point()
            display.getRealSize(size)
            width = size.x
            height = size.y
        }

        var safeWidth = width.coerceAtLeast(1)
        var safeHeight = height.coerceAtLeast(1)
        val rotation = display.rotation
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val tmp = safeWidth
            safeWidth = safeHeight
            safeHeight = tmp
        }
        val displayId = display.displayId
        val rawName = display.name?.takeIf { it.isNotBlank() }
            ?: if (displayId == Display.DEFAULT_DISPLAY) "Built-in Display" else "External Display"

        return DisplayTarget(
            displayId = displayId,
            name = rawName,
            width = safeWidth,
            height = safeHeight,
            isExternal = displayId != Display.DEFAULT_DISPLAY
        )
    }
}
