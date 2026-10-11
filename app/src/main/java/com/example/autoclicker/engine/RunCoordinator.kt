package com.example.autoclicker.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
import com.example.autoclicker.service.AccessibilityStatus
import com.example.autoclicker.service.AutoClickForegroundService

/**
 * Единая точка запуска и остановки умного режима.
 */
object RunCoordinator {

    private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoClicker::RunLock").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RUN: wake lock включён")
        } catch (t: Throwable) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "RUN: wake lock не включён: ${t.message}",
                isError = true
            )
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "RUN: wake lock выключен")
            }
        } catch (_: Throwable) {
        }
        wakeLock = null
    }

    data class StartResult(
        val started: List<String>,
        val problems: List<String>
    ) {
        val anyStarted: Boolean get() = started.isNotEmpty()
    }

    fun start(context: Context): StartResult {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository.getInstance(app)
        val gestureExecutor = GestureExecutor()
        val smart = SmartEngine.getInstance(app, gestureExecutor, settingsRepo)

        settingsRepo.setPaused(false)

        val s = settingsRepo.getLatestSettings()

        // Проверка оптимизации батареи раз в сутки (B.8)
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && pm != null) {
                val isIgnoring = pm.isIgnoringBatteryOptimizations(app.packageName)
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "BATTERY: оптимизация батареи отключена: $isIgnoring"
                )
                if (!isIgnoring) {
                    val prefs = app.getSharedPreferences("app_battery_hint", Context.MODE_PRIVATE)
                    val lastHint = prefs.getLong("last_battery_hint_time", 0L)
                    val now = System.currentTimeMillis()
                    if (now - lastHint > 24 * 60 * 60 * 1000L) {
                        prefs.edit().putLong("last_battery_hint_time", now).apply()
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(
                                app,
                                "Совет: для стабильной работы отключите оптимизацию батареи для AutoClicker",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "BATTERY: ошибка проверки оптимизации: ${t.message}"
            )
        }
        val started = mutableListOf<String>()
        val problems = mutableListOf<String>()

        if (s.isSmartMode) {
            if (smart.start()) {
                started.add("Умный режим")
            } else {
                problems.add("Умный режим не запущен: проверьте сервис доступности")
            }
        } else {
            problems.add("Умный режим выключен в настройках")
        }

        if (started.isNotEmpty()) {
            _isRunning.value = true
            settingsRepo.setRunActive(true)
            try {
                AutoClickForegroundService.start(app)
            } catch (t: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "RUN: ForegroundService не запущен: ${t.message}",
                    isError = true
                )
            }
            acquireWakeLock(app)
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "START: из плавающего окна / координатора")
        }

        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "START: запущено [${started.joinToString(", ")}]" +
                if (problems.isEmpty()) "" else ", проблемы: ${problems.joinToString("; ")}",
            isError = started.isEmpty()
        )

        if (problems.isNotEmpty()) {
            val text = problems.joinToString("\n")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            }
        }
        return StartResult(started, problems)
    }

    /** Пауза: движок останавливается, сервисы и оверлей остаются. */
    fun pause(context: Context) {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository.getInstance(app)
        val gestureExecutor = GestureExecutor()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "STOP/ПАУЗА: из плавающего окна")
        SmartEngine.getInstance(app, gestureExecutor, settingsRepo).stop("пауза")
        _isRunning.value = false
        settingsRepo.setPaused(true)
        settingsRepo.setRunActive(false)
        releaseWakeLock()
    }

    /**
     * Полная остановка всех режимов (крестик).
     */
    fun stopAll(context: Context, reason: String = "кнопка Стоп", clearActiveFlag: Boolean = true) {
        val app = context.applicationContext
        val settingsRepo = SettingsRepository.getInstance(app)
        val gestureExecutor = GestureExecutor()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "STOP: остановка режимов, причина: $reason")
        SmartEngine.getInstance(app, gestureExecutor, settingsRepo).stop(reason)
        _isRunning.value = false
        releaseWakeLock()
        if (clearActiveFlag) {
            settingsRepo.setRunActive(false)
            settingsRepo.setPaused(false)
        }
    }
}
