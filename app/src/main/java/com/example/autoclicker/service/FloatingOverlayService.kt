package com.example.autoclicker.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.Choreographer
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autoclicker.MainActivity
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.engine.CycleStatus
import com.example.autoclicker.engine.GestureExecutor
import com.example.autoclicker.engine.RunCoordinator
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.ui.theme.NeonTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Плавающий оверлей в стиле liquid-glass-neon:
 * - Свёрнутая кнопка: перетаскиваемая стеклянная таблетка с неоновым свечением.
 * - Компактная развёрнутая панель:
 *   - Заголовок «Автокликер» с «—» и «✕»
 *   - Статус (STATUS, NEXT, TIMER, последнее нажатие)
 *   - Кнопки START и STOP
 */
class FloatingOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var smartEngine: SmartEngine
    private val gestureExecutor = GestureExecutor()

    private var overlayParams: WindowManager.LayoutParams? = null
    private var rootContainer: FrameLayout? = null

    // Развёрнутая компактная панель
    private lateinit var expandedLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var nextActionText: TextView
    private lateinit var timerText: TextView
    private lateinit var lastTapText: TextView
    private lateinit var clockText: TextView
    private lateinit var startBtn: NeonGlassButton
    private lateinit var stopBtn: NeonGlassButton

    // Свёрнутая кнопка
    private lateinit var floatingBadgeButton: com.example.autoclicker.ui.views.BlackHoleBadgeView

    private var isMinimized = true

    // ===== Плавающая кнопка =====
    private var badgeCfg: BadgeCfg = BadgeCfg()
    private var badgeDragging = false
    private var floatRunning = false
    private var floatLastNanos = 0L
    private var floatT = 0f
    private var floatX = 0f
    private var floatY = 0f
    private val floatInitAngle = Math.random() * 2 * Math.PI
    private var floatVx = cos(floatInitAngle).toFloat()
    private var floatVy = sin(floatInitAngle).toFloat()
    private val floatCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!floatRunning) return
            val dt = if (floatLastNanos == 0L) 0f
            else ((frameTimeNanos - floatLastNanos) / 1_000_000_000f).coerceAtMost(0.05f)
            floatLastNanos = frameTimeNanos
            try {
                stepFloat(dt)
            } catch (_: Throwable) {
            }
            if (floatRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private var neonAnimator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settingsRepo = SettingsRepository.getInstance(applicationContext)
        smartEngine = SmartEngine.getInstance(applicationContext, gestureExecutor, settingsRepo)

        // Переключателя режимов в интерфейсе больше нет: всегда работает умный режим
        try {
            if (!settingsRepo.getLatestSettings().isSmartMode) settingsRepo.updateSmartMode(true)
        } catch (_: Exception) {
        }

        setupNeonAnimator()
        startAccessibilityWatcher()
        createOverlay()
        observeControllerState()

        EventLogManager.log(EventLogManager.TAG_OVERLAY, "FloatingOverlayService запущен (свёрнут)")
    }

    private var lastNeonUpdateMs = 0L

    private fun setupNeonAnimator() {
        neonAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 36_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                if (!isMinimized && expandedLayout.visibility == View.VISIBLE) {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastNeonUpdateMs >= 100L) { // не чаще 10 раз в секунду
                        lastNeonUpdateMs = now
                        NeonTheme.setHue(anim.animatedValue as Float)
                        expandedLayout.invalidate()
                    }
                }
            }
        }
    }

    private fun createOverlay() {
        val (sw, sh) = screenSize()
        val latest = settingsRepo.getLatestSettings()
        val side = dpToPx(badgeCfg.size)
        val initialX = latest.overlayX.coerceIn(0, (sw - side).coerceAtLeast(0))
        val initialY = latest.overlayY.coerceIn(0, (sh - side).coerceAtLeast(0))

        val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        rootContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        createMinimizedBadge()
        createExpandedPanel()

        rootContainer?.addView(floatingBadgeButton)
        rootContainer?.addView(expandedLayout)

        setMode(minimized = true)

        try {
            windowManager.addView(rootContainer, overlayParams)
        } catch (e: Exception) {
            EventLogManager.log(EventLogManager.TAG_OVERLAY, "ERROR addView overlay: ${e.message}", isError = true)
        }
    }

    /**
     * Следит за службой доступности: если она включена в настройках Android, но не подключена
     * к приложению (так бывает после аварийного закрытия процесса), раз в 3 минуты пишет в журнал
     * и показывает подсказку. Включить её программно Android не позволяет.
     */
    private fun startAccessibilityWatcher() {
        serviceScope.launch {
            var lastHintMs = 0L
            while (true) {
                delay(20_000L)
                try {
                    val enabled = AccessibilityStatus.isEnabledInSystem(applicationContext)
                    if (enabled && !AccessibilityServiceHolder.isConnected) {
                        val now = SystemClock.elapsedRealtime()
                        if (lastHintMs == 0L || now - lastHintMs >= 180_000L) {
                            lastHintMs = now
                            EventLogManager.log(
                                EventLogManager.TAG_ACCESSIBILITY,
                                "ACCESSIBILITY: служба включена в настройках, но не подключена (после сбоя). " +
                                    "Выключите и включите её заново: Настройки -> Специальные возможности",
                                isError = true
                            )
                            android.widget.Toast.makeText(
                                applicationContext,
                                "Служба доступности AutoClicker не подключена. " +
                                    "Выключите и включите её в Настройки -> Специальные возможности",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        lastHintMs = 0L
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun setMode(minimized: Boolean) {
        isMinimized = minimized
        if (minimized) {
            expandedLayout.visibility = View.GONE
            floatingBadgeButton.visibility = View.VISIBLE
            neonAnimator?.pause()
        } else {
            floatingBadgeButton.visibility = View.GONE
            expandedLayout.visibility = View.VISIBLE
            if (neonAnimator?.isPaused == true) neonAnimator?.resume() else neonAnimator?.start()
            rootContainer?.postDelayed({ clampRootToScreen() }, 120)
        }
        updateFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createMinimizedBadge() {
        floatingBadgeButton = com.example.autoclicker.ui.views.BlackHoleBadgeView(this).apply {
            val sizePx = dpToPx(badgeCfg.size)
            layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
            isRunning = false
            ringAlways = badgeCfg.ringAlways
            alpha = badgeCfg.opacity / 100f
            setBackgroundColor(Color.TRANSPARENT)

            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f
            var isClick = false

            setOnLongClickListener {
                showCloseAutoClickerDialog()
                true
            }

            setOnTouchListener { _, event ->
                val params = overlayParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        badgeDragging = true
                        updateFloat()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isClick = false
                        }
                        val (sw, sh) = screenSize()
                        val side = dpToPx(badgeCfg.size)
                        params.x = (initialX + dx).coerceIn(0, (sw - side).coerceAtLeast(0))
                        params.y = (initialY + dy).coerceIn(0, (sh - side).coerceAtLeast(0))
                        try {
                            windowManager.updateViewLayout(rootContainer, params)
                        } catch (_: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        badgeDragging = false
                        if (event.action == MotionEvent.ACTION_UP && isClick) {
                            setMode(minimized = false)
                        } else {
                            settingsRepo.updateOverlayPosition(params.x, params.y)
                            updateFloat()
                        }
                        true
                    }
                    else -> false
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createExpandedPanel() {
        val (sw, sh) = screenSize()
        val panelWidth = (sw * 0.82f).toInt().coerceIn(dpToPx(250), dpToPx(320))

        expandedLayout = NeonPanel(this, 24f).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(14))
        }

        // 1. Шапка панели: «Автокликер» с «—» и «✕»
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(6))
        }

        val titleText = TextView(this).apply {
            text = "Автокликер"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minimizeBtn = NeonGlassButton(this).apply {
            text = "—"
            textSize = 14f
            setTextColor(Color.WHITE)
            cornerDp = 12f
            hueOffset = 30f
            val btnSize = dpToPx(32)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setPadding(0, 0, 0, 0)
            setOnClickListener { setMode(minimized = true) }
        }

        val closeBtn = NeonGlassButton(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(Color.WHITE)
            cornerDp = 12f
            hueOffset = -90f
            val btnSize = dpToPx(32)
            val lp = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = dpToPx(6)
            }
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            setOnClickListener { showCloseAutoClickerDialog() }
        }

        headerLayout.addView(titleText)
        headerLayout.addView(minimizeBtn)
        headerLayout.addView(closeBtn)
        expandedLayout.addView(headerLayout)

        // Перетаскивание за заголовок
        var headerTouchStartX = 0f
        var headerTouchStartY = 0f
        var headerInitX = 0
        var headerInitY = 0

        headerLayout.setOnTouchListener { _, event ->
            val params = overlayParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    headerTouchStartX = event.rawX
                    headerTouchStartY = event.rawY
                    headerInitX = params.x
                    headerInitY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - headerTouchStartX).toInt()
                    val dy = (event.rawY - headerTouchStartY).toInt()
                    val (w, h) = screenSize()
                    val panelW = expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280)
                    val panelH = expandedLayout.height.takeIf { it > 0 } ?: dpToPx(220)
                    params.x = (headerInitX + dx).coerceIn(0, (w - panelW).coerceAtLeast(0))
                    params.y = (headerInitY + dy).coerceIn(0, (h - panelH).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(rootContainer, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    settingsRepo.updateOverlayPosition(params.x, params.y)
                    true
                }
                else -> false
            }
        }

        // 2. Блок статуса
        statusText = TextView(this).apply {
            text = "STATUS: STOPPED"
            setTextColor(Color.parseColor("#FF5252"))
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }
        expandedLayout.addView(statusText)

        nextActionText = TextView(this).apply {
            text = "NEXT: Готов к запуску"
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 11f
            setPadding(0, 0, 0, dpToPx(2))
        }
        expandedLayout.addView(nextActionText)

        timerText = TextView(this).apply {
            text = "TIMER: --"
            setTextColor(Color.parseColor("#FFD54F"))
            textSize = 11f
            setPadding(0, 0, 0, dpToPx(2))
        }
        expandedLayout.addView(timerText)

        lastTapText = TextView(this).apply {
            text = "Последнее нажатие: --"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 10f
            setPadding(0, 0, 0, dpToPx(6))
        }
        expandedLayout.addView(lastTapText)

        // 3. Ряд кнопок START / STOP
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(8))
        }

        startBtn = NeonGlassButton(this).apply {
            text = "START"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 16f
            val lp = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply { marginEnd = dpToPx(6) }
            layoutParams = lp
            setOnClickListener {
                val res = RunCoordinator.start(applicationContext)
                if (res.anyStarted) {
                    setMode(minimized = true)
                }
            }
        }

        stopBtn = NeonGlassButton(this).apply {
            text = "STOP"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            cornerDp = 16f
            hueOffset = -110f
            isEnabled = false
            alpha = 0.5f
            val lp = LinearLayout.LayoutParams(0, dpToPx(44), 1f).apply { marginStart = dpToPx(6) }
            layoutParams = lp
            setOnClickListener {
                RunCoordinator.stopAll(applicationContext, "кнопка Стоп")
            }
        }

        controlRow.addView(startBtn)
        controlRow.addView(stopBtn)
        expandedLayout.addView(controlRow)
    }

    private fun showCloseAutoClickerDialog() {
        try {
            // Из Service нельзя показывать обычный диалог: нужен тип окна «поверх приложений»,
            // иначе BadTokenException и аварийное закрытие процесса (после чего Android
            // перестаёт подключать службу доступности).
            val themed = android.view.ContextThemeWrapper(
                this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            )
            val dialog = AlertDialog.Builder(themed)
                .setTitle("Закрыть AutoClicker")
                .setPositiveButton("Закрыть") { _, _ ->
                    shutdownAllWindowsAndServices()
                    AutoClickForegroundService.stop(applicationContext)
                }
                .setNegativeButton("Отмена", null)
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "Диалог закрытия не показан (${e.message}), закрываю без вопроса",
                isError = true
            )
            onCloseService()
        }
    }

    private fun onCloseService() {
        shutdownAllWindowsAndServices()
        AutoClickForegroundService.stop(applicationContext)
    }

    private fun updateFloat() {
        val root = rootContainer
        val should = isMinimized && badgeCfg.float && !badgeDragging &&
                root != null && root.isAttachedToWindow && overlayParams != null
        if (should && !floatRunning) {
            overlayParams?.let {
                floatX = it.x.toFloat()
                floatY = it.y.toFloat()
            }
            floatRunning = true
            floatLastNanos = 0L
            Choreographer.getInstance().postFrameCallback(floatCallback)
        } else if (!should && floatRunning) {
            stopFloat()
        }
    }

    private fun stopFloat() {
        floatRunning = false
        floatLastNanos = 0L
    }

    private fun stepFloat(dt: Float) {
        val params = overlayParams ?: return
        val root = rootContainer ?: return
        val (sw, sh) = screenSize()
        val side = dpToPx(badgeCfg.size).toFloat()
        val maxX = (sw - side).coerceAtLeast(0f)
        val maxY = (sh - side).coerceAtLeast(0f)

        floatT += dt
        val speedPxPerSec = dpToPx(badgeCfg.speed).toFloat()
        floatX += floatVx * speedPxPerSec * dt
        floatY += floatVy * speedPxPerSec * dt

        if (floatX <= 0f) {
            floatX = 0f
            floatVx = abs(floatVx)
        } else if (floatX >= maxX) {
            floatX = maxX
            floatVx = -abs(floatVx)
        }
        if (floatY <= 0f) {
            floatY = 0f
            floatVy = abs(floatVy)
        } else if (floatY >= maxY) {
            floatY = maxY
            floatVy = -abs(floatVy)
        }

        params.x = floatX.toInt()
        params.y = floatY.toInt()
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {}
    }

    private fun clampRootToScreen() {
        val params = overlayParams ?: return
        val root = rootContainer ?: return
        val (sw, sh) = screenSize()
        val currentW = if (isMinimized) dpToPx(badgeCfg.size) else (expandedLayout.width.takeIf { it > 0 } ?: dpToPx(280))
        val currentH = if (isMinimized) dpToPx(badgeCfg.size) else (expandedLayout.height.takeIf { it > 0 } ?: dpToPx(220))
        val clampedX = params.x.coerceIn(0, (sw - currentW).coerceAtLeast(0))
        val clampedY = params.y.coerceIn(0, (sh - currentH).coerceAtLeast(0))
        if (clampedX != params.x || clampedY != params.y) {
            params.x = clampedX
            params.y = clampedY
            try {
                windowManager.updateViewLayout(root, params)
            } catch (_: Exception) {}
        }
    }

    private fun observeControllerState() {
        serviceScope.launch {
            smartEngine.status.collect { status ->
                val mode = if (status != CycleStatus.STOPPED) "SMART" else "NORMAL"
                val statusString = when (status) {
                    CycleStatus.STOPPED -> "STATUS: STOPPED"
                    CycleStatus.RUNNING -> when (mode) {
                        "SMART" -> "STATUS: SMART RUNNING"
                        else -> "STATUS: RUNNING"
                    }
                    CycleStatus.WAITING_CYCLE -> "STATUS: WAITING"
                }
                statusText.text = statusString
                statusText.setTextColor(
                    when (status) {
                        CycleStatus.RUNNING -> Color.parseColor("#00E676")
                        CycleStatus.WAITING_CYCLE -> Color.parseColor("#FFD54F")
                        CycleStatus.STOPPED -> Color.parseColor("#FF5252")
                    }
                )

                if (status == CycleStatus.RUNNING || status == CycleStatus.WAITING_CYCLE) {
                    startBtn.text = "▶ ЗАПУЩЕНО"
                    startBtn.isOn = true
                    startBtn.isEnabled = false
                    startBtn.alpha = 1.0f

                    stopBtn.isEnabled = true
                    stopBtn.alpha = 1.0f
                    stopBtn.isOn = true

                    floatingBadgeButton.isRunning = true
                } else {
                    startBtn.text = "START"
                    startBtn.isOn = false
                    startBtn.isEnabled = true
                    startBtn.alpha = 1.0f

                    stopBtn.isEnabled = false
                    stopBtn.alpha = 0.5f
                    stopBtn.isOn = false

                    floatingBadgeButton.isRunning = false
                }
            }
        }

        serviceScope.launch {
            smartEngine.nextAction.collect { action ->
                nextActionText.text = "NEXT: $action"
            }
        }

        serviceScope.launch {
            combine(
                smartEngine.countdownText,
                smartEngine.status,
                smartEngine.currentAction
            ) { sCd, sStatus, sAction ->
                if (sStatus != CycleStatus.STOPPED) {
                    if (sCd.isNotEmpty()) "TIMER: $sCd" else "SMART: $sAction"
                } else {
                    "TIMER: --"
                }
            }.collect { cdText ->
                timerText.text = cdText
            }
        }

        serviceScope.launch {
            smartEngine.lastAction.collect { act ->
                lastTapText.text = "Последнее: $act"
            }
        }
    }

    private fun shutdownAllWindowsAndServices() {
        stopFloat()
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        rootContainer = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopFloat()
        neonAnimator?.cancel()
        serviceScope.cancel()
        rootContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE_OVERLAY) {
            shutdownAllWindowsAndServices()
            return START_NOT_STICKY
        }
        try {
            AutoClickForegroundService.start(applicationContext)
        } catch (_: Exception) {
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            FloatingOverlayService.start(this)
            AutoClickForegroundService.start(this)
        } catch (_: Exception) {
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.example.autoclicker.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.example.autoclicker.action.HIDE_OVERLAY"

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }
}
