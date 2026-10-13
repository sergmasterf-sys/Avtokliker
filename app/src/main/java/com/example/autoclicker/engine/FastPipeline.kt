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
    }

    enum class State {
        UNKNOWN,
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
        fun addRejected(screenW: Int, screenH: Int, xFrac: Float, yFrac: Float) {
            val key = "${screenW}x${screenH}:rejected"
            val array = try {
                JSONArray(prefs.getString(key, null) ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }
            val item = JSONArray()
            item.put(xFrac.toDouble())
            item.put(yFrac.toDouble())
            array.put(item)
            // Хранить не больше 20 точек: самые старые выпадают
            val trimmed = JSONArray()
            val from = (array.length() - 20).coerceAtLeast(0)
            for (i in from until array.length()) trimmed.put(array.get(i))
            prefs.edit().putString(key, trimmed.toString()).apply()
        }

        fun isRejected(screenW: Int, screenH: Int, xFrac: Float, yFrac: Float): Boolean {
            val raw = prefs.getString("${screenW}x${screenH}:rejected", null) ?: return false
            return try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val item = array.getJSONArray(i)
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
                State.LOBBY -> onActionUpdate("В лобби: ожидание старта", "Поиск кнопки НАЧАТЬ", null)
                State.IN_MATCH -> onActionUpdate("Матч идёт: пауза кликов", "Ожидание окончания матча", null)
                State.RESULT -> {
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
                State.IN_MATCH -> 1200L
                State.LOBBY -> 350L
                State.RESULT -> 350L
                State.UNKNOWN -> 350L
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
        val startFp = fingerprintStore.getStart(sW, sH)

        if (startFp != null && verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID) {
            transitionTo(State.LOBBY)
            return
        }

        // Экран результатов (изученные и встроенные seed-отпечатки continue): тап только после подтверждения в RESULT
        if (findContinueHit(bitmap, sW, sH) != null) {
            transitionTo(State.RESULT)
            continueSeenFrames = 1
            return
        }

        // Калибровка start: кандидат должен подтвердиться на двух кадрах подряд
        if (startFp == null) {
            val cand = calibrateColor(bitmap, sW, sH, "start", START_ROI, ButtonKind.YELLOW)
            val confirmed = confirmCalibration(cand)
            if (confirmed != null) {
                fingerprintStore.saveStart(confirmed)
                transitionTo(State.LOBBY)
                return
            }
            if (cand != null) return // ждём подтверждения на следующем кадре
        }

        // Кандидат continue: не запоминаем и не тапаем здесь, проверка с подтверждением в RESULT
        if (calibrateContinue(bitmap, sW, sH) != null) {
            transitionTo(State.RESULT)
            return
        }

        // Ничего не найдено: считаем, что мы в матче
        transitionTo(State.IN_MATCH)
    }

    private suspend fun handleLobbyState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val startFp = fingerprintStore.getStart(sW, sH)
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
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "FAST: start всё ещё виден через ${now - lastStartTapMs}мс после тапа -> повтор",
                        isError = startTapCount >= 3
                    )
                }
                tapStart(startFp.xFrac * sW, startFp.yFrac * sH, "$matched/${startFp.samples.size}", "отпечаток", shotStart)
                return
            } else {
                if (startPending) {
                    // Кнопка не видна: нажатие успешно только после 2 кадров подряд
                    startGoneFrames++
                    if (startGoneFrames >= CONFIRM_GONE_FRAMES) {
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "FAST: start подтверждён: кнопка исчезла после $startTapCount тап(ов)"
                        )
                        onCycleIncrement()
                        transitionTo(State.IN_MATCH)
                    }
                    return
                }

                // Если вместо лобби уже экран результатов: переходим в RESULT, тап там после подтверждения
                if (findContinueHit(bitmap, sW, sH) != null) {
                    transitionTo(State.RESULT)
                    continueSeenFrames = 1
                    return
                }

                lobbyStartMissCount++
                if (lobbyStartMissCount >= 5) {
                    // Отпечаток start не совпал 5 раз подряд: перекалибровать по цвету
                    lobbyStartMissCount = 0
                    val colorBlob = searchColorBlob(bitmap, sW, sH, START_ROI, ButtonKind.YELLOW)
                    if (colorBlob != null) {
                        val oldX = startFp.xFrac * sW
                        val oldY = startFp.yFrac * sH
                        val dist = hypot((colorBlob.centerX - oldX).toDouble(), (colorBlob.centerY - oldY).toDouble())
                        fingerprintStore.clearStart(sW, sH)
                        val newFp = buildFingerprint(bitmap, "start", colorBlob.centerX, colorBlob.centerY, colorBlob.width, colorBlob.height, ButtonKind.YELLOW)
                        fingerprintStore.saveStart(newFp)
                        EventLogManager.log(
                            EventLogManager.TAG_AUTO_CLICKER,
                            "FAST: отпечаток start перекалиброван на ${colorBlob.centerX.toInt()},${colorBlob.centerY.toInt()} (сдвиг ${dist.toInt()} px)"
                        )
                    }
                }
            }
        } else {
            // Отпечатка start нет: калибровка с подтверждением на 2 кадрах
            val cand = calibrateColor(bitmap, sW, sH, "start", START_ROI, ButtonKind.YELLOW)
            val confirmed = confirmCalibration(cand)
            if (confirmed != null) {
                fingerprintStore.saveStart(confirmed)
                tapStart(confirmed.xFrac * sW, confirmed.yFrac * sH, "${confirmed.samples.size}/${confirmed.samples.size}", "калибровка_цвет", shotStart)
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
                        val fp = buildFingerprint(bitmap, "start", clickX, clickY, (sW * 0.18f).toInt(), (sH * 0.08f).toInt(), ButtonKind.YELLOW)
                        fingerprintStore.saveStart(fp)
                        tapStart(clickX, clickY, "${fp.samples.size}/${fp.samples.size}", "шаблон", shotStart)
                        return
                    }
                }
            }
        }
    }

    private suspend fun handleInMatchState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        // В матче тапов НЕ делаем. При появлении continue переходим в RESULT без тапа:
        // тап там только после подтверждения на нескольких кадрах.
        val hit = findContinueHit(bitmap, sW, sH)
        if (hit != null) {
            transitionTo(State.RESULT)
            continueSeenFrames = 1
            return
        }

        inMatchFrame++
        if (inMatchFrame % 3 == 0) {
            // Каждый 3-й кадр: есть ли кандидат continue в правом нижнем углу (без запоминания и без тапа)
            if (calibrateContinue(bitmap, sW, sH) != null) {
                transitionTo(State.RESULT)
                return
            }

            // Если снова видна кнопка start (давно после нажатия), вернуться в лобби
            val startFp = fingerprintStore.getStart(sW, sH)
            if (startFp != null &&
                System.currentTimeMillis() - lastStartTapMs > 6000L &&
                verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID
            ) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: в IN_MATCH снова видна кнопка start -> LOBBY"
                )
                transitionTo(State.LOBBY)
                return
            }
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
                saveContinueFp(confirmed)
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
        val startFp = fingerprintStore.getStart(sW, sH)
        if (startFp != null && verifyFingerprint(bitmap, startFp) >= REQUIRED_GRID) {
            transitionTo(State.LOBBY)
            return
        }

        // Между экранами результатов бывает пауза на анимацию, поэтому выходим не сразу
        if (resultMissFrames >= 2 && System.currentTimeMillis() - lastResultActionMs > 4000L) {
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "FAST: в RESULT кнопок не видно -> IN_MATCH"
            )
            transitionTo(State.IN_MATCH)
            return
        }

        logContinueDiag(bitmap, sW, sH)
    }

    private class ContinueHit(val fp: ButtonFingerprint, val matched: Int)

    /**
     * Проверка всех отпечатков continue (изученные + встроенные seed). Возвращает лучший.
     */
    private fun findContinueHit(bitmap: Bitmap, sW: Int, sH: Int): ContinueHit? {
        val fps = allContinueFps(sW, sH)
        var best: ContinueHit? = null
        for (fp in fps) {
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

    /** Нажатие «НАЧАТЬ»: подтверждение (кнопка исчезла) выполняется в handleLobbyState. */
    private suspend fun tapStart(x: Float, y: Float, probes: String, source: String, shotStart: Long) {
        val now = System.currentTimeMillis()
        startTapCount++
        startPending = true
        startGoneFrames = 0
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: тап start ${x.toInt()},${y.toInt()} проб $probes попытка $startTapCount источник=$source снимок->тап ${now - shotStart}мс"
        )
        onActionUpdate("Нажатие: НАЧАТЬ", "Проверка: кнопка должна исчезнуть", "НАЖАТИЕ: start (${x.toInt()}, ${y.toInt()})")
        performSafeTap(x, y)
        lastStartTapMs = System.currentTimeMillis()
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
        performSafeTap(tapX, tapY)
        lastResultActionMs = System.currentTimeMillis()
        resultMissFrames = 0
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
        continuePending = false
        continueTapCount = 0
        continueGoneFrames = 0
        continueSeenFrames = 0
        pendingCalib = null
        pendingCalibFrames = 0
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
        // Встроенные начальные отпечатки (по долям экрана, замерены на 1340x800)
        list.add(
            seedFingerprint(
                id = "seed_blue", ruleId = "continue_blue", sW = sW, sH = sH,
                leftFrac = 0.789f, topFrac = 0.895f, bottomFrac = 0.966f, widthFrac = 0.09f,
                r = 145, g = 180, b = 192, kind = ButtonKind.BLUE
            )
        )
        list.add(
            seedFingerprint(
                id = "seed_yellow", ruleId = "continue_mvp", sW = sW, sH = sH,
                leftFrac = 0.851f, topFrac = 0.897f, bottomFrac = 0.961f, widthFrac = 0.14f,
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
        leftFrac: Float,
        topFrac: Float,
        bottomFrac: Float,
        widthFrac: Float,
        r: Int,
        g: Int,
        b: Int,
        kind: ButtonKind
    ): ButtonFingerprint {
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
            xFrac = leftFrac + widthFrac / 2f,
            yFrac = (topFrac + bottomFrac) / 2f,
            boxWidthFrac = widthFrac,
            boxHeightFrac = bottomFrac - topFrac,
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
                py = (if (dy >= 0f) boxTop + dy else boxBottom + dy).toInt().coerceIn(1, sH - 2)
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
            ColorBlobFinder.findYellowButton(roiPixelsBuffer, roiW, roiH, step = 4, screenW = sW, screenH = sH)
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
    private suspend fun performSafeTap(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val dist = hypot((x - lastTapX).toDouble(), (y - lastTapY).toDouble())
        if (dist < 30.0 && (now - lastTapTimeMs) < 250L) {
            return
        }
        lastTapX = x
        lastTapY = y
        lastTapTimeMs = now
        gestureExecutor.performTap(x, y, duration = 30L)
    }

    fun clearFingerprints() {
        fingerprintStore.clearAll()
        cachedAllContinues = null
    }
}
