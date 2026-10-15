package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Репозиторий хранения настроек в SharedPreferences.
 * Только умный режим: без макросов и отложенного старта.
 */
class SettingsRepository(val context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ClickerSettings> = _settings.asStateFlow()

    private fun loadSettings(): ClickerSettings {
        val overlayX = prefs.getInt(KEY_OVERLAY_X, 50)
        val overlayY = prefs.getInt(KEY_OVERLAY_Y, 150)
        val isSmartMode = prefs.getBoolean(KEY_SMART_MODE, true)
        val isDebugScreenshots = prefs.getBoolean(KEY_DEBUG_SCREENSHOTS, false)
        val neonBrightness = prefs.getInt(KEY_NEON_BRIGHTNESS, 85)
        com.example.autoclicker.ui.theme.NeonTheme.brightness = neonBrightness / 100f

        return ClickerSettings(
            overlayX = overlayX,
            overlayY = overlayY,
            isSmartMode = isSmartMode,
            isDebugScreenshots = isDebugScreenshots,
            neonBrightness = neonBrightness
        )
    }

    fun updateOverlayPosition(x: Int, y: Int) {
        prefs.edit()
            .putInt(KEY_OVERLAY_X, x)
            .putInt(KEY_OVERLAY_Y, y)
            .apply()
        _settings.value = loadSettings()
    }

    fun updateSmartMode(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SMART_MODE, enabled)
            .apply()
        _settings.value = loadSettings()
    }

    fun updateDebugScreenshots(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DEBUG_SCREENSHOTS, enabled)
            .apply()
        _settings.value = loadSettings()
    }

    fun setRunActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_RUN_ACTIVE, active).apply()
    }

    fun isRunActive(): Boolean = prefs.getBoolean(KEY_RUN_ACTIVE, false)

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    /** Флаг: пользователь не нажимал крестик — сервис можно поднимать заново. */
    fun setOverlayWanted(wanted: Boolean) {
        prefs.edit().putBoolean(KEY_OVERLAY_WANTED, wanted).apply()
    }

    fun isOverlayWanted(): Boolean = prefs.getBoolean(KEY_OVERLAY_WANTED, false)

    fun updateNeonBrightness(brightness: Int) {
        val b = brightness.coerceIn(0, 100)
        prefs.edit().putInt(KEY_NEON_BRIGHTNESS, b).apply()
        com.example.autoclicker.ui.theme.NeonTheme.brightness = b / 100f
        _settings.value = loadSettings()
    }

    fun updateRunSmart(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_MODE, enabled).apply()
        _settings.value = loadSettings()
    }

    fun resetSmartFlags() {
        prefs.edit()
            .remove(KEY_SMART_MODE)
            .remove(KEY_DEBUG_SCREENSHOTS)
            .apply()
        _settings.value = loadSettings()
    }

    fun resetNeonBrightness() {
        prefs.edit().remove(KEY_NEON_BRIGHTNESS).apply()
        com.example.autoclicker.ui.theme.NeonTheme.brightness = 85 / 100f
        _settings.value = loadSettings()
    }

    fun getLatestSettings(): ClickerSettings = _settings.value

    companion object {
        private const val PREFS_NAME = "auto_clicker_prefs"
        private const val KEY_NEON_BRIGHTNESS = "neon_brightness"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_SMART_MODE = "is_smart_mode"
        private const val KEY_DEBUG_SCREENSHOTS = "is_debug_screenshots"
        private const val KEY_RUN_ACTIVE = "run_active"
        private const val KEY_PAUSED = "run_paused"
        private const val KEY_OVERLAY_WANTED = "overlay_wanted"

        @Volatile
        private var INSTANCE: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
