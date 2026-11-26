package com.shizukutrackpad.app.ui

import android.content.Context

/**
 * Persists user-facing trackpad preferences across launches (SharedPreferences, no extra dependency).
 */
class TrackpadSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): TrackpadSettings {
        val defaults = TrackpadSettings()
        return TrackpadSettings(
            sensitivity = prefs.getFloat(KEY_SENSITIVITY, defaults.sensitivity)
                .coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY),
            naturalScroll = prefs.getBoolean(KEY_NATURAL_SCROLL, defaults.naturalScroll),
            showPhysicalButtons = prefs.getBoolean(KEY_SHOW_PHYSICAL_BUTTONS, defaults.showPhysicalButtons),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn)
        )
    }

    fun saveSettings(settings: TrackpadSettings) {
        prefs.edit()
            .putFloat(KEY_SENSITIVITY, settings.sensitivity)
            .putBoolean(KEY_NATURAL_SCROLL, settings.naturalScroll)
            .putBoolean(KEY_SHOW_PHYSICAL_BUTTONS, settings.showPhysicalButtons)
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .apply()
    }

    fun loadShortcutTrayExpanded(): Boolean = prefs.getBoolean(KEY_TRAY_EXPANDED, true)

    fun saveShortcutTrayExpanded(expanded: Boolean) {
        prefs.edit().putBoolean(KEY_TRAY_EXPANDED, expanded).apply()
    }

    companion object {
        const val MIN_SENSITIVITY = 0.5f
        const val MAX_SENSITIVITY = 3.0f

        private const val PREFS_NAME = "trackpad_settings"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_NATURAL_SCROLL = "natural_scroll"
        private const val KEY_SHOW_PHYSICAL_BUTTONS = "show_physical_buttons"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_TRAY_EXPANDED = "shortcut_tray_expanded"
    }
}
