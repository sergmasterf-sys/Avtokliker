package com.example.autoclicker.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
import com.example.autoclicker.service.AccessibilityStatus
import com.example.autoclicker.service.FloatingOverlayService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = SettingsRepository.getInstance(application)

    val settings: StateFlow<ClickerSettings> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), repo.getLatestSettings())

    val logs: StateFlow<List<String>> = EventLogManager.logs

    val learnedCount: StateFlow<Int> = com.example.autoclicker.data.SmartLearnedStore.countFlow

    fun clearLogs() {
        EventLogManager.clearAll()
    }

    fun clearLearnedCoordinates() {
        com.example.autoclicker.data.SmartLearnedStore.clear()
        com.example.autoclicker.engine.SmartEngine.clearGlobalTemplateAndCoordinateCaches()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: хранилище выученных координат очищено")
    }

    val isAccessibilityConnected: StateFlow<Boolean> =
        AccessibilityServiceHolder.service
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccessibilityServiceHolder.isConnected)

    val isAccessibilityEnabledInSystem: StateFlow<Boolean> =
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(AccessibilityStatus.isEnabledInSystem(getApplication()))
                kotlinx.coroutines.delay(1000)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AccessibilityStatus.isEnabledInSystem(application)
        )

    val isOverlayGranted: StateFlow<Boolean> =
        kotlinx.coroutines.flow.flow {
            while (true) {
                emit(Settings.canDrawOverlays(getApplication()))
                kotlinx.coroutines.delay(1000)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings.canDrawOverlays(application))

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        getApplication<Application>().startActivity(intent)
    }

    fun openOverlaySettings() {
        val app = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${app.packageName}")
        ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        app.startActivity(intent)
    }

    fun requestIgnoreBatteryOptimizations() {
        val app = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${app.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    app.startActivity(intent)
                }
            }
        } catch (t: Throwable) {
            EventLogManager.log(EventLogManager.TAG_SYSTEM, "Battery opt request failed: ${t.message}", isError = true)
        }
    }

    fun launchOverlayService() {
        val app = getApplication<Application>()
        repo.setOverlayWanted(true)
        requestIgnoreBatteryOptimizations()
        try {
            val intent = Intent(app, FloatingOverlayService::class.java)
            app.startService(intent)
            EventLogManager.log(EventLogManager.TAG_OVERLAY, "Вызов плавающей кнопки")
        } catch (t: Throwable) {
            EventLogManager.logError(EventLogManager.TAG_OVERLAY, "Не удалось запустить оверлей", t)
        }
    }
}
