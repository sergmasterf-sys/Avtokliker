package com.example.autoclicker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.autoclicker.MainActivity
import com.example.autoclicker.R
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.RunCoordinator
import com.example.autoclicker.engine.SmartEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Foreground Service для устойчивой работы кликера в фоновом режиме.
 */
class AutoClickForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var smartEngine: SmartEngine
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        settingsRepo = SettingsRepository.getInstance(applicationContext)
        val gestureExecutor = GestureExecutor()
        smartEngine = SmartEngine.getInstance(applicationContext, gestureExecutor, settingsRepo)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        createNotificationChannel()
        startForegroundWithNotification("Готов к работе")
        observeStatus()
        AccessibilityWatchdog.start(applicationContext, serviceScope)
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "ForegroundService создан")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "SERVICE: onStartCommand " + if (intent == null) "intent=null (перезапуск системой)" else "action=${intent.action}"
        )
        if (intent == null) {
            if (settingsRepo.isRunActive()) {
                serviceScope.launch {
                    var connected = AccessibilityServiceHolder.isConnected
                    var waited = 0
                    while (!connected && waited < 5000 && isActive) {
                        delay(500)
                        waited += 500
                        connected = AccessibilityServiceHolder.isConnected
                    }
                    if (settingsRepo.isRunActive()) {
                        RunCoordinator.start(applicationContext)
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SERVICE: режимы восстановлены после перезапуска системой"
                        )
                    }
                }
            } else {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SERVICE: сервис перезапущен системой, режимы остановлены, нажмите START",
                    isError = true
                )
            }
        }
        when (intent?.action) {
            ACTION_START -> {
                RunCoordinator.start(applicationContext)
            }
            ACTION_STOP -> {
                RunCoordinator.stopAll(applicationContext, "кнопка Стоп (уведомление)", clearActiveFlag = true)
            }
            ACTION_SHUTDOWN -> {
                RunCoordinator.stopAll(applicationContext, "закрытие приложения", clearActiveFlag = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "SERVICE: приложение убрано из списка недавних (задача удалена)"
        )
        try {
            AutoClickForegroundService.start(this)
            if (android.provider.Settings.canDrawOverlays(this)) {
                FloatingOverlayService.start(this)
            }
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onLowMemory() {
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SERVICE: система сообщает о нехватке памяти", isError = true)
        super.onLowMemory()
    }

    override fun onTrimMemory(level: Int) {
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SERVICE: onTrimMemory уровень $level")
        super.onTrimMemory(level)
    }

    private fun observeStatus() {
        serviceScope.launch {
            smartEngine.status.collect {
                updateNotificationBasedOnState()
            }
        }
        serviceScope.launch {
            smartEngine.currentAction.collect {
                updateNotificationBasedOnState()
            }
        }
    }

    private fun updateNotificationBasedOnState() {
        val sStatus = smartEngine.status.value
        val isSmartActive = sStatus != CycleStatus.STOPPED
        val title = if (isSmartActive) {
            "AutoClicker: SMART"
        } else {
            "AutoClicker: ГОТОВ"
        }

        val text = if (isSmartActive) {
            smartEngine.currentAction.value
        } else {
            "Нажмите СТАРТ для запуска"
        }
        updateNotification(text, title)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText, "IDLE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String, status: String) {
        val notification = buildNotification(contentText, status)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(contentText: String, status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(this, AutoClickForegroundService::class.java).apply {
            action = ACTION_START
        }
        val startPendingIntent = PendingIntent.getService(
            this,
            1,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AutoClickForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoClicker: $status")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_play)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_play, getString(R.string.action_start), startPendingIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        RunCoordinator.stopAll(
            applicationContext,
            "служба AutoClickForegroundService уничтожена системой или приложением",
            clearActiveFlag = false
        )
        serviceScope.cancel()
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "ForegroundService остановлен")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "autoclicker_foreground_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.autoclicker.ACTION_START"
        const val ACTION_STOP = "com.example.autoclicker.ACTION_STOP"
        const val ACTION_SHUTDOWN = "com.example.autoclicker.ACTION_SHUTDOWN"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            try {
                val intent = Intent(context, AutoClickForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: Не удалось запустить ForegroundService: ${e.message}",
                    isError = true
                )
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AutoClickForegroundService::class.java).apply {
                    action = ACTION_SHUTDOWN
                }
                context.startService(intent)
            } catch (e: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: Ошибка остановки ForegroundService: ${e.message}",
                    isError = true
                )
            }
        }
    }
}
