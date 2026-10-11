package com.example.autoclicker.engine

import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.autoclicker.R
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.service.AccessibilityServiceHolder
import com.example.autoclicker.service.AccessibilityStatus
import com.example.autoclicker.service.AutoClickForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CheckReport(
    val message: String,
    val lines: List<String> = emptyList()
)

class DownsampledRoi(
    val roiX0: Int,
    val roiY0: Int,
    val downW: Int,
    val downH: Int,
    val gray: FloatArray,
    val sumI: DoubleArray,
    val sumI2: DoubleArray,
    val intW: Int,
    val factor: Int = 2
)

/**
 * Умный режим (Smart Mode) для автоматизации PUBG Mobile:
 * 1. В лобби находит кнопку «НАЧАТЬ» (по цвету, форме и шаблону) и нажимает на неё.
 * 2. Ждет начала катки и перехода в матч.
 * 3. Во время/после катки находит кнопку «ПРОДОЛЖИТЬ» (MVP, синяя, желтая) и нажимает на неё.
 * 4. Возвращается в лобби и повторяет цикл.
 */
class SmartEngine private constructor(
    private val context: Context,
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository
) {
    class SimpleLruCache<K, V>(
        private val maxSize: Int,
        private val onEvict: ((K, V) -> Unit)? = null
    ) {
        private val map = object : LinkedHashMap<K, V>(maxSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                val should = size > maxSize
                if (should && eldest != null) {
                    onEvict?.invoke(eldest.key, eldest.value)
                }
                return should
            }
        }

        @Synchronized
        operator fun get(key: K): V? = map[key]

        @Synchronized
        fun put(key: K, value: V): V? = map.put(key, value)

        @Synchronized
        operator fun set(key: K, value: V) { map.put(key, value) }

        @Synchronized
        fun clear() {
            if (onEvict != null) {
                for ((k, v) in map) {
                    onEvict.invoke(k, v)
                }
            }
            map.clear()
        }

        @Synchronized
        fun size(): Int = map.size

        @Synchronized
        fun snapshot(): Map<K, V> = HashMap(map)
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        val trace = throwable.stackTrace.take(3).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "SMART: Необработанное исключение: ${throwable.javaClass.simpleName}: ${throwable.message} @ $trace",
            isError = true
        )
    }
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)
    private var engineJob: Job? = null
    private val engineMutex = Mutex()

    init {
        com.example.autoclicker.data.SmartConfigManager.getInstance(context).onConfigChangedListener = {
            if (isRunning) {
                coroutineScope.launch {
                    stopInternal("смена настроек конфига")
                    start()
                }
            }
        }
    }

    private val _status = MutableStateFlow(CycleStatus.STOPPED)
    val status: StateFlow<CycleStatus> = _status.asStateFlow()

    private val _currentAction = MutableStateFlow("Остановлен")
    val currentAction: StateFlow<String> = _currentAction.asStateFlow()

    private val _lastAction = MutableStateFlow("—")
    val lastAction: StateFlow<String> = _lastAction.asStateFlow()

    private val _nextAction = MutableStateFlow("Готов к запуску")
    val nextAction: StateFlow<String> = _nextAction.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0)
    val remainingSeconds: StateFlow<Int> = _remainingSeconds.asStateFlow()

    private val _countdownText = MutableStateFlow("")
    val countdownText: StateFlow<String> = _countdownText.asStateFlow()

    private val _cycleNumber = MutableStateFlow(0)
    val cycleNumber: StateFlow<Int> = _cycleNumber.asStateFlow()

    val isRunning: Boolean
        get() = _status.value != CycleStatus.STOPPED && engineJob?.isActive == true

    // Диагностика: причина завершения цикла и счётчики для heartbeat
    @Volatile
    private var endReason: String? = null
    private var scanCount = 0L
    private var nullShotCount = 0L
    private var matchCount = 0L
    private val consecutiveNoMatchCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var lastTapError: String? = null
    private var scanMissStreak = 0

    private var runStartTime = 0L
    private var firstTapDone = false
    private val autoRestartTimestamps = java.util.concurrent.CopyOnWriteArrayList<Long>()

    /** Кэш координат: после первого нахождения кнопки тапаем сюда без долгого поиска */
    private data class CachedCoord(
        val x: Float,
        val y: Float,
        val screenW: Int,
        val screenH: Int,
        var useCount: Int = 0,
        var lastMs: Long = System.currentTimeMillis()
    )
    private val coordCache = java.util.concurrent.ConcurrentHashMap<String, CachedCoord>()
    private var lastCacheScreenW = 0
    private var lastCacheScreenH = 0
    private var lastTapAtMs = 0L
    private var scanIndex = 0L
    private val wideSearchTick = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val variantBuildCache = SimpleLruCache<String, TemplateVariant>(40)
    private var lastYellowRejectLogMs = 0L


    private fun callerInfo(): String {
        return try {
            Throwable().stackTrace
                .filter { it.className.startsWith("com.example.autoclicker") && !it.className.contains("SmartEngine") }
                .take(2)
                .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        } catch (t: Throwable) {
            ""
        }
    }

    enum class SmartState {
        LOBBY_SEARCH_START,       // В лобби, поиск кнопки «НАЧАТЬ»
        WAITING_MATCH_START,      // «НАЧАТЬ» нажата, ожидание подбора/загрузки матча
        IN_MATCH_SEARCH_CONTINUE, // Матч идет, поиск кнопки «ПРОДОЛЖИТЬ» после катки
        POST_MATCH_TAP_CONTINUE   // Нажатие серии «ПРОДОЛЖИТЬ» (MVP -> статистика -> лобби)
    }

    // Кеш оригинальных шаблонов из assets
    private var rawTemplates: List<RawTemplate>? = null

    // Кеш масштабированных и подготовленных для конкретного разрешения шаблонов (5 масштабов)
    private var cachedScreenWidth = 0
    private var cachedScreenHeight = 0
    private var precomputedTemplates: List<PrecomputedMultiScaleTemplate>? = null

    data class RawTemplate(
        val name: String,
        val bitmap: Bitmap,
        val isContinue: Boolean
    )

    data class TemplateVariant(
        val scaleMultiplier: Float,
        val tDownW: Int,
        val tDownH: Int,
        val tDiff: FloatArray,
        val sigmaT: Double
    )

    data class PrecomputedMultiScaleTemplate(
        val name: String,
        val isContinue: Boolean,
        val variants: List<TemplateVariant>
    )

    data class FoundMatch(
        val name: String,
        val clickX: Float,
        val clickY: Float,
        val score: Float,
        val isContinue: Boolean,
        val afterDelayMs: Long = 50L,
        val rule: com.example.autoclicker.data.SmartRule? = null,
        val scale: Float = 0f,
        val source: String = "изображение"
    )

    data class RuleTemplate(
        val rule: com.example.autoclicker.data.SmartRule,
        val rawBitmap: Bitmap,
        val variants: List<TemplateVariant>
    )

    private val croppedBitmapsCache = SimpleLruCache<String, Bitmap>(40) { _, bmp ->
        if (!bmp.isRecycled) bmp.recycle()
    }
    private var cachedRuleTemplates: List<RuleTemplate>? = null
    private var cachedRulesHash: Int = 0

    fun clearCaches(): Long {
        var estimatedBytes = 0L
        synchronized(this) {
            for ((_, bmp) in croppedBitmapsCache.snapshot()) {
                if (!bmp.isRecycled) {
                    estimatedBytes += bmp.byteCount
                    bmp.recycle()
                }
            }
            croppedBitmapsCache.clear()
            variantBuildCache.clear()
            coordCache.clear()
        }
        return estimatedBytes
    }

    /**
     * Потокобезопасная очистка кэшей шаблонов и координат без изменения bitmap активного цикла.
     */
    fun clearTemplateAndCoordinateCaches() {
        synchronized(this) {
            variantBuildCache.clear()
            coordCache.clear()
        }
        CustomRuleSearch.clearTemplateCaches()
    }

    data class CandidateScanResult(
        val bestStart: FoundMatch?,
        val bestContinue: FoundMatch?,
        val totalCandidates: Int,
        val debugSummary: String
    )

    fun start(): Boolean {
        CustomRuleSearch.clearCaches()
        clearScreensDir()
        if (!AccessibilityServiceHolder.isConnected && !AccessibilityStatus.isEnabledInSystem(context)) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "ERROR: AccessibilityService unavailable. Запуск Smart Mode невозможен.",
                isError = true
            )
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "Включите сервис доступности AutoClicker в настройках Android",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }

        coroutineScope.launch {
            engineMutex.withLock {
                if (engineJob?.isActive == true) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "Smart Mode уже выполняется. Повторный START проигнорирован."
                    )
                    return@withLock
                }

                endReason = null
                scanCount = 0L
                nullShotCount = 0L
                matchCount = 0L
                runStartTime = System.currentTimeMillis()
                firstTapDone = false
                _cycleNumber.value = 1
                _status.value = CycleStatus.RUNNING
                _lastAction.value = "START (Smart)"
                _currentAction.value = "Умный режим: запуск..."
                _nextAction.value = "Поиск кнопок на экране"
                _countdownText.value = ""
                _remainingSeconds.value = 0

                // Очистка старых логов при каждом запуске умного режима
                com.example.autoclicker.data.LogFileManager.cleanupOld()

                val configManager = com.example.autoclicker.data.SmartConfigManager.getInstance(context)
                val effectiveConfig = configManager.buildEffectiveRules()

                val (realW, realH) = getRealScreenSize()
                val H = minOf(realW, realH).toFloat()
                val prepStart = System.currentTimeMillis()
                val prepped = ensureRuleTemplates(effectiveConfig.rules, realW, realH)
                val multis = floatArrayOf(0.55f, 0.65f, 0.75f, 0.85f, 0.93f, 1.0f, 1.07f, 1.15f, 1.30f, 1.45f, 1.65f, 1.85f, 2.10f)
                for (rt in prepped) {
                    for (m in multis) {
                        getOrBuildVariant(rt, m, H)
                    }
                }
                val prepMs = System.currentTimeMillis() - prepStart
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: шаблоны подготовлены заранее за ${prepMs}мс (вариантов масштаба: ${multis.size})"
                )

                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: Умный режим запущен. Разрешение экрана: ${realW}x${realH}"
                )
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: режим ${effectiveConfig.mode.name}, правил ${effectiveConfig.rules.size} (встроенных ${effectiveConfig.builtinCount}, своих ${effectiveConfig.customCount})"
                )
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: сервис доступности подключён, API=${Build.VERSION.SDK_INT}"
                )

                val newJob = launch {
                    val currentJob = coroutineContext[Job]
                    try {
                        runLoop(effectiveConfig)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        val projectTrace = t.stackTrace.firstOrNull { it.className.startsWith("com.example.autoclicker") }
                            ?: t.stackTrace.firstOrNull()
                        val traceStr = projectTrace?.toString() ?: "unknown"
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: ОШИБКА в цикле: ${t.javaClass.simpleName}: ${t.message ?: ""} @ $traceStr",
                            isError = true
                        )
                        endReason = "необработанная ошибка: ${t.javaClass.simpleName}"
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: остановка, причина: ошибки"
                        )
                    } finally {
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: цикл завершён, причина: ${endReason ?: "внешняя отмена корутины"}"
                        )
                        engineMutex.withLock {
                            if (engineJob === currentJob) {
                                engineJob = null
                                resetState()
                                clearScreensDir()
                            }
                        }
                    }
                }
                engineJob = newJob
            }
        }
        return true
    }

    suspend fun stopInternal(reason: String = "кнопка Стоп", caller: String = "") {
        val jobToCancel: Job?
        engineMutex.withLock {
            jobToCancel = engineJob
            engineJob = null
            endReason = reason
            resetState()
            clearScreensDir()
            _lastAction.value = "STOP"
            val who = if (caller.isNotEmpty()) " (вызвал: $caller)" else ""
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "SMART: остановка, причина: $reason$who"
            )
        }
        jobToCancel?.cancelAndJoin()
    }

    fun stop(reason: String = "кнопка Стоп") {
        val caller = callerInfo()
        coroutineScope.launch {
            stopInternal(reason, caller)
        }
    }

    private fun resetState() {
        consecutiveNoMatchCount.clear()
        wideSearchTick.clear()
        coordCache.clear()
        _status.value = CycleStatus.STOPPED
        _currentAction.value = "Остановлен"
        _nextAction.value = "Готов к запуску"
        _remainingSeconds.value = 0
        _countdownText.value = ""
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                return Pair(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val display = wm.defaultDisplay
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                return Pair(size.x, size.y)
            }
        }
        val dm = context.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private suspend fun runLoop(effectiveConfig: com.example.autoclicker.data.EffectiveConfig) {
        val (realW, realH) = getRealScreenSize()
        if (lastCacheScreenW != 0 && (lastCacheScreenW != realW || lastCacheScreenH != realH)) {
            coordCache.clear()
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: кэш сброшен (экран ${realW}x${realH})")
        }
        lastCacheScreenW = realW
        lastCacheScreenH = realH
        val ruleTemplates = ensureRuleTemplates(effectiveConfig.rules, realW, realH)

        var lastMatchTime = System.currentTimeMillis()
        val idleTimeoutMs = effectiveConfig.idleTimeoutMin * 60 * 1000L
        var lastIdleWarnTime = System.currentTimeMillis()
        var lastHeartbeatTime = System.currentTimeMillis()
        var serviceLostSince = 0L
        var consecutiveErrors = 0
        var lastLoggedSize = ""

        val hasBuiltin = effectiveConfig.rules.any { it.builtin }
        val sortedRules = effectiveConfig.rules.sortedBy { it.priority }
        fun ruleLabel(rule: com.example.autoclicker.data.SmartRule): String {
            val idx = sortedRules.indexOfFirst { it.id == rule.id }.let { if (it >= 0) it + 1 else rule.priority }
            return "Правило $idx (${rule.id})"
        }

        var lastFastPathMatchTime = System.currentTimeMillis()
        var lastFullSearchTime = 0L
        var lastNoMatchLogTime = 0L

        while (true) {
            try {
                coroutineContext.ensureActive()
                val loopStart = System.currentTimeMillis()

                val service = AccessibilityServiceHolder.service.value
                if (service == null) {
                    val nowLost = System.currentTimeMillis()
                    if (serviceLostSince == 0L) {
                        serviceLostSince = nowLost
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: сервис доступности отключён, жду переподключения (до 5 мин)",
                            isError = true
                        )
                    }
                    if (nowLost - serviceLostSince > 5 * 60 * 1000L) {
                        endReason = "сервис доступности недоступен более 5 минут"
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: остановка, причина: $endReason"
                        )
                        resetState()
                        clearScreensDir()
                        return
                    }
                    _currentAction.value = "Ожидание сервиса доступности..."
                    delay(2000L)
                    continue
                }
                if (serviceLostSince != 0L) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: сервис доступности снова подключён (простой ${(System.currentTimeMillis() - serviceLostSince) / 1000} с)"
                    )
                    serviceLostSince = 0L
                }

                _currentAction.value = "Поиск правил (${effectiveConfig.rules.size} шт.)..."

                scanCount++
                scanIndex++
                val shotStart = System.currentTimeMillis()
                val minInterval = if (scanIndex == 1L) 50L else effectiveConfig.scanIntervalMs.coerceAtLeast(350L)
                val screenshot = service.takeScreenshotBitmap(minIntervalMs = minInterval)
                val shotMs = System.currentTimeMillis() - shotStart
                if (screenshot == null) nullShotCount++
                val searchStart = System.currentTimeMillis()
                if (screenshot != null && !screenshot.isRecycled) {
                    try {
                        val sizeStr = "${screenshot.width}x${screenshot.height}"
                        if (sizeStr != lastLoggedSize) {
                            lastLoggedSize = sizeStr
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: снимок ${sizeStr} (экран ${realW}x${realH})"
                            )
                        }

                        val settings = settingsRepository.getLatestSettings()
                        if (settings.isDebugScreenshots) {
                            saveDebugScreenshot(screenshot)
                        }

                        // Поиск кандидатов в порядке приоритета
                        var match: FoundMatch? = null
                        val customDir = effectiveConfig.rules.firstOrNull { !it.builtin }?.configDir
                        val preparedCustomRules = CustomRuleSearch.prepareCustomRules(effectiveConfig.rules, customDir)

                        // 1. БЫСТРЫЙ ПУТЬ ПОСЛЕ КАЖДОГО СНИМКА (A.3):
                        // Проверка в малом окне вокруг сохранённого центра для выученных правил в порядке priority
                        for (rule in sortedRules) {
                            val configName = if (rule.builtin) "builtin" else (rule.configDir?.name ?: "builtin")
                            val learned = com.example.autoclicker.data.SmartLearnedStore.get(realW, realH, rule.id, configName)
                            if (learned != null) {
                                if (rule.builtin) {
                                    val rt = ruleTemplates.firstOrNull { it.rule.id == rule.id }
                                    if (rt != null) {
                                        val eval = evalBuiltinFastMatch(screenshot, rt, learned)
                                        if (eval != null) {
                                            consecutiveNoMatchCount[rule.id] = 0
                                            val isContinue = rule.id.contains("continue", ignoreCase = true)
                                            val (clickX, clickY) = if (rule.tapTarget == "fixed" && rule.tapX != null && rule.tapY != null) {
                                                Pair(rule.tapX * screenshot.width, rule.tapY * screenshot.height)
                                            } else {
                                                Pair(eval.clickX, eval.clickY)
                                            }
                                            match = FoundMatch(
                                                name = rule.id,
                                                clickX = clickX,
                                                clickY = clickY,
                                                score = eval.bestScore,
                                                isContinue = isContinue,
                                                afterDelayMs = rule.afterDelayMs,
                                                rule = rule,
                                                scale = eval.bestScale,
                                                source = "быстрый путь"
                                            )
                                            com.example.autoclicker.data.SmartLearnedStore.put(
                                                realW, realH, rule.id, learned.multiplier,
                                                learned.xFrac, learned.yFrac, eval.bestScore,
                                                hitCount = learned.hitCount + 1,
                                                configName = configName
                                            )
                                            lastFastPathMatchTime = System.currentTimeMillis()
                                            break
                                        }
                                    }
                                } else {
                                    val customEval = CustomRuleSearch.findCustomMatchFast(screenshot, rule, preparedCustomRules, learned)
                                    if (customEval != null) {
                                        consecutiveNoMatchCount[rule.id] = 0
                                        val isContinue = rule.id.contains("continue", ignoreCase = true)
                                        val (clickX, clickY) = if (rule.tapTarget == "fixed" && rule.tapX != null && rule.tapY != null) {
                                            Pair(rule.tapX * screenshot.width, rule.tapY * screenshot.height)
                                        } else {
                                            Pair(customEval.x, customEval.y)
                                        }
                                        match = FoundMatch(
                                            name = rule.id,
                                            clickX = clickX,
                                            clickY = clickY,
                                            score = customEval.score,
                                            isContinue = isContinue,
                                            afterDelayMs = rule.afterDelayMs,
                                            rule = rule,
                                            scale = customEval.absoluteScale,
                                            source = "быстрый путь"
                                        )
                                        com.example.autoclicker.data.SmartLearnedStore.put(
                                            realW, realH, rule.id, learned.multiplier,
                                            learned.xFrac, learned.yFrac, customEval.score,
                                            hitCount = learned.hitCount + 1,
                                            configName = configName
                                        )
                                        lastFastPathMatchTime = System.currentTimeMillis()
                                        break
                                    }
                                }
                            }
                        }

                        // 2. ОБУЧЕНИЕ (ПОЛНЫЙ ПОИСК) (A.4, A.5):
                        // Запускается только если:
                        // (а) для правила нет выученной позиции на этом размере экрана;
                        // (б) ни одно правило не сработало на быстром пути за последние 5 секунд (раз в 5 секунд)
                        if (match == null) {
                            val now = System.currentTimeMillis()
                            val unlearnedRules = sortedRules.filter { r ->
                                val cfg = if (r.builtin) "builtin" else (r.configDir?.name ?: "builtin")
                                com.example.autoclicker.data.SmartLearnedStore.get(realW, realH, r.id, cfg) == null
                            }
                            val hasUnlearned = unlearnedRules.isNotEmpty()
                            val timeout5s = (now - lastFastPathMatchTime >= 5000L) && (now - lastFullSearchTime >= 5000L)

                            if (hasUnlearned || timeout5s) {
                                lastFullSearchTime = now

                                // 5. Дешёвые проверки по цвету для встроенных правил ДО тяжелого поиска по шаблону
                                if (hasBuiltin) {
                                    if (sortedRules.any { it.builtin && it.id == "start" }) {
                                        val yellowStart = detectYellowButton(screenshot, 0.0f, 0.42f, 0.65f, 1.0f, "start_yellow", false)
                                        if (yellowStart != null && yellowStart.score >= 0.78f) {
                                            match = yellowStart
                                        }
                                    }
                                    if (match == null && sortedRules.any { it.builtin && it.id.startsWith("continue") }) {
                                        val blueContinue = detectBlueButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_blue_color", true)
                                        if (blueContinue != null && blueContinue.score >= 0.78f) {
                                            match = blueContinue
                                        } else {
                                            val yellowContinue = detectYellowButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_yellow_color", true)
                                            if (yellowContinue != null && yellowContinue.score >= 0.78f) {
                                                match = yellowContinue
                                            }
                                        }
                                    }
                                }

                                // Полный поиск по шаблонам в рамках бюджета 400 мс на проход
                                if (match == null) {
                                    val rulesToSearch = if (hasUnlearned && !timeout5s) unlearnedRules else sortedRules
                                    val budgetDeadline = System.nanoTime() + 400_000_000L // 400 мс бюджет

                                    for (rule in rulesToSearch) {
                                        if (System.nanoTime() > budgetDeadline) break

                                        if (rule.builtin) {
                                            val rt = ruleTemplates.firstOrNull { it.rule.id == rule.id }
                                            if (rt != null) {
                                                val allowWide = com.example.autoclicker.data.SmartLearnedStore.get(realW, realH, rule.id) == null
                                                val smart = evalBuiltinMatchSmart(screenshot, rt, realW, realH, allowWide)
                                                val eval = smart.eval
                                                if (eval.bestScore >= rule.threshold) {
                                                    consecutiveNoMatchCount[rule.id] = 0
                                                    val isContinue = rule.id.contains("continue", ignoreCase = true)
                                                    match = FoundMatch(
                                                        name = rule.id,
                                                        clickX = eval.clickX,
                                                        clickY = eval.clickY,
                                                        score = eval.bestScore,
                                                        isContinue = isContinue,
                                                        afterDelayMs = rule.afterDelayMs,
                                                        rule = rule,
                                                        scale = eval.bestScale,
                                                        source = "полный поиск"
                                                    )
                                                    break
                                                }
                                            }
                                        } else {
                                            val eval = CustomRuleSearch.findCustomMatch(screenshot, rule, preparedCustomRules, budgetDeadline)
                                            if (eval.score >= rule.threshold) {
                                                consecutiveNoMatchCount[rule.id] = 0
                                                val (clickX, clickY) = if (rule.tapTarget == "fixed" && rule.tapX != null && rule.tapY != null) {
                                                    Pair(rule.tapX * screenshot.width, rule.tapY * screenshot.height)
                                                } else {
                                                    Pair(eval.x, eval.y)
                                                }
                                                match = FoundMatch(
                                                    name = rule.id,
                                                    clickX = clickX,
                                                    clickY = clickY,
                                                    score = eval.score,
                                                    isContinue = rule.id.contains("continue", ignoreCase = true),
                                                    afterDelayMs = rule.afterDelayMs,
                                                    rule = rule,
                                                    scale = eval.absoluteScale,
                                                    source = "полный поиск"
                                                )
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (match != null) {
                            if (!firstTapDone) {
                                firstTapDone = true
                                val firstTapMs = System.currentTimeMillis() - runStartTime
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: первое нажатие после «Старт» через ${firstTapMs}мс («${match.name}»)"
                                )
                            }
                            lastMatchTime = System.currentTimeMillis()
                            lastIdleWarnTime = lastMatchTime
                            matchCount++

                            val scaleX = if (screenshot.width > 0) realW.toFloat() / screenshot.width else 1f
                            val scaleY = if (screenshot.height > 0) realH.toFloat() / screenshot.height else 1f
                            val finalX = match.clickX * scaleX
                            val finalY = match.clickY * scaleY

                            // Запоминаем новую позицию в хранилище, если это было обучение (полный поиск или цвет)
                            val matchedRule = match.rule ?: sortedRules.firstOrNull { it.id == match.name || match.name.startsWith(it.id) }
                            if (match.source != "быстрый путь" && matchedRule != null) {
                                val cfg = if (matchedRule.builtin) "builtin" else (matchedRule.configDir?.name ?: "builtin")
                                val H = minOf(screenshot.width, screenshot.height).toFloat()
                                val baseScale = if (matchedRule.builtin) H / matchedRule.refHeight else (H / minOf(matchedRule.refWidth, matchedRule.refHeight))
                                val mult = if (baseScale > 0f && match.scale > 0f) match.scale / baseScale else 1f
                                val xFrac = match.clickX / screenshot.width
                                val yFrac = match.clickY / screenshot.height

                                com.example.autoclicker.data.SmartLearnedStore.put(
                                    realW, realH, matchedRule.id, mult,
                                    xFrac, yFrac, match.score,
                                    configName = cfg
                                )
                                val rLabel = ruleLabel(matchedRule)
                                val scaleStr = String.format(Locale.US, "%.2f", mult)
                                val scoreStr = String.format(Locale.US, "%.2f", match.score)
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: $rLabel: запомнены координаты (${finalX.toInt()}, ${finalY.toInt()}), масштаб $scaleStr, score $scoreStr"
                                )
                            }

                            // Также обновляем coordCache
                            val cacheKey = match.rule?.id ?: match.name
                            coordCache[cacheKey] = CachedCoord(finalX, finalY, realW, realH)

                            scanMissStreak = 0
                            val src = match.source
                            val scorePct = (match.score * 100).toInt()
                            val scaleStr = String.format(Locale.US, "%.2f", match.scale)
                            val rLabel = if (matchedRule != null) ruleLabel(matchedRule) else match.name

                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "SMART: КНОПКА ОБНАРУЖЕНА «$rLabel» (источник: $src, совпадение $scorePct%, масштаб $scaleStr) → НАЖАТИЕ в (${finalX.toInt()}, ${finalY.toInt()})"
                            )

                            _currentAction.value = "Нажатие «${match.name}»..."
                            _lastAction.value = "${match.name} (${finalX.toInt()}, ${finalY.toInt()})"

                            val tapOk = performTapWithHooks(finalX, finalY)
                            if (lastTapAtMs > 0L && System.currentTimeMillis() - lastTapAtMs < 60_000L) {
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: интервал с прошлого нажатия: ${System.currentTimeMillis() - lastTapAtMs} мс"
                                )
                            }
                            lastTapAtMs = System.currentTimeMillis()
                            if (tapOk) {
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: НАЖАТИЕ ВЫПОЛНЕНО «$rLabel» в (${finalX.toInt()}, ${finalY.toInt()}): жест принят системой"
                                )
                            } else {
                                val reason = when {
                                    !AccessibilityServiceHolder.isConnected -> "сервис доступности отключён"
                                    lastTapError != null -> "ошибка жеста: $lastTapError"
                                    else -> "жест отменён системой (dispatchGesture вернул cancelled)"
                                }
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: НАЖАТИЕ НЕ ВЫПОЛНЕНО «$rLabel» в (${finalX.toInt()}, ${finalY.toInt()}): $reason",
                                    isError = true
                                )
                            }

                            val delayMs = match.afterDelayMs
                            if (delayMs > 0) {
                                val totalSec = (delayMs / 1000).toInt()
                                if (totalSec > 1) {
                                    for (sec in totalSec downTo 1) {
                                        coroutineContext.ensureActive()
                                        _remainingSeconds.value = sec
                                        _countdownText.value = "${sec}с"
                                        delay(1000L)
                                    }
                                    _remainingSeconds.value = 0
                                    _countdownText.value = ""
                                } else {
                                    delay(delayMs)
                                }
                            }
                        } else {
                            // Идёт матч или экран не распознан
                            val nowIdle = System.currentTimeMillis()
                            if (nowIdle - lastMatchTime >= idleTimeoutMs && nowIdle - lastIdleWarnTime >= idleTimeoutMs) {
                                lastIdleWarnTime = nowIdle
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: ${(nowIdle - lastMatchTime) / 60000} мин нет совпадений (идёт матч или экран не распознан), работа продолжается"
                                )
                            }

                            val nextShotStr = if (effectiveConfig.scanIntervalMs < 1000L) {
                                "Следующий снимок через ${effectiveConfig.scanIntervalMs}мс"
                            } else {
                                "Следующий снимок через ${effectiveConfig.scanIntervalMs / 1000}с"
                            }
                            _nextAction.value = nextShotStr
                            val spent = System.currentTimeMillis() - loopStart
                            val searchMs = System.currentTimeMillis() - searchStart

                            // Не шумим в журнал: пишем «ничего не найдено» не чаще раза в 30 секунд
                            if (nowIdle - lastNoMatchLogTime >= 30_000L) {
                                lastNoMatchLogTime = nowIdle
                                EventLogManager.log(
                                    EventLogManager.TAG_AUTO_CLICKER,
                                    "SMART: кнопки не обнаружены на текущем экране"
                                )
                            }
                            delay((effectiveConfig.scanIntervalMs - spent).coerceAtLeast(50L))
                        }
                    } finally {
                        if (!screenshot.isRecycled) {
                            screenshot.recycle()
                        }
                    }
                } else {
                    _currentAction.value = "Умный режим: ожидание снимка..."
                    val nextShotStr = if (effectiveConfig.scanIntervalMs < 1000L) {
                        "Следующий снимок через ${effectiveConfig.scanIntervalMs}мс"
                    } else {
                        "Следующий снимок через ${effectiveConfig.scanIntervalMs / 1000}с"
                    }
                    _nextAction.value = nextShotStr
                    val spentNull = System.currentTimeMillis() - loopStart
                    delay((effectiveConfig.scanIntervalMs - spentNull).coerceAtLeast(50L))
                }

                consecutiveErrors = 0

                val nowHb = System.currentTimeMillis()
                if (nowHb - lastHeartbeatTime >= 60_000L) {
                    lastHeartbeatTime = nowHb
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: работает. Снимков: $scanCount (неудачных $nullShotCount), совпадений: $matchCount, последнее совпадение ${(nowHb - lastMatchTime) / 1000} с назад"
                    )
                }

            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                consecutiveErrors++
                val projectTrace = t.stackTrace.firstOrNull { it.className.startsWith("com.example.autoclicker") }
                    ?: t.stackTrace.firstOrNull()
                val traceStr = projectTrace?.toString() ?: "unknown"
                val errName = t.javaClass.simpleName
                val errMsg = t.message ?: "нет сообщения"

                _currentAction.value = "Ошибка: $errMsg"

                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: ОШИБКА в цикле: $errName: $errMsg @ $traceStr",
                    isError = true
                )

                if (consecutiveErrors >= 10) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: слишком много ошибок подряд (10)",
                        isError = true
                    )
                    endReason = "10 ошибок подряд"
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: остановка, причина: ошибки"
                    )
                    resetState()
                    clearScreensDir()

                    // Автоматический перезапуск до 3 раз за час (B.9)
                    val now = System.currentTimeMillis()
                    autoRestartTimestamps.removeAll { now - it > 3600_000L }
                    if (autoRestartTimestamps.size < 3) {
                        autoRestartTimestamps.add(now)
                        val attempt = autoRestartTimestamps.size
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: Авто-перезапуск после ошибок (попытка $attempt из 3 за час)..."
                        )
                        coroutineScope.launch {
                            delay(3000L)
                            if (endReason == "10 ошибок подряд") {
                                start()
                            }
                        }
                    } else {
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: Превышен лимит авто-перезапусков (3 за час). Режим остановлен.",
                            isError = true
                        )
                    }
                    return
                }

                delay(effectiveConfig.scanIntervalMs)
            }
        }
    }

    private fun ensureRuleTemplates(
        effectiveRules: List<com.example.autoclicker.data.SmartRule>,
        screenWidth: Int,
        screenHeight: Int
    ): List<RuleTemplate> {
        val rulesHash = effectiveRules.hashCode()
        if (cachedRuleTemplates != null &&
            cachedRulesHash == rulesHash &&
            cachedScreenWidth == screenWidth &&
            cachedScreenHeight == screenHeight &&
            cachedRuleTemplates!!.all { !it.rawBitmap.isRecycled }
        ) {
            return cachedRuleTemplates!!
        }

        val result = mutableListOf<RuleTemplate>()
        val H = minOf(screenWidth, screenHeight).toFloat()
        val scaleMultipliers = floatArrayOf(0.93f, 1.0f, 1.07f)

        for (rule in effectiveRules) {
            if (!rule.builtin) continue
            val bmp = loadRuleBitmap(rule) ?: continue
            val baseScale = H / rule.refHeight
            val variants = mutableListOf<TemplateVariant>()

            for (m in scaleMultipliers) {
                val scale = baseScale * m
                var targetW = (bmp.width * scale).roundToInt().coerceAtLeast(4)
                var targetH = (bmp.height * scale).roundToInt().coerceAtLeast(4)
                if (targetW % 2 != 0) targetW++
                if (targetH % 2 != 0) targetH++

                val downW = targetW / 2
                val downH = targetH / 2

                val scaledBmp = if (targetW == bmp.width && targetH == bmp.height) {
                    bmp
                } else {
                    Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                }
                val pixels = IntArray(targetW * targetH)
                scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaledBmp !== bmp && !scaledBmp.isRecycled) {
                    scaledBmp.recycle()
                }

                val tGray = FloatArray(downW * downH)
                var sumT = 0.0
                for (dy in 0 until downH) {
                    val srcY0 = dy * 2
                    val srcY1 = srcY0 + 1
                    val r0 = srcY0 * targetW
                    val r1 = srcY1 * targetW
                    val dstOffset = dy * downW
                    for (dx in 0 until downW) {
                        val srcX0 = dx * 2
                        val srcX1 = srcX0 + 1
                        val p00 = pixels[r0 + srcX0]
                        val p01 = pixels[r0 + srcX1]
                        val p10 = pixels[r1 + srcX0]
                        val p11 = pixels[r1 + srcX1]

                        val g00 = (((p00 shr 16) and 0xFF) * 299 + ((p00 shr 8) and 0xFF) * 587 + (p00 and 0xFF) * 114)
                        val g01 = (((p01 shr 16) and 0xFF) * 299 + ((p01 shr 8) and 0xFF) * 587 + (p01 and 0xFF) * 114)
                        val g10 = (((p10 shr 16) and 0xFF) * 299 + ((p10 shr 8) and 0xFF) * 587 + (p10 and 0xFF) * 114)
                        val g11 = (((p11 shr 16) and 0xFF) * 299 + ((p11 shr 8) and 0xFF) * 587 + (p11 and 0xFF) * 114)

                        val avg = (g00 + g01 + g10 + g11) / 4000f
                        tGray[dstOffset + dx] = avg
                        sumT += avg
                    }
                }

                val n = (downW * downH).toDouble()
                val meanT = sumT / n
                val tDiff = FloatArray(downW * downH)
                var sumDiffSq = 0.0
                for (i in tGray.indices) {
                    val diff = (tGray[i] - meanT).toFloat()
                    tDiff[i] = diff
                    sumDiffSq += diff * diff
                }
                val sigmaT = sqrt(sumDiffSq)

                variants.add(
                    TemplateVariant(
                        scaleMultiplier = m,
                        tDownW = downW,
                        tDownH = downH,
                        tDiff = tDiff,
                        sigmaT = sigmaT
                    )
                )
            }

            result.add(RuleTemplate(rule, bmp, variants))
        }

        cachedRuleTemplates = result
        cachedRulesHash = rulesHash
        cachedScreenWidth = screenWidth
        cachedScreenHeight = screenHeight
        return result
    }

    private fun loadRuleBitmap(rule: com.example.autoclicker.data.SmartRule): Bitmap? {
        if (rule.builtin) {
            val assetPath = rule.imageFile ?: return null
            return try {
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }

        if (!rule.imageFile.isNullOrEmpty() && rule.configDir != null) {
            val file = File(rule.configDir, File(rule.imageFile).name)
            if (file.exists()) {
                return try {
                    BitmapFactory.decodeFile(file.absolutePath)
                } catch (e: Exception) {
                    null
                }
            }
        }

        if (!rule.imageSource.isNullOrEmpty() && rule.imageBox != null && rule.configDir != null) {
            val cacheKey = "${rule.configDir.name}_${rule.id}"
            val cached = croppedBitmapsCache[cacheKey]
            if (cached != null && !cached.isRecycled) {
                return cached
            }

            val srcFile = File(rule.configDir, File(rule.imageSource).name)
            if (srcFile.exists()) {
                try {
                    val fullBmp = BitmapFactory.decodeFile(srcFile.absolutePath) ?: return null
                    val srcW = fullBmp.width
                    val srcH = fullBmp.height
                    val box = rule.imageBox
                    val bX = (box[0] * srcW).toInt().coerceIn(0, srcW - 1)
                    val bY = (box[1] * srcH).toInt().coerceIn(0, srcH - 1)
                    val bW = ((box[2] - box[0]) * srcW).toInt().coerceIn(4, srcW - bX)
                    val bH = ((box[3] - box[1]) * srcH).toInt().coerceIn(4, srcH - bY)
                    val cropped = Bitmap.createBitmap(fullBmp, bX, bY, bW, bH)
                    if (cropped !== fullBmp && !fullBmp.isRecycled) {
                        fullBmp.recycle()
                    }
                    croppedBitmapsCache[cacheKey] = cropped
                    return cropped
                } catch (e: Exception) {
                    return null
                }
            }
        }

        return null
    }

    data class MatchEval(
        val bestScore: Float,
        val bestScale: Float,
        val clickX: Float,
        val clickY: Float
    )

    private fun evalBuiltinMatch(
        screenshot: Bitmap,
        ruleTemplate: RuleTemplate
    ): MatchEval {
        val rule = ruleTemplate.rule
        val reg = rule.getEffectiveRegion()
        val roi = createDownsampledRoi(screenshot, reg[0], reg[2], reg[1], reg[3])
            ?: return MatchEval(-1f, 0f, 0f, 0f)

        val H = minOf(screenshot.width, screenshot.height).toFloat()
        val baseScale = H / rule.refHeight

        var bestScore = -1f
        var bestScale = 0f
        var bestCoords = Pair(0f, 0f)

        for (v in ruleTemplate.variants) {
            val (score, coords) = searchTemplateInRoi(roi, v)
            if (score > bestScore) {
                bestScore = score
                bestScale = baseScale * v.scaleMultiplier
                bestCoords = coords
            }
        }

        val (clickX, clickY) = if (rule.tapTarget == "fixed" && rule.tapX != null && rule.tapY != null) {
            Pair(rule.tapX * screenshot.width, rule.tapY * screenshot.height)
        } else {
            bestCoords
        }

        return MatchEval(bestScore, bestScale, clickX, clickY)
    }


    private fun buildVariant(bmp: Bitmap, multiplier: Float, baseScale: Float): TemplateVariant {
        val scale = baseScale * multiplier
        var targetW = (bmp.width * scale).roundToInt().coerceAtLeast(4)
        var targetH = (bmp.height * scale).roundToInt().coerceAtLeast(4)
        if (targetW % 2 != 0) targetW++
        if (targetH % 2 != 0) targetH++
        val downW = targetW / 2
        val downH = targetH / 2
        val scaledBmp = if (targetW == bmp.width && targetH == bmp.height) bmp
        else Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaledBmp !== bmp && !scaledBmp.isRecycled) scaledBmp.recycle()
        val tGray = FloatArray(downW * downH)
        var sumT = 0.0
        for (dy in 0 until downH) {
            val srcY0 = dy * 2
            val srcY1 = srcY0 + 1
            val r0 = srcY0 * targetW
            val r1 = srcY1 * targetW
            val dstOffset = dy * downW
            for (dx in 0 until downW) {
                val srcX0 = dx * 2
                val srcX1 = srcX0 + 1
                val p00 = pixels[r0 + srcX0]
                val p01 = pixels[r0 + srcX1]
                val p10 = pixels[r1 + srcX0]
                val p11 = pixels[r1 + srcX1]
                val g00 = (((p00 shr 16) and 0xFF) * 299 + ((p00 shr 8) and 0xFF) * 587 + (p00 and 0xFF) * 114)
                val g01 = (((p01 shr 16) and 0xFF) * 299 + ((p01 shr 8) and 0xFF) * 587 + (p01 and 0xFF) * 114)
                val g10 = (((p10 shr 16) and 0xFF) * 299 + ((p10 shr 8) and 0xFF) * 587 + (p10 and 0xFF) * 114)
                val g11 = (((p11 shr 16) and 0xFF) * 299 + ((p11 shr 8) and 0xFF) * 587 + (p11 and 0xFF) * 114)
                val avg = (g00 + g01 + g10 + g11) / 4000f
                tGray[dstOffset + dx] = avg
                sumT += avg
            }
        }
        val meanT = sumT / (downW * downH)
        val tDiff = FloatArray(downW * downH)
        var sumDiffSq = 0.0
        for (i in tGray.indices) {
            val d = tGray[i] - meanT
            tDiff[i] = d.toFloat()
            sumDiffSq += d * d
        }
        val sigmaT = kotlin.math.sqrt(sumDiffSq)
        return TemplateVariant(multiplier, downW, downH, tDiff, sigmaT)
    }

    private fun getOrBuildVariant(rt: RuleTemplate, multiplier: Float, H: Float): TemplateVariant {
        val m = (multiplier * 100).roundToInt() / 100f
        val key = "${rt.rule.id}@$m"
        variantBuildCache[key]?.let { return it }
        val baseScale = H / rt.rule.refHeight
        val v = buildVariant(rt.rawBitmap, m, baseScale)
        variantBuildCache[key] = v
        return v
    }

    private data class BuiltinSmartEval(
        val eval: MatchEval,
        val source: String,
        val stage: Int,
        val multiplier: Float
    )

    /** Быстрый путь: проверка в малом окне вокруг сохранённого центра (размер шаблона + 15%, масштаб ± 4%). */
    private fun evalBuiltinFastMatch(
        screenshot: Bitmap,
        ruleTemplate: RuleTemplate,
        learned: com.example.autoclicker.data.SmartLearnedStore.Learned
    ): MatchEval? {
        val rule = ruleTemplate.rule
        val H = minOf(screenshot.width, screenshot.height).toFloat()
        val baseScale = H / rule.refHeight

        // Размер шаблона при выученном масштабе
        val rawBmp = ruleTemplate.rawBitmap
        val templW = rawBmp.width * baseScale * learned.multiplier
        val templH = rawBmp.height * baseScale * learned.multiplier

        // Размер окна: размер шаблона плюс запас 15% с каждой стороны
        val halfSpanX = (templW * 0.65f) / screenshot.width
        val halfSpanY = (templH * 0.65f) / screenshot.height

        val x0 = (learned.xFrac - halfSpanX).coerceIn(0f, 1f)
        val x1 = (learned.xFrac + halfSpanX).coerceIn(0f, 1f)
        val y0 = (learned.yFrac - halfSpanY).coerceIn(0f, 1f)
        val y1 = (learned.yFrac + halfSpanY).coerceIn(0f, 1f)

        val roi = createDownsampledRoi(screenshot, x0, x1, y0, y1) ?: return null

        var bestScore = -1f
        var bestScale = 0f

        val scales = floatArrayOf(learned.multiplier * 0.96f, learned.multiplier, learned.multiplier * 1.04f)
        for (m in scales) {
            val v = getOrBuildVariant(ruleTemplate, m, H)
            val (score, _) = searchTemplateInRoi(roi, v)
            if (score > bestScore) {
                bestScore = score
                bestScale = baseScale * v.scaleMultiplier
            }
        }

        if (bestScore >= (rule.threshold - 0.05f)) {
            val clickX = learned.xFrac * screenshot.width
            val clickY = learned.yFrac * screenshot.height
            return MatchEval(bestScore, bestScale, clickX, clickY)
        }
        return null
    }

    /** 3 ступени: память → стандарт → широкий поиск. */
    private fun evalBuiltinMatchSmart(
        screenshot: Bitmap,
        ruleTemplate: RuleTemplate,
        realW: Int,
        realH: Int,
        allowWide: Boolean
    ): BuiltinSmartEval {
        val rule = ruleTemplate.rule
        val H = minOf(screenshot.width, screenshot.height).toFloat()
        val baseScale = H / rule.refHeight
        val learned = com.example.autoclicker.data.SmartLearnedStore.get(realW, realH, rule.id)

        // Ступень 1: запомненное
        if (learned != null) {
            val x0 = (learned.xFrac - 0.10f).coerceIn(0f, 1f)
            val x1 = (learned.xFrac + 0.10f).coerceIn(0f, 1f)
            val y0 = (learned.yFrac - 0.12f).coerceIn(0f, 1f)
            val y1 = (learned.yFrac + 0.12f).coerceIn(0f, 1f)
            val roi = createDownsampledRoi(screenshot, x0, x1, y0, y1)
            if (roi != null) {
                var bestScore = -1f
                var bestScale = 0f
                var bestCoords = Pair(0f, 0f)
                var bestM = learned.multiplier
                for (m in floatArrayOf(learned.multiplier, learned.multiplier * 0.96f, learned.multiplier * 1.04f)) {
                    val v = getOrBuildVariant(ruleTemplate, m, H)
                    val (score, coords) = searchTemplateInRoi(roi, v)
                    if (score > bestScore) {
                        bestScore = score
                        bestScale = baseScale * v.scaleMultiplier
                        bestCoords = coords
                        bestM = m
                    }
                }
                if (bestScore >= rule.threshold) {
                    if (bestScore > learned.score) {
                        com.example.autoclicker.data.SmartLearnedStore.put(
                            realW, realH, rule.id, bestM,
                            bestCoords.first / screenshot.width,
                            bestCoords.second / screenshot.height,
                            bestScore
                        )
                    }
                    return BuiltinSmartEval(MatchEval(bestScore, bestScale, bestCoords.first, bestCoords.second), "память", 1, bestM)
                }
            }
        }

        // Ступень 2: как сейчас
        val std = evalBuiltinMatch(screenshot, ruleTemplate)
        if (std.bestScore >= rule.threshold) {
            val mult = if (baseScale > 0f) (std.bestScale / baseScale) else 1f
            com.example.autoclicker.data.SmartLearnedStore.put(
                realW, realH, rule.id, mult,
                std.clickX / screenshot.width, std.clickY / screenshot.height, std.bestScore
            )
            return BuiltinSmartEval(std, "изображение", 2, mult)
        }

        // Ступень 3: широкий поиск
        if (allowWide) {
            val (rx0, rx1, ry0, ry1) = if (rule.id.contains("continue")) {
                floatArrayOf(0.30f, 1.0f, 0.45f, 1.0f)
            } else {
                floatArrayOf(0.0f, 0.80f, 0.30f, 1.0f)
            }
            val roi = createDownsampledRoi(screenshot, rx0, rx1, ry0, ry1)
            if (roi != null) {
                var bestScore = -1f
                var bestScale = 0f
                var bestCoords = Pair(0f, 0f)
                var bestM = 1f
                val multis = floatArrayOf(0.55f, 0.65f, 0.75f, 0.85f, 1.15f, 1.30f, 1.45f, 1.65f, 1.85f, 2.10f)
                for (m in multis) {
                    val v = getOrBuildVariant(ruleTemplate, m, H)
                    val (score, coords) = searchTemplateInRoi(roi, v)
                    if (score > bestScore) {
                        bestScore = score
                        bestScale = baseScale * v.scaleMultiplier
                        bestCoords = coords
                        bestM = m
                    }
                }
                if (bestScore >= rule.threshold) {
                    com.example.autoclicker.data.SmartLearnedStore.put(
                        realW, realH, rule.id, bestM,
                        bestCoords.first / screenshot.width,
                        bestCoords.second / screenshot.height,
                        bestScore
                    )
                    return BuiltinSmartEval(
                        MatchEval(bestScore, bestScale, bestCoords.first, bestCoords.second),
                        "изображение", 3, bestM
                    )
                }
            }
        }

        return BuiltinSmartEval(std, "изображение", 2, 1f)
    }

    private fun findMatchInScreenshot(
        screenshot: Bitmap,
        ruleTemplate: RuleTemplate
    ): FoundMatch? {
        val rule = ruleTemplate.rule
        val eval = evalBuiltinMatch(screenshot, ruleTemplate)
        if (eval.bestScore >= rule.threshold) {
            val isContinue = rule.id.contains("continue", ignoreCase = true)
            return FoundMatch(
                name = rule.id,
                clickX = eval.clickX,
                clickY = eval.clickY,
                score = eval.bestScore,
                isContinue = isContinue,
                afterDelayMs = rule.afterDelayMs,
                rule = rule,
                scale = eval.bestScale
            )
        }
        return null
    }

    suspend fun checkConfigNow(): CheckReport {
        val service = AccessibilityServiceHolder.service.value
        if (service == null || !AccessibilityServiceHolder.isConnected) {
            return CheckReport(
                message = "Сервис доступности не подключён",
                lines = emptyList()
            )
        }

        val screenshot = service.takeScreenshotBitmap()
            ?: return CheckReport(
                message = "Не удалось сделать снимок",
                lines = emptyList()
            )

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val realSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                Pair(dm.widthPixels, dm.heightPixels)
            }
            val realW = realSize.first
            val realH = realSize.second
            val scaleX = if (screenshot.width > 0) realW.toFloat() / screenshot.width else 1f
            val scaleY = if (screenshot.height > 0) realH.toFloat() / screenshot.height else 1f

            val effectiveConfig = com.example.autoclicker.data.SmartConfigManager.getInstance(context).buildEffectiveRules(silent = true)
            val customDir = effectiveConfig.rules.firstOrNull { !it.builtin }?.configDir
            val preparedCustomRules = CustomRuleSearch.prepareCustomRules(effectiveConfig.rules, customDir)
            val builtinTemplates = ensureRuleTemplates(effectiveConfig.rules, screenshot.width, screenshot.height)

            val lines = mutableListOf<String>()

            for (rule in effectiveConfig.rules) {
                if (rule.builtin) {
                    val rt = builtinTemplates.firstOrNull { it.rule.id == rule.id }
                    if (rt != null) {
                        val eval = evalBuiltinMatch(screenshot, rt)
                        val scoreStr = String.format(Locale.US, "%.2f", maxOf(0f, eval.bestScore))
                        val scaleStr = String.format(Locale.US, "%.2f", eval.bestScale)
                        val threshStr = String.format(Locale.US, "%.2f", rule.threshold)
                        val outcome = if (eval.bestScore >= rule.threshold) {
                            val finalX = (eval.clickX * scaleX).toInt()
                            val finalY = (eval.clickY * scaleY).toInt()
                            "найдено ($finalX,$finalY)"
                        } else {
                            "не найдено"
                        }
                        lines.add("${rule.id}: score $scoreStr, масштаб $scaleStr, порог $threshStr → $outcome")
                    }
                } else {
                    val prep = preparedCustomRules.firstOrNull { it.rule.id == rule.id }
                    prep?.errors?.forEach { err ->
                        lines.add("Ошибка (${rule.id}): $err")
                    }
                    val eval = CustomRuleSearch.findCustomMatch(screenshot, rule, preparedCustomRules)
                    val scoreStr = String.format(Locale.US, "%.2f", maxOf(0f, eval.score))
                    val scaleStr = String.format(Locale.US, "%.2f", eval.absoluteScale)
                    val threshStr = String.format(Locale.US, "%.2f", rule.threshold)
                    val outcome = if (eval.score >= rule.threshold) {
                        val (clickX, clickY) = if (rule.tapTarget == "fixed" && rule.tapX != null && rule.tapY != null) {
                            Pair(rule.tapX * realW, rule.tapY * realH)
                        } else {
                            Pair(eval.x * scaleX, eval.y * scaleY)
                        }
                        "найдено (${clickX.toInt()},${clickY.toInt()})"
                    } else {
                        "не найдено"
                    }
                    lines.add("${rule.id}: score $scoreStr, масштаб $scaleStr, порог $threshStr → $outcome")
                }
            }

            return CheckReport(
                message = "Результаты проверки:",
                lines = lines
            )
        } finally {
            if (!screenshot.isRecycled) {
                screenshot.recycle()
            }
        }
    }

    private suspend fun performTapWithHooks(x: Float, y: Float): Boolean {
        lastTapError = null
        val success = try {
            gestureExecutor.performTap(x, y)
        } catch (t: Throwable) {
            lastTapError = t.message ?: t.toString()
            false
        }
        return success
    }

    private fun ensureTemplatesLoaded() {
        if (rawTemplates != null && rawTemplates!!.isNotEmpty() && rawTemplates!!.all { !it.bitmap.isRecycled }) {
            return
        }

        val templates = mutableListOf<RawTemplate>()
        val list = listOf(
            Triple("continue_mvp", "templates/continue_mvp.png", true),
            Triple("continue_blue", "templates/continue_blue.png", true),
            Triple("start", "templates/start.png", false)
        )

        for ((name, assetPath, isContinue) in list) {
            try {
                context.assets.open(assetPath).use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        templates.add(RawTemplate(name, bmp, isContinue))
                    }
                }
            } catch (e: Exception) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "ERROR: Не удалось загрузить шаблон $name: ${e.message}",
                    isError = true
                )
            }
        }
        rawTemplates = templates
    }

    /**
     * Предварительный расчет шаблонов в 5 масштабах: 0.85, 0.93, 1.0, 1.07, 1.18.
     * Не вызывает recycle() на исходных шаблонах rawTemplate.
     */
    private fun getPrecomputedTemplates(screenWidth: Int, screenHeight: Int): List<PrecomputedMultiScaleTemplate> {
        if (precomputedTemplates != null && cachedScreenWidth == screenWidth && cachedScreenHeight == screenHeight) {
            if (rawTemplates != null && rawTemplates!!.all { !it.bitmap.isRecycled }) {
                return precomputedTemplates!!
            }
        }

        ensureTemplatesLoaded()
        val raw = rawTemplates ?: return emptyList()
        val minDim = minOf(screenWidth, screenHeight)
        val baseScale = minDim / 800f
        val scaleMultipliers = floatArrayOf(0.85f, 0.93f, 1.0f, 1.07f, 1.18f)
        val result = mutableListOf<PrecomputedMultiScaleTemplate>()

        for (template in raw) {
            if (template.bitmap.isRecycled) continue
            val variants = mutableListOf<TemplateVariant>()
            for (m in scaleMultipliers) {
                val scale = baseScale * m
                var targetW = (template.bitmap.width * scale).roundToInt().coerceAtLeast(4)
                var targetH = (template.bitmap.height * scale).roundToInt().coerceAtLeast(4)
                if (targetW % 2 != 0) targetW++
                if (targetH % 2 != 0) targetH++

                val downW = targetW / 2
                val downH = targetH / 2

                val scaledBmp = if (targetW == template.bitmap.width && targetH == template.bitmap.height) {
                    template.bitmap
                } else {
                    Bitmap.createScaledBitmap(template.bitmap, targetW, targetH, true)
                }
                val pixels = IntArray(targetW * targetH)
                scaledBmp.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
                if (scaledBmp !== template.bitmap && !scaledBmp.isRecycled) {
                    scaledBmp.recycle()
                }

                val tGray = FloatArray(downW * downH)
                var sumT = 0.0
                for (dy in 0 until downH) {
                    val srcY0 = dy * 2
                    val srcY1 = srcY0 + 1
                    val r0 = srcY0 * targetW
                    val r1 = srcY1 * targetW
                    val dstOffset = dy * downW
                    for (dx in 0 until downW) {
                        val srcX0 = dx * 2
                        val srcX1 = srcX0 + 1
                        val p00 = pixels[r0 + srcX0]
                        val p01 = pixels[r0 + srcX1]
                        val p10 = pixels[r1 + srcX0]
                        val p11 = pixels[r1 + srcX1]

                        val g00 = (((p00 shr 16) and 0xFF) * 299 + ((p00 shr 8) and 0xFF) * 587 + (p00 and 0xFF) * 114)
                        val g01 = (((p01 shr 16) and 0xFF) * 299 + ((p01 shr 8) and 0xFF) * 587 + (p01 and 0xFF) * 114)
                        val g10 = (((p10 shr 16) and 0xFF) * 299 + ((p10 shr 8) and 0xFF) * 587 + (p10 and 0xFF) * 114)
                        val g11 = (((p11 shr 16) and 0xFF) * 299 + ((p11 shr 8) and 0xFF) * 587 + (p11 and 0xFF) * 114)

                        val avg = (g00 + g01 + g10 + g11) / 4000f
                        tGray[dstOffset + dx] = avg
                        sumT += avg
                    }
                }

                val n = (downW * downH).toDouble()
                val meanT = sumT / n
                val tDiff = FloatArray(downW * downH)
                var sumDiffSq = 0.0
                for (i in tGray.indices) {
                    val diff = (tGray[i] - meanT).toFloat()
                    tDiff[i] = diff
                    sumDiffSq += diff * diff
                }
                val sigmaT = sqrt(sumDiffSq)

                variants.add(
                    TemplateVariant(
                        scaleMultiplier = m,
                        tDownW = downW,
                        tDownH = downH,
                        tDiff = tDiff,
                        sigmaT = sigmaT
                    )
                )
            }

            result.add(
                PrecomputedMultiScaleTemplate(
                    name = template.name,
                    isContinue = template.isContinue,
                    variants = variants
                )
            )
        }

        cachedScreenWidth = screenWidth
        cachedScreenHeight = screenHeight
        precomputedTemplates = result
        return result
    }

    /**
     * Поиск желтой кнопки «НАЧАТЬ» в лобби или желтой кнопки «ПРОДОЛЖИТЬ» по цвету и прямоугольной форме.
     */
    private fun detectYellowButton(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float,
        name: String,
        isContinue: Boolean
    ): FoundMatch? {
        val sW = screenshot.width
        val sH = screenshot.height
        val x0 = (sW * fracX0).toInt().coerceIn(0, sW - 1)
        val x1 = (sW * fracX1).toInt().coerceIn(x0 + 1, sW)
        val y0 = (sH * fracY0).toInt().coerceIn(0, sH - 1)
        val y1 = (sH * fracY1).toInt().coerceIn(y0 + 1, sH)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 10 || roiH < 10) return null

        val pixels = IntArray(roiW * roiH)
        screenshot.getPixels(pixels, 0, roiW, x0, y0, roiW, roiH)

        var minX = roiW
        var maxX = 0
        var minY = roiH
        var maxY = 0
        var yellowCount = 0

        val step = 2
        for (y in 0 until roiH step step) {
            val rowOffset = y * roiW
            for (x in 0 until roiW step step) {
                val p = pixels[rowOffset + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Золотисто-желтый оттенок PUBG Mobile
                if (r in 180..255 && g in 130..245 && b in 0..115 && (r - b) >= 85 && (g - b) >= 45 && r >= (g - 20)) {
                    yellowCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (yellowCount < 15) return null

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1

        val minAllowedW = (sW * 0.06f).toInt()
        val maxAllowedW = (sW * 0.40f).toInt()
        val minAllowedH = (sH * 0.03f).toInt()
        val maxAllowedH = (sH * 0.22f).toInt()

        if (boxW in minAllowedW..maxAllowedW && boxH in minAllowedH..maxAllowedH) {
            val aspect = boxW.toFloat() / boxH
            if (aspect in 1.4f..6.5f) {
                val totalSampled = ((boxW / step) * (boxH / step)).coerceAtLeast(1)
                val density = yellowCount.toFloat() / totalSampled
                if (density >= 0.25f) {
                    val clickX = x0 + (minX + maxX) / 2f
                    val clickY = y0 + (minY + maxY) / 2f
                    val score = (0.86f + (density * 0.10f)).coerceAtMost(0.98f)
                    return FoundMatch(name, clickX, clickY, score, isContinue, afterDelayMs = 50L, source = "цвет")
                } else {
                    val nowR = System.currentTimeMillis()
                    if (nowR - lastYellowRejectLogMs >= 5000L) {
                        lastYellowRejectLogMs = nowR
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "SMART: жёлтая область отклонена: рамка ${boxW}x${boxH} (допустимо ширина $minAllowedW..$maxAllowedW, высота $minAllowedH..$maxAllowedH), пропорция ${"%.2f".format(aspect)}, плотность ${"%.2f".format(density)} (нужно ≥ 0.25)"
                        )
                    }
                }
            } else {
                val nowR = System.currentTimeMillis()
                if (nowR - lastYellowRejectLogMs >= 5000L) {
                    lastYellowRejectLogMs = nowR
                    val aspect = boxW.toFloat() / boxH.coerceAtLeast(1)
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: жёлтая область отклонена: рамка ${boxW}x${boxH} (допустимо ширина $minAllowedW..$maxAllowedW, высота $minAllowedH..$maxAllowedH), пропорция ${"%.2f".format(aspect)}, плотность n/a (нужно ≥ 0.25)"
                    )
                }
            }
        } else {
            val nowR = System.currentTimeMillis()
            if (nowR - lastYellowRejectLogMs >= 5000L) {
                lastYellowRejectLogMs = nowR
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SMART: жёлтая область отклонена: рамка ${boxW}x${boxH} (допустимо ширина $minAllowedW..$maxAllowedW, высота $minAllowedH..$maxAllowedH), пропорция n/a, плотность n/a (нужно ≥ 0.25)"
                )
            }
        }
        return null
    }

    /**
     * Поиск синей кнопки «ПРОДОЛЖИТЬ» / «В ЛОББИ» по цвету и прямоугольной форме.
     */
    private fun detectBlueButton(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float,
        name: String,
        isContinue: Boolean
    ): FoundMatch? {
        val sW = screenshot.width
        val sH = screenshot.height
        val x0 = (sW * fracX0).toInt().coerceIn(0, sW - 1)
        val x1 = (sW * fracX1).toInt().coerceIn(x0 + 1, sW)
        val y0 = (sH * fracY0).toInt().coerceIn(0, sH - 1)
        val y1 = (sH * fracY1).toInt().coerceIn(y0 + 1, sH)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 10 || roiH < 10) return null

        val pixels = IntArray(roiW * roiH)
        screenshot.getPixels(pixels, 0, roiW, x0, y0, roiW, roiH)

        var minX = roiW
        var maxX = 0
        var minY = roiH
        var maxY = 0
        var blueCount = 0

        val step = 2
        for (y in 0 until roiH step step) {
            val rowOffset = y * roiW
            for (x in 0 until roiW step step) {
                val p = pixels[rowOffset + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Синий оттенок кнопки продолжения PUBG
                if (b in 150..255 && r in 0..120 && g in 80..230 && (b - r) >= 60) {
                    blueCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (blueCount < 15) return null

        val boxW = maxX - minX + 1
        val boxH = maxY - minY + 1

        val minAllowedW = (sW * 0.05f).toInt()
        val maxAllowedW = (sW * 0.35f).toInt()
        val minAllowedH = (sH * 0.03f).toInt()
        val maxAllowedH = (sH * 0.20f).toInt()

        if (boxW in minAllowedW..maxAllowedW && boxH in minAllowedH..maxAllowedH) {
            val aspect = boxW.toFloat() / boxH
            if (aspect in 1.4f..6.0f) {
                val totalSampled = ((boxW / step) * (boxH / step)).coerceAtLeast(1)
                val density = blueCount.toFloat() / totalSampled
                if (density >= 0.25f) {
                    val clickX = x0 + (minX + maxX) / 2f
                    val clickY = y0 + (minY + maxY) / 2f
                    val score = (0.84f + (density * 0.10f)).coerceAtMost(0.96f)
                    return FoundMatch(name, clickX, clickY, score, isContinue, afterDelayMs = 50L, source = "цвет")
                }
            }
        }
        return null
    }

    private fun createDownsampledRoi(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float
    ): DownsampledRoi? {
        val screenW = screenshot.width
        val screenH = screenshot.height

        val roiX0 = (screenW * fracX0).toInt().coerceIn(0, screenW - 1)
        val roiX1 = (screenW * fracX1).toInt().coerceIn(roiX0 + 1, screenW)
        val roiY0 = (screenH * fracY0).toInt().coerceIn(0, screenH - 1)
        val roiY1 = (screenH * fracY1).toInt().coerceIn(roiY0 + 1, screenH)

        val rawW = roiX1 - roiX0
        val rawH = roiY1 - roiY0

        val evenW = if (rawW % 2 != 0) rawW - 1 else rawW
        val evenH = if (rawH % 2 != 0) rawH - 1 else rawH

        val downW = evenW / 2
        val downH = evenH / 2
        if (downW < 2 || downH < 2) return null

        val pixels = IntArray(evenW * evenH)
        screenshot.getPixels(pixels, 0, evenW, roiX0, roiY0, evenW, evenH)

        val gray = FloatArray(downW * downH)
        for (dy in 0 until downH) {
            val srcY0 = dy * 2
            val srcY1 = srcY0 + 1
            val r0 = srcY0 * evenW
            val r1 = srcY1 * evenW
            val dstOffset = dy * downW
            for (dx in 0 until downW) {
                val srcX0 = dx * 2
                val srcX1 = srcX0 + 1
                val p00 = pixels[r0 + srcX0]
                val p01 = pixels[r0 + srcX1]
                val p10 = pixels[r1 + srcX0]
                val p11 = pixels[r1 + srcX1]

                val g00 = (((p00 shr 16) and 0xFF) * 299 + ((p00 shr 8) and 0xFF) * 587 + (p00 and 0xFF) * 114)
                val g01 = (((p01 shr 16) and 0xFF) * 299 + ((p01 shr 8) and 0xFF) * 587 + (p01 and 0xFF) * 114)
                val g10 = (((p10 shr 16) and 0xFF) * 299 + ((p10 shr 8) and 0xFF) * 587 + (p10 and 0xFF) * 114)
                val g11 = (((p11 shr 16) and 0xFF) * 299 + ((p11 shr 8) and 0xFF) * 587 + (p11 and 0xFF) * 114)

                gray[dstOffset + dx] = (g00 + g01 + g10 + g11) / 4000f
            }
        }

        val intW = downW + 1
        val intH = downH + 1
        val sumI = DoubleArray(intW * intH)
        val sumI2 = DoubleArray(intW * intH)

        for (y in 0 until downH) {
            var rowSum = 0.0
            var rowSum2 = 0.0
            val rowOffset = y * downW
            val intRowOffset = (y + 1) * intW
            val prevIntRowOffset = y * intW
            for (x in 0 until downW) {
                val v = gray[rowOffset + x].toDouble()
                rowSum += v
                rowSum2 += v * v
                sumI[intRowOffset + (x + 1)] = sumI[prevIntRowOffset + (x + 1)] + rowSum
                sumI2[intRowOffset + (x + 1)] = sumI2[prevIntRowOffset + (x + 1)] + rowSum2
            }
        }

        return DownsampledRoi(
            roiX0 = roiX0,
            roiY0 = roiY0,
            downW = downW,
            downH = downH,
            gray = gray,
            sumI = sumI,
            sumI2 = sumI2,
            intW = intW
        )
    }

    private fun searchTemplateInRoi(
        roi: DownsampledRoi,
        variant: TemplateVariant
    ): Pair<Float, Pair<Float, Float>> {
        val tW = variant.tDownW
        val tH = variant.tDownH
        if (roi.downW < tW || roi.downH < tH || variant.sigmaT <= 1e-4) {
            return Pair(-1f, Pair(0f, 0f))
        }

        val maxSearchX = roi.downW - tW
        val maxSearchY = roi.downH - tH
        val n = (tW * tH).toDouble()
        val tDiff = variant.tDiff
        val sigmaT = variant.sigmaT
        val sumI = roi.sumI
        val sumI2 = roi.sumI2
        val intW = roi.intW
        val gray = roi.gray
        val downW = roi.downW

        fun getBoxSum(x: Int, y: Int, w: Int, h: Int): Double {
            val x1 = x
            val y1 = y
            val x2 = x + w
            val y2 = y + h
            val d = sumI[y2 * intW + x2]
            val b = sumI[y1 * intW + x2]
            val c = sumI[y2 * intW + x1]
            val a = sumI[y1 * intW + x1]
            return d - b - c + a
        }

        fun getBoxSum2(x: Int, y: Int, w: Int, h: Int): Double {
            val x1 = x
            val y1 = y
            val x2 = x + w
            val y2 = y + h
            val d = sumI2[y2 * intW + x2]
            val b = sumI2[y1 * intW + x2]
            val c = sumI2[y2 * intW + x1]
            val a = sumI2[y1 * intW + x1]
            return d - b - c + a
        }

        var bestScore = -1f
        var bestX = -1
        var bestY = -1

        var y = 0
        while (y <= maxSearchY) {
            var x = 0
            while (x <= maxSearchX) {
                val sI = getBoxSum(x, y, tW, tH)
                val sI2 = getBoxSum2(x, y, tW, tH)
                val varianceI = sI2 - (sI * sI) / n
                if (varianceI > 1e-4) {
                    val sigmaI = sqrt(varianceI)
                    var num = 0.0
                    var tIdx = 0
                    for (ty in 0 until tH) {
                        val rowOffset = (y + ty) * downW + x
                        for (tx in 0 until tW) {
                            num += tDiff[tIdx++] * gray[rowOffset + tx]
                        }
                    }
                    val score = (num / (sigmaT * sigmaI)).toFloat()
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                }
                x += 2
            }
            y += 2
        }

        if (bestScore >= 0.65f && bestX >= 0 && bestY >= 0) {
            val rMinX = (bestX - 2).coerceAtLeast(0)
            val rMaxX = (bestX + 2).coerceAtMost(maxSearchX)
            val rMinY = (bestY - 2).coerceAtLeast(0)
            val rMaxY = (bestY + 2).coerceAtMost(maxSearchY)

            for (ry in rMinY..rMaxY) {
                for (rx in rMinX..rMaxX) {
                    if (rx == bestX && ry == bestY) continue
                    val sI = getBoxSum(rx, ry, tW, tH)
                    val sI2 = getBoxSum2(rx, ry, tW, tH)
                    val varianceI = sI2 - (sI * sI) / n
                    if (varianceI > 1e-4) {
                        val sigmaI = sqrt(varianceI)
                        var num = 0.0
                        var tIdx = 0
                        for (ty in 0 until tH) {
                            val rowOffset = (ry + ty) * downW + rx
                            for (tx in 0 until tW) {
                                num += tDiff[tIdx++] * gray[rowOffset + tx]
                            }
                        }
                        val score = (num / (sigmaT * sigmaI)).toFloat()
                        if (score > bestScore) {
                            bestScore = score
                            bestX = rx
                            bestY = ry
                        }
                    }
                }
            }
        }

        if (bestX >= 0 && bestY >= 0) {
            val clickX = roi.roiX0 + (bestX + tW / 2f) * 2f
            val clickY = roi.roiY0 + (bestY + tH / 2f) * 2f
            return Pair(bestScore, Pair(clickX, clickY))
        }

        return Pair(bestScore, Pair(0f, 0f))
    }

    /**
     * Комплексное сканирование всех кандидатов:
     * 1. «НАЧАТЬ» в лобби (левый нижний угол: цвет + шаблон)
     * 2. «ПРОДОЛЖИТЬ» после катки (правый нижний угол: шаблоны continue_mvp, continue_blue + желтый/синий цвет)
     */
    private fun scanAllCandidates(screenshot: Bitmap): CandidateScanResult {
        val templates = getPrecomputedTemplates(screenshot.width, screenshot.height)
        val tmplMvp = templates.firstOrNull { it.name == "continue_mvp" }
        val tmplBlue = templates.firstOrNull { it.name == "continue_blue" }
        val tmplStart = templates.firstOrNull { it.name == "start" }

        var countCandidates = 0

        // 1. Поиск в левом нижнем углу («НАЧАТЬ»)
        var bestStartMatch: FoundMatch? = null
        var bestStartScore = 0f

        // А) Поиск жёлтой кнопки «НАЧАТЬ» по цвету и геометрии
        val yellowStart = detectYellowButton(screenshot, 0.0f, 0.42f, 0.65f, 1.0f, "start_yellow", false)
        if (yellowStart != null && yellowStart.score >= 0.78f) {
            bestStartMatch = yellowStart
            bestStartScore = yellowStart.score
            countCandidates++
        }

        // Б) Шаблонный поиск «НАЧАТЬ»
        var tmplStartScore = 0f
        val startRoi = createDownsampledRoi(screenshot, 0.0f, 0.42f, 0.65f, 1.0f)
        if (startRoi != null && tmplStart != null) {
            for (v in tmplStart.variants) {
                val (score, coords) = searchTemplateInRoi(startRoi, v)
                if (score > tmplStartScore) {
                    tmplStartScore = score
                    if (score >= 0.75f && score > bestStartScore) {
                        bestStartScore = score
                        bestStartMatch = FoundMatch("start_template", coords.first, coords.second, score, false)
                        countCandidates++
                    }
                }
            }
        }

        // 2. Поиск в правом нижнем углу («ПРОДОЛЖИТЬ»)
        var bestContinueMatch: FoundMatch? = null
        var bestContinueScore = 0f

        // А) Шаблоны continue_mvp и continue_blue
        var scoreMvp = 0f
        var scoreBlueTmpl = 0f
        val continueRoi = createDownsampledRoi(screenshot, 0.50f, 1.0f, 0.65f, 1.0f)
        if (continueRoi != null) {
            if (tmplMvp != null) {
                for (v in tmplMvp.variants) {
                    val (score, coords) = searchTemplateInRoi(continueRoi, v)
                    if (score > scoreMvp) {
                        scoreMvp = score
                        if (score >= 0.75f && score > bestContinueScore) {
                            bestContinueScore = score
                            bestContinueMatch = FoundMatch("continue_mvp", coords.first, coords.second, score, true)
                            countCandidates++
                        }
                    }
                }
            }
            if (tmplBlue != null) {
                for (v in tmplBlue.variants) {
                    val (score, coords) = searchTemplateInRoi(continueRoi, v)
                    if (score > scoreBlueTmpl) {
                        scoreBlueTmpl = score
                        if (score >= 0.75f && score > bestContinueScore) {
                            bestContinueScore = score
                            bestContinueMatch = FoundMatch("continue_blue", coords.first, coords.second, score, true)
                            countCandidates++
                        }
                    }
                }
            }
        }

        // Б) Цветовой поиск продолжения (синяя или желтая кнопка)
        val blueContinue = detectBlueButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_blue_color", true)
        if (blueContinue != null && blueContinue.score >= 0.78f) {
            countCandidates++
            if (blueContinue.score > bestContinueScore) {
                bestContinueScore = blueContinue.score
                bestContinueMatch = blueContinue
            }
        }

        val yellowContinue = detectYellowButton(screenshot, 0.50f, 1.0f, 0.65f, 1.0f, "continue_yellow_color", true)
        if (yellowContinue != null && yellowContinue.score >= 0.78f) {
            countCandidates++
            if (yellowContinue.score > bestContinueScore) {
                bestContinueScore = yellowContinue.score
                bestContinueMatch = yellowContinue
            }
        }

        val summary = String.format(
            Locale.US,
            "start(color=%.2f, tmpl=%.2f), continue(mvp=%.2f, blue=%.2f, col_blue=%.2f, col_yel=%.2f)",
            yellowStart?.score ?: 0f,
            tmplStartScore,
            scoreMvp,
            scoreBlueTmpl,
            blueContinue?.score ?: 0f,
            yellowContinue?.score ?: 0f
        )

        return CandidateScanResult(
            bestStart = bestStartMatch,
            bestContinue = bestContinueMatch,
            totalCandidates = countCandidates,
            debugSummary = summary
        )
    }

    private fun showUnrecognizedScreenNotification() {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, AutoClickForegroundService.CHANNEL_ID)
                .setContentTitle("AutoClicker")
                .setContentText("Экран не распознан")
                .setSmallIcon(R.drawable.ic_stop)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(1002, notif)
        } catch (_: Exception) {}
    }

    private fun saveDebugScreenshot(bitmap: Bitmap) {
        try {
            val dir = File(context.cacheDir, "screens")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "screen_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            val files = dir.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() }
            if (files != null && files.size > 5) {
                val toDelete = files.take(files.size - 5)
                toDelete.forEach { it.delete() }
            }
        } catch (_: Exception) {}
    }

    private fun clearScreensDir() {
        try {
            val dir = File(context.cacheDir, "screens")
            if (dir.exists()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        @Volatile
        private var INSTANCE: SmartEngine? = null

        fun clearGlobalCaches(): Long {
            return INSTANCE?.clearCaches() ?: 0L
        }

        fun clearGlobalTemplateAndCoordinateCaches() {
            INSTANCE?.clearTemplateAndCoordinateCaches() ?: CustomRuleSearch.clearTemplateCaches()
        }

        fun getInstance(
            context: Context,
            gestureExecutor: GestureExecutor,
            settingsRepository: SettingsRepository
        ): SmartEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SmartEngine(
                    context.applicationContext,
                    gestureExecutor,
                    settingsRepository
                ).also { INSTANCE = it }
            }
        }
    }
}
