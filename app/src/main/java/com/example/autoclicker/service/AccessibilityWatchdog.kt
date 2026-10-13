package com.example.autoclicker.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.example.autoclicker.MainActivity
import com.example.autoclicker.R
import com.example.autoclicker.data.EventLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Следит за подключением службы доступности.
 *
 * Если служба отвалилась (система пометила её «упавшей» после убийства процесса):
 *  1) если приложению выдано WRITE_SECURE_SETTINGS — само выключает и включает службу;
 *  2) иначе показывает уведомление с кнопкой «Открыть настройки».
 *
 * Выдать разрешение один раз можно без компьютера, через беспроводную отладку
 * на самом устройстве (Shizuku / LADB): pm grant <applicationId> android.permission.WRITE_SECURE_SETTINGS
 */
object AccessibilityWatchdog {

    private const val CHANNEL_ID = "autoclicker_accessibility_alert"
    private const val NOTIFICATION_ID = 1002
    private const val CHECK_INTERVAL_MS = 4000L
    private const val GRACE_MS = 8000L
    private const val RESTART_COOLDOWN_MS = 30_000L

    private var job: Job? = null

    @Volatile
    private var notified = false

    fun hasSecureSettingsPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            var badSince = 0L
            var lastRestartAt = 0L
            while (isActive) {
                try {
                    if (AccessibilityServiceHolder.isConnected) {
                        badSince = 0L
                        cancelNotification(app)
                    } else {
                        val now = System.currentTimeMillis()
                        if (badSince == 0L) badSince = now
                        if (now - badSince >= GRACE_MS) {
                            val enabledInSystem = AccessibilityStatus.isEnabledInSystem(app)
                            // Есть WRITE_SECURE_SETTINGS: сами включаем/перезапускаем службу
                            // (и если система её выключила, и если она «упала»)
                            if (hasSecureSettingsPermission(app) &&
                                now - lastRestartAt >= RESTART_COOLDOWN_MS
                            ) {
                                lastRestartAt = now
                                restartService(app)
                            }
                            if (!AccessibilityServiceHolder.isConnected) {
                                showNotification(app, enabledInSystem)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    EventLogManager.log(
                        EventLogManager.TAG_ACCESSIBILITY,
                        "WATCHDOG: ошибка проверки службы доступности: ${t.message}",
                        isError = true
                    )
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Быстро включает службу, если её нет в списке включённых (без выкл/вкл, без ожидания).
     * Безопасно вызывать часто: без WRITE_SECURE_SETTINGS ничего не делает.
     * Используется при запуске приложения и после перезагрузки устройства.
     */
    fun enableNow(context: Context): Boolean {
        return try {
            if (!hasSecureSettingsPermission(context)) return false
            val component = ComponentName(context, AutoClickAccessibilityService::class.java)
            val resolver = context.contentResolver
            val raw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val list = raw.split(':').filter { it.isNotBlank() }
            val already = list.any { ComponentName.unflattenFromString(it.trim()) == component }
            if (!already) {
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    (list + component.flattenToString()).joinToString(":")
                )
                EventLogManager.log(
                    EventLogManager.TAG_ACCESSIBILITY,
                    "WATCHDOG: служба доступности была выключена системой -> включена автоматически"
                )
            }
            if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Выключает и снова включает службу через Settings.Secure (нужен WRITE_SECURE_SETTINGS). */
    private suspend fun restartService(context: Context): Boolean {
        return try {
            val component = ComponentName(context, AutoClickAccessibilityService::class.java)
            val resolver = context.contentResolver
            val raw = Settings.Secure.getString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val others = raw.split(':').filter {
                it.isNotBlank() && ComponentName.unflattenFromString(it.trim()) != component
            }
            EventLogManager.log(
                EventLogManager.TAG_ACCESSIBILITY,
                "WATCHDOG: служба включена, но не подключена -> перезапуск (выкл/вкл)"
            )
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                others.joinToString(":")
            )
            delay(700L)
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (others + component.flattenToString()).joinToString(":")
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

            var waited = 0
            while (waited < 6000 && !AccessibilityServiceHolder.isConnected) {
                delay(300L)
                waited += 300
            }
            val ok = AccessibilityServiceHolder.isConnected
            EventLogManager.log(
                EventLogManager.TAG_ACCESSIBILITY,
                if (ok) "WATCHDOG: служба доступности восстановлена автоматически"
                else "WATCHDOG: перезапуск не помог, служба не подключилась",
                isError = !ok
            )
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            EventLogManager.log(
                EventLogManager.TAG_ACCESSIBILITY,
                "WATCHDOG: нет права WRITE_SECURE_SETTINGS: ${e.message}",
                isError = true
            )
            false
        } catch (t: Throwable) {
            EventLogManager.log(
                EventLogManager.TAG_ACCESSIBILITY,
                "WATCHDOG: ошибка перезапуска службы: ${t.message}",
                isError = true
            )
            false
        }
    }

    private fun showNotification(context: Context, enabledInSystem: Boolean) {
        if (notified) return
        notified = true

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Служба доступности",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Предупреждение, если служба доступности отключилась"
            }
            nm.createNotificationChannel(channel)
        }

        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settingsPi = PendingIntent.getActivity(
            context,
            11,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPi = PendingIntent.getActivity(
            context,
            12,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (enabledInSystem) {
            "Служба отвалилась. Откройте настройки: выключите и снова включите AutoClicker."
        } else {
            "Служба доступности выключена. Откройте настройки и включите AutoClicker."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stop)
            .setContentTitle("AutoClicker: служба доступности отключена")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(settingsPi)
            .addAction(R.drawable.ic_play, "Открыть настройки", settingsPi)
            .addAction(R.drawable.ic_play, "Открыть приложение", openAppPi)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (_: Throwable) {
        }
        EventLogManager.log(
            EventLogManager.TAG_ACCESSIBILITY,
            "WATCHDOG: показано уведомление об отключении службы доступности",
            isError = true
        )
    }

    private fun cancelNotification(context: Context) {
        if (!notified) return
        notified = false
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
        }
    }
}
