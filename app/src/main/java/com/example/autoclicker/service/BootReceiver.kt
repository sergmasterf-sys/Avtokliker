package com.example.autoclicker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * После перезагрузки телефона и после обновления приложения сразу включает службу доступности
 * (если один раз выдан WRITE_SECURE_SETTINGS). Больше не нужно включать её руками.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> AccessibilityWatchdog.enableNow(context.applicationContext)
        }
    }
}
