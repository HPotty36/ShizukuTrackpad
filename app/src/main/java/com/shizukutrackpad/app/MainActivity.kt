package com.shizukutrackpad.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.shizukutrackpad.app.ui.TrackpadScreen
import com.shizukutrackpad.app.ui.TrackpadViewModel
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
}
