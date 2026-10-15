package com.example.autoclicker.engine

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import com.example.autoclicker.data.EffectiveConfig
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartRule
import com.example.autoclicker.service.AutoClickAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Быстрый конвейер для встроенных правил PUBG Mobile (режим DEFAULT_ONLY).
 * Работает на машине состояний (UNKNOWN, LOBBY, IN_MATCH, RESULT),
 * использует мгновенные 9-точечные цветовые отпечатки кнопок (ButtonFingerprint)
 * без сканирования и выделения памяти на полный экран в горячем цикле.
 */
class FastPipeline(
    private val context: Context,
    private val gestureExecutor: GestureExecutor,
    private val settingsRepository: SettingsRepository,
    private val onActionUpdate: (current: String, next: String, last: String?) -> Unit,
    private val onCycleIncrement: () -> Unit,
    private val templateFallback: (suspend (Bitmap, SmartRule) -> Pair<Float, Float>?)? = null
) {

    companion object {
        // Порядок долей экрана: [x0, x1, y0, y1]
        val START_ROI = floatArrayOf(0.0f, 0.42f, 0.65f, 1.0f)
        val CONTINUE_ROI = floatArrayOf(0.72f, 1.0f, 0.84f, 1.0f)

        // Режимы проб отпечатка
        const val PROBE_GRID = "grid"   // 9 проб сеткой 3x3 внутри рамки (для start)
        const val PROBE_EDGE6 = "edge6" // 6 проб у левого края рамки в полосах без текста (для continue)

        // Сколько проб должно совпасть
        const val REQUIRED_GRID = 7
        const val REQUIRED_EDGE = 5

        // Подтверждение нажатий (слабый интернет / медленный планшет)
        const val START_RETRY_MS = 800L           // пауза перед повтором тапа по start
        const val CONTINUE_RETRY_MS = 700L        // пауза перед повтором тапа по continue
        const val CONFIRM_GONE_FRAMES = 2         // кнопка должна пропасть на N кадрах подряд
        const val CONTINUE_CONFIRM_FRAMES = 2     // continue должен быть виден N кадров подряд до тапа
        const val CALIBRATION_CONFIRM_FRAMES = 2  // кандидат калибровки должен повториться N кадров
        const val MAX_CONTINUE_TAPS = 5           // тапов без реакции до отбраковки отпечатка
        const val FRESH_CONTINUE_TAPS = 2         // то же для только что откалиброванного отпечатка
        const val CONTINUE_BLOCK_MS = 10_000L     // пауза continue после отбраковки

        // Ворота конца матча: «Продолжить» ищем только когда счёт близок к цели
        const val DEFAULT_TARGET = EndgameGate.DEFAULT_TARGET
        const val ARM_MARGIN = EndgameGate.ARM_MARGIN
        const val OCR_FAIL_LIMIT = 4                  // столько сбоев OCR подряд, и OCR временно отключается
        const val OCR_DISABLED_COOLDOWN_MS = 30_000L  // пауза перед повторным включением OCR после сбоев
        const val WAITING_FALLBACK_MS = 90_000L       // ожидание без единого признака -> считаем что матч
        const val WAITING_FALLBACK_NO_OCR_MS = 15_000L
        const val IN_MATCH_START_RECHECK_MS = 2_000L // быстро возвращаться в лобби при видимой жёлтой НАЧАТЬ
        const val IN_MATCH_ARMED_INTERVAL_MS = 350L
        const val IN_MATCH_IDLE_INTERVAL_MS = 1200L
        const val SCORE_CONFLICT_INTERVAL_MS = 350L   // счёт расходится с хранимым: читаем чаще, чтобы быстро исправить
        const val SCORE_READ_PERIOD_MS = 900L
        const val SCORE_CONFLICT_READ_PERIOD_MS = 300L

        // Проверка, что тап по start реально запустил подбор
        const val START_GONE_YELLOW_MAX = 0.12f   // на месте кнопки жёлтого должно стать меньше 12%
        const val START_VERIFY_MS = 8_000L
        const val PROVISIONAL_MAX_TAPS = 3        // новая (не сохранённая) точка start: максимум 3 тапа
        const val RESULT_EXIT_MS = 10_000L        // сколько ждать кнопку на экране результатов
        const val REJECT_START = "rejected_start"
    }

    enum class State {
        UNKNOWN,
        WAITING,   // экран неизвестен: загрузка игры, заставка, нет кнопок
        LOBBY,
        IN_MATCH,
        RESULT
    }

    enum class ButtonKind {
        YELLOW,
        BLUE
    }

    data class FingerprintSample(
        // grid: доли рамки 0.25 / 0.50 / 0.75
        // edge6: relX = смещение в пикселях (при высоте экрана 800) от ЛЕВОГО края рамки,
        //        relY = смещение в пикселях от верха рамки (если >= 0) или от низа рамки (если < 0)
        val relX: Float,
        val relY: Float,
        val r: Int,
        val g: Int,
        val b: Int
    )

    data class ButtonFingerprint(
        val id: String,
        val ruleId: String,
        val screenW: Int,
        val screenH: Int,
        val xFrac: Float,
        val yFrac: Float,
        val boxWidthFrac: Float,
        val boxHeightFrac: Float,
        val samples: List<FingerprintSample>,
        val kind: ButtonKind,
        val probeMode: String = PROBE_GRID
    )

    /**
     * Постоянное хранение отпечатков в SharedPreferences (аналогично SmartLearnedStore).
     */
    class FingerprintStore(context: Context) {
        private val prefs: SharedPreferences =
            context.applicationContext.getSharedPreferences("smart_fingerprints", Context.MODE_PRIVATE)

        init {
            // Версия 2: старые отпечатки могли содержать ложный continue (кнопка «присесть» и т.п.).
            // Один раз стираем их, start и continue заново выучатся уже с проверкой.
            if (prefs.getInt("schema_version", 0) < 2) {
                prefs.edit().clear().putInt("schema_version", 2).apply()
            }
        }

        fun getStart(screenW: Int, screenH: Int): ButtonFingerprint? {
            val raw = prefs.getString("${screenW}x${screenH}:start", null) ?: return null
            return deserialize(raw)
        }

        fun saveStart(fp: ButtonFingerprint) {
            val json = serialize(fp)
            prefs.edit().putString("${fp.screenW}x${fp.screenH}:start", json).apply()
        }

        fun clearStart(screenW: Int, screenH: Int) {
            prefs.edit().remove("${screenW}x${screenH}:start").apply()
        }

        fun getContinues(screenW: Int, screenH: Int): List<ButtonFingerprint> {
            val raw = prefs.getString("${screenW}x${screenH}:continue_list", null) ?: return emptyList()
            return try {
                val array = JSONArray(raw)
                val list = mutableListOf<ButtonFingerprint>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    deserializeJson(item)?.let { list.add(it) }
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun saveContinue(fp: ButtonFingerprint) {
            val existing = getContinues(fp.screenW, fp.screenH).toMutableList()
            val newX = fp.xFrac * fp.screenW
            val newY = fp.yFrac * fp.screenH

            // Не сохранять новый, если центр ближе 40 px к существующему
            val tooClose = existing.any { ext ->
                val exX = ext.xFrac * ext.screenW
                val exY = ext.yFrac * ext.screenH
                hypot((newX - exX).toDouble(), (newY - exY).toDouble()) < 40.0
            }
            if (!tooClose) {
                existing.add(0, fp)
                // Хранить до 4 отпечатков continue
                val trimmed = existing.take(4)
                val array = JSONArray()
                trimmed.forEach { array.put(serializeJson(it)) }
                prefs.edit().putString("${fp.screenW}x${fp.screenH}:continue_list", array.toString()).apply()
            }
        }

        fun removeContinue(fp: ButtonFingerprint) {
            val remaining = getContinues(fp.screenW, fp.screenH).filter { it.id != fp.id }
            val array = JSONArray()
            remaining.forEach { array.put(serializeJson(it)) }
            prefs.edit().putString("${fp.screenW}x${fp.screenH}:continue_list", array.toString()).apply()
        }

        /** Запомнить «ложную» точку continue (например, кнопка в матче), чтобы больше не калибровать по ней. */
        fun addRejected(screenW: Int, screenH: Int, xFrac: Float, yFrac: Float, tag: String = "rejected") {
            val key = "${screenW}x${screenH}:$tag"
            val array = try {
                JSONArray(prefs.getString(key, null) ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
            val item = JSONArray()
            item.put(xFrac.toDouble())
            item.put(yFrac.toDouble())
            item.put(System.currentTimeMillis())
            array.put(item)
            // Хранить не больше 20 точек: самые старые выпадают
            val trimmed = JSONArray()
            val from = (array.length() - 20).coerceAtLeast(0)
            for (i in from until array.length()) trimmed.put(array.get(i))
            prefs.edit().putString(key, trimmed.toString()).apply()
        }

        fun isRejected(screenW: Int, screenH: Int, xFrac: Float, yFrac: Float, tag: String = "rejected"): Boolean {
            val raw = prefs.getString("${screenW}x${screenH}:$tag", null) ?: return false
            val now = System.currentTimeMillis()
            return try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.getJSONArray(i)
                    if (item.length() >= 3) {
                        val ts = item.optLong(2, 0L)
                        // Ложные точки устаревают через 15 минут, предотвращая вечное залипание координат
                        if (ts > 0L && now - ts > 15 * 60_000L) continue
                    }
                    val dx = (xFrac - item.getDouble(0)) * screenW
                    val dy = (yFrac - item.getDouble(1)) * screenH
                    if (hypot(dx, dy) < 60.0) return true
                }
                false
            } catch (_: Exception) {
                false
            }
        }

        fun count(screenW: Int, screenH: Int): Int {
            var c = if (getStart(screenW, screenH) != null) 1 else 0
            c += getContinues(screenW, screenH).size
            return c
        }

        fun clearAll() {
            prefs.edit().clear().apply()
        }

        private fun serialize(fp: ButtonFingerprint): String = serializeJson(fp).toString()

        private fun serializeJson(fp: ButtonFingerprint): JSONObject {
            val obj = JSONObject()
            obj.put("id", fp.id)
            obj.put("ruleId", fp.ruleId)
            obj.put("screenW", fp.screenW)
            obj.put("screenH", fp.screenH)
            obj.put("xFrac", fp.xFrac.toDouble())
            obj.put("yFrac", fp.yFrac.toDouble())
            obj.put("boxWidthFrac", fp.boxWidthFrac.toDouble())
            obj.put("boxHeightFrac", fp.boxHeightFrac.toDouble())
            obj.put("kind", fp.kind.name)
            obj.put("probeMode", fp.probeMode)

            val samplesArray = JSONArray()
            for (s in fp.samples) {
                val sObj = JSONObject()
                sObj.put("rx", s.relX.toDouble())
                sObj.put("ry", s.relY.toDouble())
                sObj.put("r", s.r)
                sObj.put("g", s.g)
                sObj.put("b", s.b)
                samplesArray.put(sObj)
            }
            obj.put("samples", samplesArray)
            return obj
        }

        private fun deserialize(raw: String): ButtonFingerprint? {
            return try {
                deserializeJson(JSONObject(raw))
            } catch (_: Exception) {
                null
            }
        }

        private fun deserializeJson(obj: JSONObject): ButtonFingerprint? {
            return try {
                val id = obj.getString("id")
                val ruleId = obj.getString("ruleId")
                val screenW = obj.getInt("screenW")
                val screenH = obj.getInt("screenH")
                val xFrac = obj.getDouble("xFrac").toFloat()
                val yFrac = obj.getDouble("yFrac").toFloat()
                val boxWidthFrac = obj.getDouble("boxWidthFrac").toFloat()
                val boxHeightFrac = obj.getDouble("boxHeightFrac").toFloat()
                val kind = ButtonKind.valueOf(obj.getString("kind"))
                val probeMode = obj.optString("probeMode", PROBE_GRID)

                val samplesArray = obj.getJSONArray("samples")
                val samples = mutableListOf<FingerprintSample>()
                for (i in 0 until samplesArray.length()) {
                    val sObj = samplesArray.getJSONObject(i)
                    samples.add(
                        FingerprintSample(
                            relX = sObj.getDouble("rx").toFloat(),
                            relY = sObj.getDouble("ry").toFloat(),
                            r = sObj.getInt("r"),
                            g = sObj.getInt("g"),
                            b = sObj.getInt("b")
                        )
                    )
                }
                ButtonFingerprint(id, ruleId, screenW, screenH, xFrac, yFrac, boxWidthFrac, boxHeightFrac, samples, kind, probeMode)
            } catch (_: Exception) {
                null
            }
        }
    }

    private val fingerprintStore = FingerprintStore(context)

    // Переиспользуемый буфер пикселей для ROI калибровки (исключает аллокации в цикле)
    private var roiPixelsBuffer = IntArray(800 * 500)

    // Состояние автомата
    private var state: State = State.UNKNOWN

    // Защита от дублей тапов: не тапать одну и ту же точку чаще раза в 250 мс
    private var lastTapX = -1f
    private var lastTapY = -1f
    private var lastTapTimeMs = 0L

    // Счётчики для LOBBY и RESULT
    private var lobbyStartMissCount = 0
    private var lastResultActionMs = 0L
    private var resultMissFrames = 0
    private var inMatchFrame = 0
    private var lastStartTapMs = 0L

    // Подтверждение нажатий
    private var startPending = false
    private var startTapCount = 0
    private var startGoneFrames = 0

    private var continuePending = false
    private var continueTapCount = 0
    private var continueLastTapMs = 0L
    private var continueGoneFrames = 0
    private var continueSeenFrames = 0
    private var continueBlockedUntilMs = 0L
    private var freshContinueId: String? = null

    private var pendingCalib: ButtonFingerprint? = null
    private var pendingCalibFrames = 0

    // Новые отпечатки попадают в хранилище только после подтверждения, что тап сработал
    private var provisionalStart: ButtonFingerprint? = null
    private var startTapBox: IntArray? = null // [left, top, right, bottom] кнопки в момент тапа
    private var provisionalContinue: ButtonFingerprint? = null

    // Чтение счёта по цифрам и «ворота конца матча»
    private val scoreReader = ScoreReader()
    private val endgameGate = EndgameGate()
    private var lastResyncLogged = 0

    private var scoreOcrFailures = 0
    private var scoreOcrDisabledUntilMs = 0L
    private val scoreOcrDisabled: Boolean
        get() = scoreOcrDisabledUntilMs > System.currentTimeMillis()
    private var lastScoreReadMs = 0L
    private var lastScoreLogMs = 0L
    private var lastOcrErrorLogMs = 0L
    private var lastScoreUiText = ""
    private var waitingSinceMs = 0L
    private var waitingFrame = 0

    // Кэш всех отпечатков continue (изученные + встроенные seed), сбрасывается при сохранении
    private var cachedAllContinues: List<ButtonFingerprint>? = null
    private var cachedAllContinuesKey = 0L

    // Диагностика кандидатов continue (для журнала раз в 5 сек)
    private var lastContinueDiagMs = 0L
    private var lastPaleInfo = "нет данных"
    private var lastYellowInfo = "нет данных"

    // Логирование и статистика (без спама, раз в 5 сек)
    private var lastSummaryLogMs = 0L
    private var lastCalibRejectLogMs = 0L
    private var lastSmallRoiLogMs = 0L
    private var shotTimeSum = 0L
    private var procTimeSum = 0L
    private var framesCount = 0

    private fun transitionTo(newState: State) {
        if (state != newState) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "FAST: $state -> $newState")
            state = newState
            resetTapTracking()
            when (newState) {
                State.UNKNOWN -> onActionUpdate("Определение экрана...", "Поиск кнопок", null)
                State.WAITING -> {
                    scoreReader.resetTracker()
                    waitingSinceMs = System.currentTimeMillis()
                    waitingFrame = 0
                    onActionUpdate("Ожидание лобби", "Поиск кнопки НАЧАТЬ", null)
                }
                State.LOBBY -> {
                    scoreReader.resetTracker()
                    scoreOcrFailures = 0
                    scoreOcrDisabledUntilMs = 0L
                    onActionUpdate("В лобби: ожидание старта", "Поиск кнопки НАЧАТЬ", null)
                }
                State.IN_MATCH -> {
                    startMatchGate()
                    val need = endgameGate.lastKnownTarget - EndgameGate.ARM_MARGIN
                    onActionUpdate("Матч идёт: пауза кликов", "Ожидание счёта $need+", null)
                }
                State.RESULT -> {
                    scoreReader.resetTracker()
                    lastResultActionMs = System.currentTimeMillis()
                    resultMissFrames = 0
                    onActionUpdate("Результаты матча", "Нажатие ПРОДОЛЖИТЬ", null)
                }
            }
        }
    }

    /**
     * Запуск быстрого цикла.
     */
    suspend fun runLoop(
        service: AutoClickAccessibilityService,
        screenW: Int,
        screenH: Int,
        config: EffectiveConfig
    ) {
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: быстрый конвейер запущен на экране ${screenW}x${screenH}, отпечатков в базе: ${fingerprintStore.count(screenW, screenH)}"
        )
        transitionTo(State.UNKNOWN)

        while (true) {
            val loopStart = System.currentTimeMillis()

            // Интервал снимков зависит от состояния:
            // IN_MATCH: опрос раз в 1200 мс (тапов в игру нет, только проверка кнопок)
            // LOBBY: опрос каждые ~350 мс
            // RESULT: опрос каждые ~300-350 мс
            val interval = when (state) {
                State.IN_MATCH -> when {
                    endgameGate.isArmed -> IN_MATCH_ARMED_INTERVAL_MS
                    scoreReader.tracker.hasPendingConflict -> SCORE_CONFLICT_INTERVAL_MS
                    else -> IN_MATCH_IDLE_INTERVAL_MS
                }
                State.LOBBY -> 350L
                State.RESULT -> 350L
                State.UNKNOWN -> 350L
                State.WAITING -> 350L
            }

            val shotStart = System.currentTimeMillis()
            var screenshot = service.takeScreenshotBitmap(minIntervalMs = interval)
            if (screenshot == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                delay(100L)
                screenshot = service.takeScreenshotBitmap(minIntervalMs = 350L)
            }
            val shotMs = System.currentTimeMillis() - shotStart

            if (screenshot == null || screenshot.isRecycled) {
                delay(200L)
                continue
            }

            var bmpToRecycle: Bitmap? = screenshot
            val procStart = System.currentTimeMillis()

            try {
                // Если Bitmap формата HARDWARE, копируем в ARGB_8888 один раз
                val workingBmp = if (screenshot.config == Bitmap.Config.HARDWARE) {
                    val copy = screenshot.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.recycle()
                    bmpToRecycle = copy
                    copy
                } else {
                    screenshot
                }

                if (workingBmp == null || workingBmp.isRecycled) continue

                val sW = workingBmp.width
                val sH = workingBmp.height

                // Сохранение отладочного скриншота только если включено
                val settings = settingsRepository.getLatestSettings()
                if (settings.isDebugScreenshots) {
                    // Сохранение при необходимости
                }

                // Логика машины состояний
                processFrame(workingBmp, sW, sH, config, shotStart)

                val procMs = System.currentTimeMillis() - procStart

                // Сводка раз в 5 секунд
                shotTimeSum += shotMs
                procTimeSum += procMs
                framesCount++
                val now = System.currentTimeMillis()
                if (now - lastSummaryLogMs >= 5000L) {
                    lastSummaryLogMs = now
                    val avgShot = if (framesCount > 0) shotTimeSum / framesCount else 0L
                    val avgProc = if (framesCount > 0) procTimeSum / framesCount else 0L
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "FAST: состояние $state, средняя длительность: снимок ${avgShot}мс, обработка ${avgProc}мс, отпечатков: ${fingerprintStore.count(sW, sH)}"
                    )
                    shotTimeSum = 0L
                    procTimeSum = 0L
                    framesCount = 0
                }

                // Планирование следующего снимка: max(0, interval - потраченное время)
                val totalSpent = System.currentTimeMillis() - loopStart
                val nextWait = maxOf(0L, interval - totalSpent)
                if (nextWait > 0) {
                    delay(nextWait)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: ошибка в обработке кадра: ${t.javaClass.simpleName}: ${t.message}",
                    isError = true
                )
                delay(300L)
            } finally {
                try {
                    bmpToRecycle?.recycle()
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun processFrame(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        when (state) {
            State.UNKNOWN -> handleUnknownState(bitmap, sW, sH, config, shotStart)
            State.WAITING -> handleWaitingState(bitmap, sW, sH, config, shotStart)
            State.LOBBY -> handleLobbyState(bitmap, sW, sH, config, shotStart)
            State.IN_MATCH -> handleInMatchState(bitmap, sW, sH, config, shotStart)
            State.RESULT -> handleResultState(bitmap, sW, sH, config, shotStart)
        }
    }

    private suspend fun handleUnknownState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val startFp = provisionalStart ?: fingerprintStore.getStart(sW, sH)

        if (startFp != null && verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID) {
            transitionTo(State.LOBBY)
            return
        }

        // Панель счёта видна: матч уже идёт (например, автокликер включили посреди матча)
        if (enterMatchIfScoreVisible(bitmap, sW, sH)) return

        // Экран результатов (изученные и встроенные seed-отпечатки continue): тап только после подтверждения в RESULT
        if (findContinueHit(bitmap, sW, sH) != null) {
            transitionTo(State.RESULT)
            continueSeenFrames = 1
            return
        }

        // Калибровка start: кандидат должен подтвердиться на двух кадрах подряд
        if (startFp == null) {
            val cand = calibrateStart(bitmap, sW, sH)
            val confirmed = confirmCalibration(cand)
            if (confirmed != null) {
                provisionalStart = confirmed // в хранилище попадёт только после подтверждённого запуска подбора
                transitionTo(State.LOBBY)
                return
            }
            if (cand != null) return // ждём подтверждения на следующем кадре
        }

        // Ничего не найдено: загрузка игры, заставка, переход между экранами. Это НЕ матч.
        transitionTo(State.WAITING)
    }

    /**
     * Ожидание: экран неизвестен (загрузка игры, нет кнопок). Быстрый опрос, ищем лобби,
     * панель счёта (значит идёт матч) или экран результатов.
     */
    private suspend fun handleWaitingState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val now = System.currentTimeMillis()
        waitingFrame++

        val startFp = provisionalStart ?: fingerprintStore.getStart(sW, sH)
        if (startFp != null) {
            if (verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID) {
                transitionTo(State.LOBBY)
                return
            }
        } else {
            val cand = calibrateStart(bitmap, sW, sH)
            val confirmed = confirmCalibration(cand)
            if (confirmed != null) {
                provisionalStart = confirmed
                transitionTo(State.LOBBY)
                return
            }
        }

        val hit = findContinueHit(bitmap, sW, sH)
        // Счёт читаем раз в 3 кадра, а при совпадении continue сразу: это может быть кнопка «присесть» в матче
        if (hit != null || waitingFrame % 3 == 0) {
            if (enterMatchIfScoreVisible(bitmap, sW, sH)) return
        }
        if (hit != null) {
            transitionTo(State.RESULT)
            continueSeenFrames = 1
            return
        }

        val limit = if (scoreOcrDisabled) WAITING_FALLBACK_NO_OCR_MS else WAITING_FALLBACK_MS
        if (now - waitingSinceMs > limit) {
            if (isLobbyStartVisible(bitmap, sW, sH)) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: ожидание затянулось (${(now - waitingSinceMs) / 1000}с), видна НАЧАТЬ -> LOBBY"
                )
                transitionTo(State.LOBBY)
            } else {
                // Не форсируем матч по таймауту — пусть score OCR решит, иначе остаёмся в WAITING
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: ожидание затянулось (${(now - waitingSinceMs) / 1000}с), НАЧАТЬ нет — проверяю счёт, иначе жду дальше"
                )
                if (!enterMatchIfScoreVisible(bitmap, sW, sH)) {
                    // сброс таймера, чтобы не спамить лог каждую секунду
                    waitingSinceMs = now - limit / 2
                }
            }
        }
    }

    private suspend fun handleLobbyState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val stored = fingerprintStore.getStart(sW, sH)
        val startFp = provisionalStart ?: stored
        val now = System.currentTimeMillis()
        if (startFp != null) {
            val matched = verifyFingerprint(bitmap, startFp)
            if (matched >= REQUIRED_GRID) {
                lobbyStartMissCount = 0
                startGoneFrames = 0
                if (startPending) {
                    // Кнопка всё ещё на экране после тапа: ждём реакции игры
                    // (слабый интернет / планшет «не проснулся»), потом повторяем.
                    val retryDelay = if (startTapCount >= 4) START_RETRY_MS * 2 else START_RETRY_MS
                    if (now - lastStartTapMs < retryDelay) return
                    if (provisionalStart != null && startTapCount >= PROVISIONAL_MAX_TAPS) {
                        discardProvisionalStart("кнопка не реагирует на $startTapCount тапа")
                        return
                    }
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "FAST: start всё ещё виден через ${now - lastStartTapMs}мс после тапа -> повтор",
                        isError = startTapCount >= 3
                    )
                }
                val src = if (provisionalStart != null) "калибровка_цвет" else "отпечаток"
                tapStart(startFp, "$matched/${startFp.samples.size}", src, shotStart, sW, sH)
                return
            } else {
                if (startPending) {
                    // Кнопка не совпала с отпечатком. Это успех, только если на её месте больше нет жёлтого
                    // (появилась серая панель «Подбор»). Если жёлтое осталось, тап попал не туда.
                    val box = startTapBox
                    val yellow = if (box != null) yellowRatio(bitmap, box) else 0f
                    if (yellow >= START_GONE_YELLOW_MAX) {
                        startGoneFrames = 0
                        if (now - lastStartTapMs > START_VERIFY_MS) {
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "FAST: start не подтверждён: на месте кнопки жёлтого ${(yellow * 100).toInt()}% через ${now - lastStartTapMs}мс",
                                isError = true
                            )
                            if (provisionalStart != null) {
                                discardProvisionalStart("после тапа цвет на месте кнопки не сменился")
                            } else {
                                startPending = false
                                startTapCount = 0
                            }
                        }
                        return
                    }
                    startGoneFrames++
                    if (startGoneFrames >= CONFIRM_GONE_FRAMES) {
                        confirmStart(bitmap, sW, sH)
                    }
                    return
                }

                // Если вместо лобби уже экран результатов (или идёт матч): решаем по панели счёта
                if (findContinueHit(bitmap, sW, sH) != null) {
                    if (enterMatchIfScoreVisible(bitmap, sW, sH)) return
                    transitionTo(State.RESULT)
                    continueSeenFrames = 1
                    return
                }

                lobbyStartMissCount++
                if (lobbyStartMissCount >= 5) {
                    // Отпечаток start не совпал 5 раз подряд: перекалибровать по цвету (но сохранить только после подтверждения)
                    lobbyStartMissCount = 0
                    val cand = calibrateStart(bitmap, sW, sH)
                    if (cand != null) {
                        val oldX = startFp.xFrac * sW
                        val oldY = startFp.yFrac * sH
                        val dist = hypot((cand.xFrac * sW - oldX).toDouble(), (cand.yFrac * sH - oldY).toDouble())
                        provisionalStart = cand
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "FAST: start перекалиброван на ${(cand.xFrac * sW).toInt()},${(cand.yFrac * sH).toInt()} (сдвиг ${dist.toInt()} px), сохраню после подтверждения"
                        )
                    }
                }
            }
        } else {
            // Отпечатка start нет: калибровка с подтверждением на 2 кадрах
            val cand = calibrateStart(bitmap, sW, sH)
            val confirmed = confirmCalibration(cand)
            if (confirmed != null) {
                provisionalStart = confirmed
                tapStart(confirmed, "${confirmed.samples.size}/${confirmed.samples.size}", "калибровка_цвет", shotStart, sW, sH)
                return
            }
            if (cand != null) return

            if (templateFallback != null) {
                // Запасной вариант: поиск по шаблону только при калибровке
                val rule = config.rules.firstOrNull { it.id == "start" }
                if (rule != null) {
                    val fallbackCoords = templateFallback.invoke(bitmap, rule)
                    if (fallbackCoords != null) {
                        val (clickX, clickY) = fallbackCoords
                        val fp = buildFingerprint(bitmap, "start", clickX, clickY, (sH * 0.31f).toInt(), (sH * 0.12f).toInt(), ButtonKind.YELLOW)
                        provisionalStart = fp
                        tapStart(fp, "${fp.samples.size}/${fp.samples.size}", "шаблон", shotStart, sW, sH)
                        return
                    }
                }
            }
        }
    }

    /** Запуск подбора подтверждён: кнопка исчезла и цвет на её месте сменился. */
    private suspend fun confirmStart(bitmap: Bitmap, sW: Int, sH: Int) {
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: start подтверждён: жёлтая НАЧАТЬ сменилась (панель «Команда X/Y») после $startTapCount тап(ов)"
        )
        provisionalStart?.let {
            fingerprintStore.saveStart(it)
            provisionalStart = null
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "FAST: отпечаток start сохранён (подтверждён запуском подбора)"
            )
        }
        // Справочно для журнала: что написано на месте кнопки (например «00:07 Команда 3/4»)
        val box = startTapBox
        if (box != null && !scoreOcrDisabled) {
            try {
                val text = scoreReader.readText(
                    bitmap,
                    box[0].toFloat() / sW, box[1].toFloat() / sH,
                    box[2].toFloat() / sW, box[3].toFloat() / sH
                )
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: на месте кнопки start теперь: «${text.take(60)}»"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
        onCycleIncrement()
        transitionTo(State.IN_MATCH)
    }

    private fun discardProvisionalStart(reason: String) {
        val fp = provisionalStart ?: return
        fingerprintStore.addRejected(fp.screenW, fp.screenH, fp.xFrac, fp.yFrac, REJECT_START)
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: точка start ${(fp.xFrac * fp.screenW).toInt()},${(fp.yFrac * fp.screenH).toInt()} отброшена: $reason",
            isError = true
        )
        provisionalStart = null
        startPending = false
        startTapCount = 0
        startGoneFrames = 0
        lobbyStartMissCount = 0
    }

    private suspend fun handleInMatchState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val now = System.currentTimeMillis()

        // 1. Снова видна кнопка start / жёлтая НАЧАТЬ (подбор отменили / вернулись в лобби)
        if (now - lastStartTapMs > IN_MATCH_START_RECHECK_MS && isLobbyStartVisible(bitmap, sW, sH)) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "FAST: в IN_MATCH снова видна жёлтая НАЧАТЬ -> LOBBY"
            )
            transitionTo(State.LOBBY)
            return
        }

        // 2. Обновляем ворота конца матча (чтение счёта раз в ~1 с, не пропускаем даже когда armed)
        updateEndgameGate(bitmap, now)

        val mode = endgameGate.mode
        // 3. Пока счёт далёк от конца (OFF), кнопки «Продолжить» НЕ ищем вообще
        if (mode == EndgameMode.OFF) {
            return
        }

        // 4. Поиск кнопки «Продолжить»:
        // SOFT: ТОЛЬКО по уже сохранённым и seed-отпечаткам, БЕЗ калибровки по цвету (кнопка «присесть»)
        // HARD: по отпечаткам + калибровка по цвету
        val hit = findContinueHit(bitmap, sW, sH)
        val calib = if (mode == EndgameMode.HARD) calibrateContinue(bitmap, sW, sH) else null

        if (hit != null || calib != null) {
            transitionTo(State.RESULT)
            continueSeenFrames = 1
            return
        }

        logContinueDiag(bitmap, sW, sH)
    }

    private suspend fun handleResultState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val now = System.currentTimeMillis()

        if (now >= continueBlockedUntilMs) {
            val hit = findContinueHit(bitmap, sW, sH)
            if (hit != null) {
                resultMissFrames = 0
                continueGoneFrames = 0
                lastResultActionMs = now
                continueSeenFrames++
                // Тапаем только если кнопка видна на 2 кадрах подряд
                if (continueSeenFrames < CONTINUE_CONFIRM_FRAMES) return
                // После тапа даём игре время отреагировать (слабый интернет)
                if (continuePending && now - continueLastTapMs < CONTINUE_RETRY_MS) return

                val isSeed = hit.fp.id.startsWith("seed_")
                val limit = if (hit.fp.id == freshContinueId) FRESH_CONTINUE_TAPS else MAX_CONTINUE_TAPS
                if (continueTapCount >= limit) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "FAST: continue ${hit.fp.id} не исчезает после $continueTapCount тапов — " +
                            (if (isSeed) "пауза" else "отпечаток удалён (похоже на ложный), пауза") +
                            " ${CONTINUE_BLOCK_MS / 1000}с",
                        isError = true
                    )
                    if (!isSeed) {
                        fingerprintStore.removeContinue(hit.fp)
                        fingerprintStore.addRejected(sW, sH, hit.fp.xFrac, hit.fp.yFrac)
                        cachedAllContinues = null
                    }
                    if (hit.fp === provisionalContinue) provisionalContinue = null
                    freshContinueId = null
                    continueBlockedUntilMs = now + CONTINUE_BLOCK_MS
                    continuePending = false
                    continueTapCount = 0
                    continueSeenFrames = 0
                    return
                }
                val src = if (isSeed) "seed" else "отпечаток"
                tapContinueAt(hit.fp, "${hit.matched}/${hit.fp.samples.size}", src, sW, sH, shotStart)
                return
            }

            if (continuePending) {
                // Кнопка пропала: подтверждаем после 2 кадров подряд
                continueGoneFrames++
                if (continueGoneFrames >= CONFIRM_GONE_FRAMES) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "FAST: continue подтверждён: кнопка исчезла после $continueTapCount тап(ов)"
                    )
                    // Новый отпечаток запоминаем только теперь, когда тап реально убрал кнопку
                    provisionalContinue?.let {
                        saveContinueFp(it)
                        provisionalContinue = null
                    }
                    continuePending = false
                    continueTapCount = 0
                    continueGoneFrames = 0
                    continueSeenFrames = 0
                    freshContinueId = null
                    lastResultActionMs = now
                }
                return
            }

            // Новая позиция/цвет кнопки: калибровка по цвету с подтверждением на 2 кадрах
            val cand = calibrateContinue(bitmap, sW, sH)
            val confirmed = confirmCalibration(cand)
            if (confirmed != null) {
                provisionalContinue = confirmed
                freshContinueId = confirmed.id
                tapContinueAt(confirmed, "${confirmed.samples.size}/${confirmed.samples.size}", "цвет", sW, sH, shotStart)
                return
            }
            if (cand != null) {
                lastResultActionMs = now
                return
            }
        }

        resultMissFrames++

        // Continue не видно: если видна кнопка start, значит вернулись в лобби
        val startFp = provisionalStart ?: fingerprintStore.getStart(sW, sH)
        if (startFp != null && verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID) {
            transitionTo(State.LOBBY)
            return
        }

        // Между экранами результатов бывает пауза на анимацию, поэтому выходим не сразу.
        // Выходим в WAITING (быстрый опрос), а не в «матч идёт».
        if (resultMissFrames >= 2 && System.currentTimeMillis() - lastResultActionMs > RESULT_EXIT_MS) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "FAST: в RESULT кнопок не видно -> WAITING"
            )
            transitionTo(State.WAITING)
            return
        }

        logContinueDiag(bitmap, sW, sH)
    }

    // ---------------------------------------------------------------------------------------
    //  Чтение счёта по цифрам и «ворота конца матча»
    // ---------------------------------------------------------------------------------------

    data class ReadScoreResult(val score: MatchScore?, val exceptionOccurred: Boolean)

    /** Читает счёт; результат null без исключения — это нормальное чтение без панели счёта. */
    private suspend fun readScoreSafe(bitmap: Bitmap): ReadScoreResult {
        val now = System.currentTimeMillis()
        if (scoreOcrDisabledUntilMs > now) {
            return ReadScoreResult(null, exceptionOccurred = true)
        }
        return try {
            val s = scoreReader.read(bitmap)
            scoreOcrFailures = 0
            val tr = scoreReader.tracker
            if (tr.resyncCount != lastResyncLogged) {
                lastResyncLogged = tr.resyncCount
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SCORE: счёт сам исправлен (${tr.lastResyncInfo}), всего исправлений: ${tr.resyncCount}"
                )
            }
            val isDebug = settingsRepository.getLatestSettings().isDebugScreenshots
            if (isDebug) {
                scoreReader.maybeSaveDebugCrop(
                    context = context,
                    screenBitmap = bitmap,
                    score = s,
                    isDebugEnabled = true,
                    now = now
                )
            }
            ReadScoreResult(s, exceptionOccurred = false)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            scoreOcrFailures++
            val isDebug = settingsRepository.getLatestSettings().isDebugScreenshots
            if (isDebug) {
                scoreReader.maybeSaveDebugCrop(
                    context = context,
                    screenBitmap = bitmap,
                    score = null,
                    isDebugEnabled = true,
                    now = now
                )
            }
            if (now - lastOcrErrorLogMs >= 5000L) {
                lastOcrErrorLogMs = now
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SCORE: ошибка OCR (${scoreOcrFailures}/$OCR_FAIL_LIMIT): ${t.javaClass.simpleName}: ${t.message}",
                    isError = true
                )
            }
            if (scoreOcrFailures >= OCR_FAIL_LIMIT) {
                scoreOcrDisabledUntilMs = now + OCR_DISABLED_COOLDOWN_MS
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SCORE: OCR временно отключён на ${OCR_DISABLED_COOLDOWN_MS / 1000}с из-за ошибок ($scoreOcrFailures подряд)",
                    isError = true
                )
            }
            ReadScoreResult(null, exceptionOccurred = true)
        }
    }

    /**
     * Переход в IN_MATCH по панели счёта.
     * ВАЖНО: из лобби (пока видна жёлтая НАЧАТЬ) переход ЗАПРЕЩЁН.
     * Основной путь в матч — confirmStart() после смены кнопки на «Команда X/Y».
     * Этот путь только для случая «включили бота уже посреди катки».
     */
    private suspend fun enterMatchIfScoreVisible(bitmap: Bitmap, sW: Int, sH: Int): Boolean {
        // Пока видна жёлтая НАЧАТЬ — это лобби, счёт не смотрим
        if (isLobbyStartVisible(bitmap, sW, sH)) {
            return false
        }

        val (s, exceptionOccurred) = readScoreSafe(bitmap)
        if (exceptionOccurred || s == null) return false

        // Нужны оба числа счёта (настоящая панель матча), одного мало
        val hasBothSides = s.left != null && s.right != null
        if (!hasBothSides) {
            return false
        }
        // Разумные значения TDM
        val l = s.left!!
        val r = s.right!!
        if (l < 0 || r < 0 || l > 80 || r > 80) return false

        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "SCORE: настоящая панель счёта $s (НАЧАТЬ не видна) -> IN_MATCH"
        )
        transitionTo(State.IN_MATCH)
        endgameGate.onScore(s, System.currentTimeMillis())
        logScore(s, System.currentTimeMillis())
        return true
    }

    private fun logScore(s: MatchScore, now: Long) {
        val need = endgameGate.lastKnownTarget - EndgameGate.ARM_MARGIN
        if (now - lastScoreLogMs >= 15_000L) {
            lastScoreLogMs = now
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "SCORE: счёт $s, порог $need, OCR: «${scoreReader.lastRawText.take(60)}»"
            )
        }
        if (endgameGate.mode == EndgameMode.OFF) {
            val ui = "Счёт ${s.left ?: "?"}:${s.right ?: "?"}, ждём ${need}+"
            if (ui != lastScoreUiText) {
                lastScoreUiText = ui
                onActionUpdate("Матч идёт: пауза кликов", ui, null)
            }
        }
    }

    private fun checkEndgameModeTransition(prevMode: EndgameMode, now: Long) {
        val newMode = endgameGate.mode
        if (newMode != prevMode) {
            when (newMode) {
                EndgameMode.HARD -> {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SCORE: конец матча подтверждён [HARD] (${endgameGate.getArmReasonText(now)}) -> полный поиск ПРОДОЛЖИТЬ с калибровкой"
                    )
                    onActionUpdate("Конец матча: поиск ПРОДОЛЖИТЬ (HARD)", "Нажатие ПРОДОЛЖИТЬ", null)
                }
                EndgameMode.SOFT -> {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SCORE: подозрение на конец матча [SOFT] (${endgameGate.getArmReasonText(now)}) -> поиск ПРОДОЛЖИТЬ только по отпечаткам"
                    )
                    onActionUpdate("Конец матча близок: поиск ПРОДОЛЖИТЬ (SOFT)", "Нажатие ПРОДОЛЖИТЬ", null)
                }
                EndgameMode.OFF -> {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SCORE: режим конца матча отменён [OFF]: ${endgameGate.lastDisarmReason}"
                    )
                    val s = endgameGate.lastScore
                    val need = endgameGate.lastKnownTarget - EndgameGate.ARM_MARGIN
                    val ui = if (s != null) "Счёт ${s.left ?: "?"}:${s.right ?: "?"}, ждём ${need}+" else "Матч идёт: пауза кликов"
                    onActionUpdate("Матч идёт: пауза кликов", ui, null)
                    lastScoreUiText = ui
                }
            }
        }
    }

    /** Решает, пора ли искать «Продолжить». Чтение счёта раз в ~1 с (не пропускаем даже когда armed). */
    private suspend fun updateEndgameGate(bitmap: Bitmap, now: Long) {
        val prevMode = endgameGate.mode

        // Опрос OCR раз в ~1 с
        val readPeriod = if (scoreReader.tracker.hasPendingConflict) SCORE_CONFLICT_READ_PERIOD_MS else SCORE_READ_PERIOD_MS
        if (now - lastScoreReadMs < readPeriod) {
            endgameGate.onTimeTick(now)
            checkEndgameModeTransition(prevMode, now)
            return
        }
        lastScoreReadMs = now

        if (scoreOcrDisabled) {
            endgameGate.onOcrUnavailable(now)
        } else {
            val (s, exceptionOccurred) = readScoreSafe(bitmap)
            if (exceptionOccurred) {
                if (scoreOcrDisabled) {
                    endgameGate.onOcrUnavailable(now)
                }
            } else if (s != null) {
                s.timerSeconds?.let { endgameGate.onTimer(it, now) }
                endgameGate.onScore(s, now)
                logScore(s, now)
            } else {
                endgameGate.onEmptyRead(now)
            }
        }

        checkEndgameModeTransition(prevMode, now)
    }

    private fun startMatchGate() {
        val now = System.currentTimeMillis()
        endgameGate.startMatch(now)
        scoreReader.resetTracker()
        scoreOcrFailures = 0
        scoreOcrDisabledUntilMs = 0L
        lastScoreReadMs = 0L
        lastScoreUiText = ""
    }

    /** Доля жёлтых пикселей в прямоугольнике [left, top, right, bottom]. */
    private fun yellowRatio(bitmap: Bitmap, box: IntArray): Float {
        val left = box[0].coerceIn(0, bitmap.width - 1)
        val top = box[1].coerceIn(0, bitmap.height - 1)
        val right = box[2].coerceIn(left + 1, bitmap.width)
        val bottom = box[3].coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < 4 || h < 4) return 0f
        val step = maxOf(3, minOf(w, h) / 12)
        var total = 0
        var yellow = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val p = bitmap.getPixel(x, y)
                if (ColorBlobFinder.isYellowHsv((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) yellow++
                total++
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else yellow.toFloat() / total
    }

    /**
     * Надёжная проверка: в зоне кнопки «НАЧАТЬ» много жёлтого цвета.
     * Работает даже без сохранённого отпечатка и при небольшом изменении освещения.
     * Порог 0.18 — кнопка занимает заметную часть ROI.
     */
    private fun isLobbyStartVisible(bitmap: Bitmap, sW: Int, sH: Int): Boolean {
        val x0 = (START_ROI[0] * sW).toInt().coerceIn(0, sW - 1)
        val x1 = (START_ROI[1] * sW).toInt().coerceIn(x0 + 1, sW)
        val y0 = (START_ROI[2] * sH).toInt().coerceIn(0, sH - 1)
        val y1 = (START_ROI[3] * sH).toInt().coerceIn(y0 + 1, sH)
        val ratio = yellowRatio(bitmap, intArrayOf(x0, y0, x1, y1))
        if (ratio >= 0.18f) return true
        // Дополнительно: если есть отпечаток и он совпал — тоже лобби
        val startFp = provisionalStart ?: fingerprintStore.getStart(sW, sH)
        if (startFp != null && verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID) return true
        return false
    }

    private fun calibrateStart(bitmap: Bitmap, sW: Int, sH: Int): ButtonFingerprint? {
        val cand = calibrateColor(bitmap, sW, sH, "start", START_ROI, ButtonKind.YELLOW) ?: return null
        // Точки, по которым start уже не сработал, больше не берём
        if (fingerprintStore.isRejected(sW, sH, cand.xFrac, cand.yFrac, REJECT_START)) return null
        return cand
    }

    private class ContinueHit(val fp: ButtonFingerprint, val matched: Int)

    /**
     * Проверка всех отпечатков continue (изученные + встроенные seed). Возвращает лучший.
     */
    private fun findContinueHit(bitmap: Bitmap, sW: Int, sH: Int): ContinueHit? {
        val fps = allContinueFps(sW, sH)
        val prov = provisionalContinue
        var best: ContinueHit? = null
        for (fp in (if (prov != null) fps + prov else fps)) {
            val m = verifyFingerprint(bitmap, fp)
            if (m >= requiredMatches(fp)) {
                val cur = best
                if (cur == null || m > cur.matched) {
                    best = ContinueHit(fp, m)
                }
            }
        }
        return best
    }

    /** Нажатие «НАЧАТЬ»: подтверждение (кнопка исчезла и цвет сменился) выполняется в handleLobbyState. */
    private suspend fun tapStart(
        fp: ButtonFingerprint,
        probes: String,
        source: String,
        shotStart: Long,
        sW: Int,
        sH: Int
    ) {
        val x = fp.xFrac * sW
        val y = fp.yFrac * sH
        val now = System.currentTimeMillis()
        startTapCount++
        startPending = true
        startGoneFrames = 0
        val halfW = fp.boxWidthFrac * sW / 2f
        val halfH = fp.boxHeightFrac * sH / 2f
        startTapBox = intArrayOf(
            (x - halfW).toInt().coerceAtLeast(0),
            (y - halfH).toInt().coerceAtLeast(0),
            (x + halfW).toInt().coerceAtMost(sW - 1),
            (y + halfH).toInt().coerceAtMost(sH - 1)
        )
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: тап start ${x.toInt()},${y.toInt()} проб $probes попытка $startTapCount источник=$source снимок->тап ${now - shotStart}мс"
        )
        onActionUpdate("Нажатие: НАЧАТЬ", "Проверка: кнопка должна смениться на «Подбор»", "НАЖАТИЕ: start (${x.toInt()}, ${y.toInt()})")
        val ok = performSafeTap(x, y)
        lastStartTapMs = System.currentTimeMillis()
        if (!ok) {
            // Система отменила жест: тап не состоялся, повторяем скоро и не считаем попыткой
            startTapCount = (startTapCount - 1).coerceAtLeast(0)
            lastStartTapMs = lastStartTapMs - START_RETRY_MS + 200L
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "FAST: тап start не выполнен системой, повтор", isError = true)
        }
    }

    /** Нажатие «ПРОДОЛЖИТЬ»: подтверждение (кнопка исчезла) выполняется в handleResultState. */
    private suspend fun tapContinueAt(
        fp: ButtonFingerprint,
        probes: String,
        source: String,
        sW: Int,
        sH: Int,
        shotStart: Long
    ) {
        val tapX = fp.xFrac * sW
        val tapY = fp.yFrac * sH
        val now = System.currentTimeMillis()
        continueTapCount++
        continuePending = true
        continueGoneFrames = 0
        continueLastTapMs = now
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: тап continue ${tapX.toInt()},${tapY.toInt()} проб $probes попытка $continueTapCount источник=$source снимок->тап ${now - shotStart}мс"
        )
        onActionUpdate("Нажатие: ПРОДОЛЖИТЬ", "Проверка: кнопка должна исчезнуть", "НАЖАТИЕ: continue (${tapX.toInt()}, ${tapY.toInt()})")
        val ok = performSafeTap(tapX, tapY)
        lastResultActionMs = System.currentTimeMillis()
        resultMissFrames = 0
        if (!ok) {
            // Жест отменён системой: это не нажатие, повторяем скоро и не считаем попыткой
            continueTapCount = (continueTapCount - 1).coerceAtLeast(0)
            continueLastTapMs = lastResultActionMs - CONTINUE_RETRY_MS + 200L
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "FAST: тап continue не выполнен системой, повтор", isError = true)
        }
    }

    /**
     * Подтверждение калибровки: кандидат должен повториться в той же точке
     * на CALIBRATION_CONFIRM_FRAMES кадрах подряд, иначе его не запоминаем и не тапаем.
     */
    private fun confirmCalibration(candidate: ButtonFingerprint?): ButtonFingerprint? {
        if (candidate == null) {
            pendingCalib = null
            pendingCalibFrames = 0
            return null
        }
        val prev = pendingCalib
        val sameSpot = prev != null && prev.ruleId == candidate.ruleId &&
            hypot(
                ((candidate.xFrac - prev.xFrac) * candidate.screenW).toDouble(),
                ((candidate.yFrac - prev.yFrac) * candidate.screenH).toDouble()
            ) < 40.0
        if (sameSpot) {
            pendingCalibFrames++
        } else {
            pendingCalib = candidate
            pendingCalibFrames = 1
        }
        return if (pendingCalibFrames >= CALIBRATION_CONFIRM_FRAMES) {
            pendingCalib = null
            pendingCalibFrames = 0
            candidate
        } else null
    }

    private fun resetTapTracking() {
        startPending = false
        startTapCount = 0
        startGoneFrames = 0
        provisionalStart = null
        continuePending = false
        continueTapCount = 0
        continueGoneFrames = 0
        continueSeenFrames = 0
        continueBlockedUntilMs = 0L
        pendingCalib = null
        pendingCalibFrames = 0
        startTapBox = null
        provisionalContinue = null
    }

    private fun calibrateContinue(bitmap: Bitmap, sW: Int, sH: Int): ButtonFingerprint? {
        val cand = calibrateColor(bitmap, sW, sH, "continue_blue", CONTINUE_ROI, ButtonKind.BLUE)
            ?: calibrateColor(bitmap, sW, sH, "continue_mvp", CONTINUE_ROI, ButtonKind.YELLOW)
            ?: return null
        // Точки, которые уже оказались ложными (тап не убрал «кнопку»), больше не берём
        if (fingerprintStore.isRejected(sW, sH, cand.xFrac, cand.yFrac)) return null
        return cand
    }

    private fun saveContinueFp(fp: ButtonFingerprint) {
        fingerprintStore.saveContinue(fp)
        cachedAllContinues = null
    }

    private fun allContinueFps(sW: Int, sH: Int): List<ButtonFingerprint> {
        val key = sW.toLong() * 100000L + sH.toLong()
        val cached = cachedAllContinues
        if (cached != null && cachedAllContinuesKey == key) return cached

        val list = ArrayList<ButtonFingerprint>()
        list.addAll(fingerprintStore.getContinues(sW, sH))
        // Встроенные начальные отпечатки. Замерены на 1340x800 и заданы в «единицах высоты 800»
        // от ПРАВОГО края экрана (интерфейс прижат к углу и растёт вместе с высотой экрана),
        // поэтому подходят и для 8", и для 10", 12", 13" и телефонов с другим соотношением сторон.
        list.add(
            seedFingerprint(
                id = "seed_blue", ruleId = "continue_blue", sW = sW, sH = sH,
                centerFromRightU = 223f, widthU = 121f, topU = 716f, bottomU = 773f,
                r = 145, g = 180, b = 192, kind = ButtonKind.BLUE
            )
        )
        list.add(
            seedFingerprint(
                id = "seed_yellow", ruleId = "continue_mvp", sW = sW, sH = sH,
                centerFromRightU = 106f, widthU = 188f, topU = 718f, bottomU = 769f,
                r = 231, g = 180, b = 29, kind = ButtonKind.YELLOW
            )
        )
        cachedAllContinues = list
        cachedAllContinuesKey = key
        return list
    }

    private fun seedFingerprint(
        id: String,
        ruleId: String,
        sW: Int,
        sH: Int,
        centerFromRightU: Float,
        widthU: Float,
        topU: Float,
        bottomU: Float,
        r: Int,
        g: Int,
        b: Int,
        kind: ButtonKind
    ): ButtonFingerprint {
        val scale = sH / 800f
        val samples = ArrayList<FingerprintSample>()
        for (dy in floatArrayOf(8f, -8f)) {
            for (dx in floatArrayOf(12f, 40f, 70f)) {
                samples.add(FingerprintSample(dx, dy, r, g, b))
            }
        }
        return ButtonFingerprint(
            id = id,
            ruleId = ruleId,
            screenW = sW,
            screenH = sH,
            xFrac = (sW - centerFromRightU * scale) / sW,
            yFrac = ((topU + bottomU) / 2f) * scale / sH,
            boxWidthFrac = widthU * scale / sW,
            boxHeightFrac = (bottomU - topU) * scale / sH,
            samples = samples,
            kind = kind,
            probeMode = PROBE_EDGE6
        )
    }

    private fun logContinueDiag(bitmap: Bitmap, sW: Int, sH: Int) {
        val now = System.currentTimeMillis()
        if (now - lastContinueDiagMs < 5000L) return
        lastContinueDiagMs = now
        val sb = StringBuilder("FAST: continue: голубая: ")
        sb.append(lastPaleInfo).append("; жёлтая: ").append(lastYellowInfo).append("; пробы:")
        for (fp in allContinueFps(sW, sH)) {
            sb.append(' ').append(fp.id).append('=')
                .append(verifyFingerprint(bitmap, fp)).append('/').append(fp.samples.size)
        }
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, sb.toString())
    }

    private fun requiredMatches(fp: ButtonFingerprint): Int {
        return if (fp.probeMode == PROBE_EDGE6) REQUIRED_EDGE else REQUIRED_GRID
    }

    /**
     * Быстрая проверка отпечатка: читает только пиксели проб через getPixel (без копирования экрана).
     * grid: 9 проб сеткой по рамке; edge6: 6 проб у левого края рамки (ширина кнопки может меняться).
     */
    private fun verifyFingerprint(bitmap: Bitmap, fp: ButtonFingerprint): Int {
        val sW = bitmap.width
        val sH = bitmap.height
        val boxW = fp.boxWidthFrac * sW
        val boxH = fp.boxHeightFrac * sH
        val boxLeft = fp.xFrac * sW - boxW / 2f
        val boxTop = fp.yFrac * sH - boxH / 2f
        val boxBottom = boxTop + boxH
        val scale = sH / 800f
        val edge = fp.probeMode == PROBE_EDGE6

        var matched = 0
        for (sample in fp.samples) {
            val px: Int
            val py: Int
            if (edge) {
                px = (boxLeft + sample.relX * scale).toInt().coerceIn(1, sW - 2)
                val dy = sample.relY * scale
                py = (if (dy >= 0f) boxTop + shift(dy) else boxBottom + dy).toInt().coerceIn(1, sH - 2)
            } else {
                px = (boxLeft + sample.relX * boxW).toInt().coerceIn(1, sW - 2)
                py = (boxTop + sample.relY * boxH).toInt().coerceIn(1, sH - 2)
            }

            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (dy in -1..1) {
                val y = py + dy
                for (dx in -1..1) {
                    val x = px + dx
                    val p = bitmap.getPixel(x, y)
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
            }
            val avgR = sumR / count
            val avgG = sumG / count
            val avgB = sumB / count

            val diff = Math.abs(avgR - sample.r) + Math.abs(avgG - sample.g) + Math.abs(avgB - sample.b)
            if (diff <= 90) {
                matched++
            }
        }
        return matched
    }

    private fun shift(dy: Float): Float = dy

    /**
     * Поиск кнопки через ColorBlobFinder на ROI (step=4 для скорости).
     */
    private fun searchColorBlob(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        region: FloatArray,
        kind: ButtonKind,
        isContinue: Boolean = false
    ): ColorBlobFinder.Blob? {
        val x0 = (sW * region[0]).toInt().coerceIn(0, sW - 1)
        val x1 = (sW * region[1]).toInt().coerceIn(x0 + 1, sW)
        val y0 = (sH * region[2]).toInt().coerceIn(0, sH - 1)
        val y1 = (sH * region[3]).toInt().coerceIn(y0 + 1, sH)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 10 || roiH < 10) {
            val now = System.currentTimeMillis()
            if (now - lastSmallRoiLogMs >= 5000L) {
                lastSmallRoiLogMs = now
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: ROI слишком мал: ${roiW}x${roiH} (region=${region.contentToString()})"
                )
            }
            return null
        }

        val requiredSize = roiW * roiH
        if (roiPixelsBuffer.size < requiredSize) {
            roiPixelsBuffer = IntArray(requiredSize)
        }
        bitmap.getPixels(roiPixelsBuffer, 0, roiW, x0, y0, roiW, roiH)

        val result = if (isContinue) {
            ColorBlobFinder.findContinueButton(
                roiPixelsBuffer, roiW, roiH, step = 4, screenW = sW, screenH = sH,
                pale = (kind == ButtonKind.BLUE)
            )
        } else if (kind == ButtonKind.YELLOW) {
            ColorBlobFinder.findStartButton(roiPixelsBuffer, roiW, roiH, step = 4, screenW = sW, screenH = sH)
        } else {
            ColorBlobFinder.findBlueButton(roiPixelsBuffer, roiW, roiH, step = 4, screenW = sW, screenH = sH)
        }

        if (isContinue) {
            val cand = result.best ?: result.bestRejected
            val info = if (cand == null) {
                "кандидатов нет"
            } else {
                val verdict = if (result.best != null) "принята" else "отклонена"
                "рамка ${cand.width}x${cand.height} плотность ${"%.2f".format(cand.density)} пропорция ${"%.2f".format(cand.aspect)} ($verdict)"
            }
            if (kind == ButtonKind.BLUE) lastPaleInfo = info else lastYellowInfo = info
        }

        if (result.best != null) {
            val b = result.best
            return ColorBlobFinder.Blob(
                minX = x0 + b.minX,
                maxX = x0 + b.maxX,
                minY = y0 + b.minY,
                maxY = y0 + b.maxY,
                pixelCount = b.pixelCount,
                effectivePixels = b.effectivePixels,
                width = b.width,
                height = b.height,
                aspect = b.aspect,
                density = b.density,
                metric = b.metric
            )
        }

        // Лог лучшей отклонённой компоненты раз в 5 сек
        val rejected = result.bestRejected
        if (!isContinue && rejected != null && rejected.effectivePixels >= 10) {
            val now = System.currentTimeMillis()
            if (now - lastCalibRejectLogMs >= 5000L) {
                lastCalibRejectLogMs = now
                val colorName = if (kind == ButtonKind.YELLOW) "жёлтая" else "синяя"
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: калибровка ($colorName) отклонена: рамка ${rejected.width}x${rejected.height}, пропорция ${"%.2f".format(rejected.aspect)}, плотность ${"%.2f".format(rejected.density)} (нужно ≥ 0.45)"
                )
            }
        }
        return null
    }

    private fun calibrateColor(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        ruleId: String,
        region: FloatArray,
        kind: ButtonKind
    ): ButtonFingerprint? {
        val isContinue = ruleId.startsWith("continue")
        val blob = searchColorBlob(bitmap, sW, sH, region, kind, isContinue) ?: return null
        val fp = if (isContinue) {
            buildEdgeFingerprint(bitmap, ruleId, blob.centerX, blob.centerY, blob.width, blob.height, kind)
        } else {
            buildFingerprint(bitmap, ruleId, blob.centerX, blob.centerY, blob.width, blob.height, kind)
        }
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: запомнил кнопку $ruleId ${blob.centerX.toInt()},${blob.centerY.toInt()} проб ${fp.samples.size}/${fp.samples.size}"
        )
        return fp
    }

    private fun buildFingerprint(
        bitmap: Bitmap,
        ruleId: String,
        centerX: Float,
        centerY: Float,
        boxW: Int,
        boxH: Int,
        kind: ButtonKind
    ): ButtonFingerprint {
        val sW = bitmap.width
        val sH = bitmap.height
        val boxLeft = centerX - boxW / 2f
        val boxTop = centerY - boxH / 2f

        val axis = floatArrayOf(0.25f, 0.50f, 0.75f)
        val samples = mutableListOf<FingerprintSample>()

        for (ry in axis) {
            for (rx in axis) {
                val px = (boxLeft + rx * boxW).toInt().coerceIn(1, sW - 2)
                val py = (boxTop + ry * boxH).toInt().coerceIn(1, sH - 2)

                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (dy in -1..1) {
                    val y = (py + dy).coerceIn(0, sH - 1)
                    for (dx in -1..1) {
                        val x = (px + dx).coerceIn(0, sW - 1)
                        val p = bitmap.getPixel(x, y)
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                        count++
                    }
                }
                samples.add(FingerprintSample(rx, ry, sumR / count, sumG / count, sumB / count))
            }
        }

        return ButtonFingerprint(
            id = "${ruleId}_${System.currentTimeMillis() % 10000}",
            ruleId = ruleId,
            screenW = sW,
            screenH = sH,
            xFrac = centerX / sW.toFloat(),
            yFrac = centerY / sH.toFloat(),
            boxWidthFrac = boxW / sW.toFloat(),
            boxHeightFrac = boxH / sH.toFloat(),
            samples = samples,
            kind = kind
        )
    }

    /**
     * Отпечаток для кнопок continue: 6 проб у левого края рамки (3 сверху, 3 снизу),
     * там нет текста, а ширина кнопки (таймер, «Продолжить ( 98 )») на пробы не влияет.
     */
    private fun buildEdgeFingerprint(
        bitmap: Bitmap,
        ruleId: String,
        centerX: Float,
        centerY: Float,
        boxW: Int,
        boxH: Int,
        kind: ButtonKind
    ): ButtonFingerprint {
        val sW = bitmap.width
        val sH = bitmap.height
        val scale = sH / 800f
        val boxLeft = centerX - boxW / 2f
        val boxTop = centerY - boxH / 2f
        val boxBottom = boxTop + boxH

        val samples = ArrayList<FingerprintSample>()
        for (dy in floatArrayOf(8f, -8f)) {
            for (dx in floatArrayOf(12f, 40f, 70f)) {
                val px = (boxLeft + dx * scale).toInt().coerceIn(1, sW - 2)
                val shift = dy * scale
                val py = (if (shift >= 0f) boxTop + shift else boxBottom + shift).toInt().coerceIn(1, sH - 2)

                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0
                for (oy in -1..1) {
                    for (ox in -1..1) {
                        val p = bitmap.getPixel((px + ox).coerceIn(0, sW - 1), (py + oy).coerceIn(0, sH - 1))
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                        count++
                    }
                }
                samples.add(FingerprintSample(dx, dy, sumR / count, sumG / count, sumB / count))
            }
        }

        return ButtonFingerprint(
            id = "${ruleId}_${System.currentTimeMillis() % 10000}",
            ruleId = ruleId,
            screenW = sW,
            screenH = sH,
            xFrac = centerX / sW.toFloat(),
            yFrac = centerY / sH.toFloat(),
            boxWidthFrac = boxW / sW.toFloat(),
            boxHeightFrac = boxH / sH.toFloat(),
            samples = samples,
            kind = kind,
            probeMode = PROBE_EDGE6
        )
    }

    /**
     * Безопасное выполнение тапа с защитой от повторных нажатий в ту же точку (< 250 мс)
     * и длительностью жеста 30 мс.
     */
    private suspend fun performSafeTap(x: Float, y: Float): Boolean {
        val now = System.currentTimeMillis()
        val dist = hypot((x - lastTapX).toDouble(), (y - lastTapY).toDouble())
        if (dist < 30.0 && (now - lastTapTimeMs) < 250L) {
            return true // дубль: не тапаем, но это не ошибка
        }
        lastTapX = x
        lastTapY = y
        lastTapTimeMs = now
        return gestureExecutor.performTap(x, y, duration = 30L)
    }

    fun clearFingerprints() {
        fingerprintStore.clearAll()
        cachedAllContinues = null
    }
}
