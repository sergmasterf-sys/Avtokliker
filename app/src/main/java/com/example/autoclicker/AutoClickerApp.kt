package com.example.autoclicker

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.LogFileManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.service.FloatingOverlayService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AutoClickerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogFileManager.init(this)
        EventLogManager.log(EventLogManager.TAG_SYSTEM, "Application.onCreate")

        // 1. Очистка старых временных файлов и скриншотов в cacheDir
        cleanupCacheDir()

        // 2. Анализ причин предыдущих завершений процесса (Android 11+)
        logHistoricalExitReasons()

        // 3. Самовосстановление оверлея после убийства процесса (если пользователь не нажимал крестик)
        try {
            val repo = SettingsRepository.getInstance(this)
            if (repo.isOverlayWanted()) {
                EventLogManager.log(EventLogManager.TAG_SYSTEM, "Возобновление оверлея после перезапуска процесса")
                startService(Intent(this, FloatingOverlayService::class.java))
            }
        } catch (t: Throwable) {
            EventLogManager.logError(EventLogManager.TAG_SYSTEM, "Не удалось возобновить оверлей", t)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "TRIM_MEMORY_RUNNING_MODERATE (5)"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "TRIM_MEMORY_RUNNING_LOW (10)"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "TRIM_MEMORY_RUNNING_CRITICAL (15)"
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "TRIM_MEMORY_UI_HIDDEN (20)"
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "TRIM_MEMORY_BACKGROUND (40)"
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "TRIM_MEMORY_MODERATE (60)"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "TRIM_MEMORY_COMPLETE (80)"
            else -> "LEVEL_$level"
        }
        EventLogManager.log(EventLogManager.TAG_SYSTEM, "onTrimMemory: уровень $levelName")

        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            try {
                SmartEngine.clearGlobalTemplateAndCoordinateCaches()
            } catch (t: Throwable) {
                EventLogManager.logError(EventLogManager.TAG_SYSTEM, "Ошибка очистки кэшей по trimMemory", t)
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        EventLogManager.log(EventLogManager.TAG_SYSTEM, "onLowMemory: критически низкий уровень памяти", isError = true)
        try {
            SmartEngine.clearGlobalTemplateAndCoordinateCaches()
        } catch (t: Throwable) {
            EventLogManager.logError(EventLogManager.TAG_SYSTEM, "Ошибка очистки кэшей по lowMemory", t)
        }
    }

    private fun cleanupCacheDir() {
        try {
            val cache = cacheDir ?: return
            // Удаление старых zip_temp_*
            cache.listFiles()?.forEach { file ->
                if (file.name.startsWith("zip_temp_")) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
            // Удаление содержимого cacheDir/screens
            val screensDir = File(cache, "screens")
            if (screensDir.exists() && screensDir.isDirectory) {
                screensDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
        } catch (t: Throwable) {
            EventLogManager.logError(EventLogManager.TAG_SYSTEM, "Ошибка очистки cacheDir", t)
        }
    }

    private fun logHistoricalExitReasons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exitReasons = am.getHistoricalProcessExitReasons(packageName, 0, 5)
            if (exitReasons.isEmpty()) return

            val prefs = getSharedPreferences("app_exit_reasons", Context.MODE_PRIVATE)
            val lastRecordedTimestamp = prefs.getLong("last_recorded_exit_timestamp", 0L)

            // Отбираем только те завершения, которые новее сохранённой отметки времени
            val newExits = exitReasons
                .filter { it.timestamp > lastRecordedTimestamp }
                .sortedBy { it.timestamp }

            if (newExits.isEmpty()) return

            var maxTimestamp = lastRecordedTimestamp
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (exitInfo in newExits) {
                if (exitInfo.timestamp > maxTimestamp) {
                    maxTimestamp = exitInfo.timestamp
                }
                val reasonStr = when (exitInfo.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_CRASH -> "CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
                    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                    ApplicationExitInfo.REASON_OTHER -> "OTHER"
                    else -> "REASON_${exitInfo.reason}"
                }
                val timeStr = timeFormat.format(Date(exitInfo.timestamp))
                val desc = exitInfo.description ?: "нет"
                val isError = when (exitInfo.reason) {
                    ApplicationExitInfo.REASON_LOW_MEMORY,
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> true
                    else -> false
                }
                EventLogManager.log(
                    EventLogManager.TAG_SYSTEM,
                    "Завершение процесса: причина=$reasonStr, время=$timeStr, description=$desc",
                    isError = isError
                )
            }
            if (maxTimestamp > lastRecordedTimestamp) {
                prefs.edit().putLong("last_recorded_exit_timestamp", maxTimestamp).apply()
            }
        } catch (t: Throwable) {
            EventLogManager.logError(EventLogManager.TAG_SYSTEM, "Ошибка чтения getHistoricalProcessExitReasons", t)
        }
    }
}
