package com.shizukutrackpad.app

import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.shizukutrackpad.app.ui.TrackpadScreen
import com.shizukutrackpad.app.ui.TrackpadViewModel
import com.shizukutrackpad.app.service.VirtualTouchpad
import com.shizukutrackpad.app.ui.theme.ShizukuTrackpadTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TrackpadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            ShizukuTrackpadTheme {
                TrackpadScreen(viewModel = viewModel)
            }
        }
    }

    // When the pointer of our own virtual touchpad is over this window (phone display), its
    // events must not reach the UI: they would interrupt the finger stream being forwarded
    // and could feed back into the touchpad.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean =
        if (isFromVirtualTouchpad(ev)) true else super.dispatchTouchEvent(ev)

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        if (isFromVirtualTouchpad(ev)) true else super.dispatchGenericMotionEvent(ev)

    private fun isFromVirtualTouchpad(ev: MotionEvent): Boolean {
        val device = ev.device ?: return false
        return device.vendorId == VirtualTouchpad.VENDOR_ID && device.productId == VirtualTouchpad.PRODUCT_ID
    }
}
