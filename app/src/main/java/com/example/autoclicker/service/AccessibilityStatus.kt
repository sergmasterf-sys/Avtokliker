package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

object AccessibilityStatus {
    @Volatile
    var isRunning: Boolean = false
        private set

    fun setRunning(running: Boolean) {
        isRunning = running
    }

    /**
     * Сервис включён в системных настройках или уже активен в процессе.
     */
    fun isEnabledInSystem(context: Context): Boolean {
        // 1. Если сервис уже подключен и работает в процессе — он гарантированно включен
        if (AccessibilityServiceHolder.isConnected || AutoClickAccessibilityService.instance != null) {
            return true
        }

        val targetClass = AutoClickAccessibilityService::class.java.simpleName
        val targetFullClass = AutoClickAccessibilityService::class.java.name

        // 2. Проверка через AccessibilityManager (официальный API Android)
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                val enabledList = mutableListOf<AccessibilityServiceInfo>()
                try {
                    enabledList.addAll(am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
                } catch (_: Exception) {}
                try {
                    enabledList.addAll(am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC))
                } catch (_: Exception) {}

                val hit = enabledList.any { info ->
                    val id = info.id ?: ""
                    val siName = info.resolveInfo?.serviceInfo?.name ?: ""
                    id.contains(targetClass, ignoreCase = true) ||
                        siName.contains(targetClass, ignoreCase = true) ||
                        id.contains(targetFullClass, ignoreCase = true) ||
                        siName.contains(targetFullClass, ignoreCase = true)
                }
                if (hit) return true
            }
        } catch (_: Exception) {
        }

        // 3. Fallback: проверка через Settings.Secure (сохраняет доступ даже если сервис временно отвязался)
        return try {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            raw.split(':').any { part ->
                val p = part.trim()
                p.contains(targetClass, ignoreCase = true) ||
                    p.contains(targetFullClass, ignoreCase = true)
            }
        } catch (_: Exception) {
            false
        }
    }
}
