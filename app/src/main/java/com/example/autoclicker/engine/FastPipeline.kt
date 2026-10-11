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
        val relX: Float, // 0.25f, 0.50f, 0.75f
        val relY: Float, // 0.25f, 0.50f, 0.75f
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
        val kind: ButtonKind
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
                ButtonFingerprint(id, ruleId, screenW, screenH, xFrac, yFrac, boxWidthFrac, boxHeightFrac, samples, kind)
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

    // Логирование и статистика (без спама, раз в 5 сек)
    private var lastSummaryLogMs = 0L
    private var lastCalibRejectLogMs = 0L
    private var shotTimeSum = 0L
    private var procTimeSum = 0L
    private var framesCount = 0

    private fun transitionTo(newState: State) {
        if (state != newState) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "FAST: $state -> $newState")
            state = newState
            when (newState) {
                State.UNKNOWN -> onActionUpdate("Определение экрана...", "Поиск кнопок", null)
                State.LOBBY -> onActionUpdate("В лобби: ожидание старта", "Поиск кнопки НАЧАТЬ", null)
                State.IN_MATCH -> onActionUpdate("Матч идёт: пауза кликов", "Ожидание окончания матча", null)
                State.RESULT -> onActionUpdate("Результаты матча", "Нажатие ПРОДОЛЖИТЬ", null)
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
            // IN_MATCH: опрос редкий (1500 мс)
            // LOBBY: опрос каждые ~350 мс
            // RESULT: опрос каждые ~300-350 мс
            val interval = when (state) {
                State.IN_MATCH -> 1500L
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
        // Проверяем все отпечатки
        val startFp = fingerprintStore.getStart(sW, sH)
        val continueFps = fingerprintStore.getContinues(sW, sH)

        if (startFp != null && verifyFingerprint(bitmap, startFp) >= 7) {
            transitionTo(State.LOBBY)
            return
        }

        for (cfp in continueFps) {
            if (verifyFingerprint(bitmap, cfp) >= 7) {
                transitionTo(State.RESULT)
                return
            }
        }

        // Отпечатков нет или не совпали — калибровка
        if (startFp == null) {
            val calibStart = calibrateColor(bitmap, sW, sH, "start", floatArrayOf(0.0f, 0.42f, 0.65f, 1.0f), ButtonKind.YELLOW)
            if (calibStart != null) {
                fingerprintStore.saveStart(calibStart)
                transitionTo(State.LOBBY)
                return
            }
        }

        if (continueFps.isEmpty()) {
            val calibCont = calibrateColor(bitmap, sW, sH, "continue", floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f), ButtonKind.BLUE)
                ?: calibrateColor(bitmap, sW, sH, "continue", floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f), ButtonKind.YELLOW)
            if (calibCont != null) {
                fingerprintStore.saveContinue(calibCont)
                transitionTo(State.RESULT)
                return
            }
        }

        // Ничего не найдено — считаем, что мы в матче
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
        if (startFp != null) {
            val matched = verifyFingerprint(bitmap, startFp)
            if (matched >= 7) {
                lobbyStartMissCount = 0
                val tapX = startFp.xFrac * sW
                val tapY = startFp.yFrac * sH
                val timeMs = System.currentTimeMillis() - shotStart
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: тап start ${tapX.toInt()},${tapY.toInt()} проб $matched/9 источник=отпечаток снимок->тап ${timeMs}мс"
                )
                onActionUpdate("Нажатие: НАЧАТЬ", "Переход в матч", "НАЖАТИЕ: start (${tapX.toInt()}, ${tapY.toInt()})")
                performSafeTap(tapX, tapY)
                onCycleIncrement()
                transitionTo(State.IN_MATCH)
                return
            } else {
                lobbyStartMissCount++
                if (lobbyStartMissCount >= 5) {
                    // Защита: отпечаток start не совпал 5 раз подряд — проверить, не сместилась ли кнопка
                    val colorBlob = searchColorBlob(bitmap, sW, sH, floatArrayOf(0.0f, 0.42f, 0.65f, 1.0f), ButtonKind.YELLOW)
                    if (colorBlob != null) {
                        val oldX = startFp.xFrac * sW
                        val oldY = startFp.yFrac * sH
                        val dist = hypot((colorBlob.centerX - oldX).toDouble(), (colorBlob.centerY - oldY).toDouble())
                        if (dist > 40.0) {
                            fingerprintStore.clearStart(sW, sH)
                            val newFp = buildFingerprint(bitmap, "start", colorBlob.centerX, colorBlob.centerY, colorBlob.width, colorBlob.height, ButtonKind.YELLOW)
                            fingerprintStore.saveStart(newFp)
                            EventLogManager.log(
                                EventLogManager.TAG_AUTO_CLICKER,
                                "FAST: кнопка start сместилась ($dist px), отпечаток перекалиброван на ${colorBlob.centerX.toInt()},${colorBlob.centerY.toInt()}"
                            )
                            lobbyStartMissCount = 0
                        }
                    }
                }
            }
        } else {
            // Отпечатка start нет — калибровка на каждом кадре
            val calib = calibrateColor(bitmap, sW, sH, "start", floatArrayOf(0.0f, 0.42f, 0.65f, 1.0f), ButtonKind.YELLOW)
            if (calib != null) {
                fingerprintStore.saveStart(calib)
                val tapX = calib.xFrac * sW
                val tapY = calib.yFrac * sH
                val timeMs = System.currentTimeMillis() - shotStart
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: тап start ${tapX.toInt()},${tapY.toInt()} проб 9/9 источник=калибровка_цвет снимок->тап ${timeMs}мс"
                )
                onActionUpdate("Нажатие: НАЧАТЬ", "Переход в матч", "НАЖАТИЕ: start (${tapX.toInt()}, ${tapY.toInt()})")
                performSafeTap(tapX, tapY)
                onCycleIncrement()
                transitionTo(State.IN_MATCH)
                return
            } else if (templateFallback != null) {
                // Запасной вариант: поиск по шаблону только при калибровке
                val rule = config.rules.firstOrNull { it.id == "start" }
                if (rule != null) {
                    val fallbackCoords = templateFallback.invoke(bitmap, rule)
                    if (fallbackCoords != null) {
                        val (clickX, clickY) = fallbackCoords
                        val fp = buildFingerprint(bitmap, "start", clickX, clickY, (sW * 0.18f).toInt(), (sH * 0.08f).toInt(), ButtonKind.YELLOW)
                        fingerprintStore.saveStart(fp)
                        performSafeTap(clickX, clickY)
                        onCycleIncrement()
                        transitionTo(State.IN_MATCH)
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
        // В матче тапов НЕ делаем. Проверяем только кнопки continue
        val continueFps = fingerprintStore.getContinues(sW, sH)
        for (cfp in continueFps) {
            val matched = verifyFingerprint(bitmap, cfp)
            if (matched >= 7) {
                val tapX = cfp.xFrac * sW
                val tapY = cfp.yFrac * sH
                val timeMs = System.currentTimeMillis() - shotStart
                transitionTo(State.RESULT)
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: тап ${cfp.ruleId} ${tapX.toInt()},${tapY.toInt()} проб $matched/9 источник=отпечаток снимок->тап ${timeMs}мс"
                )
                onActionUpdate("Нажатие: ПРОДОЛЖИТЬ", "Экран результатов", "НАЖАТИЕ: continue (${tapX.toInt()}, ${tapY.toInt()})")
                performSafeTap(tapX, tapY)
                lastResultActionMs = System.currentTimeMillis()
                return
            }
        }

        // Если continue ещё не изучен — калибровка в ROI continue
        if (continueFps.isEmpty()) {
            val calibCont = calibrateColor(bitmap, sW, sH, "continue_blue", floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f), ButtonKind.BLUE)
                ?: calibrateColor(bitmap, sW, sH, "continue_mvp", floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f), ButtonKind.YELLOW)
            if (calibCont != null) {
                fingerprintStore.saveContinue(calibCont)
                val tapX = calibCont.xFrac * sW
                val tapY = calibCont.yFrac * sH
                val timeMs = System.currentTimeMillis() - shotStart
                transitionTo(State.RESULT)
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: тап ${calibCont.ruleId} ${tapX.toInt()},${tapY.toInt()} проб 9/9 источник=калибровка_цвет снимок->тап ${timeMs}мс"
                )
                onActionUpdate("Нажатие: ПРОДОЛЖИТЬ", "Экран результатов", "НАЖАТИЕ: continue (${tapX.toInt()}, ${tapY.toInt()})")
                performSafeTap(tapX, tapY)
                lastResultActionMs = System.currentTimeMillis()
                return
            }
        }
    }

    private suspend fun handleResultState(
        bitmap: Bitmap,
        sW: Int,
        sH: Int,
        config: EffectiveConfig,
        shotStart: Long
    ) {
        val continueFps = fingerprintStore.getContinues(sW, sH)
        var continueClicked = false

        for (cfp in continueFps) {
            val matched = verifyFingerprint(bitmap, cfp)
            if (matched >= 7) {
                val tapX = cfp.xFrac * sW
                val tapY = cfp.yFrac * sH
                val timeMs = System.currentTimeMillis() - shotStart
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "FAST: тап ${cfp.ruleId} ${tapX.toInt()},${tapY.toInt()} проб $matched/9 источник=отпечаток снимок->тап ${timeMs}мс"
                )
                onActionUpdate("Нажатие: ПРОДОЛЖИТЬ", "Экран результатов", "НАЖАТИЕ: continue (${tapX.toInt()}, ${tapY.toInt()})")
                performSafeTap(tapX, tapY)
                lastResultActionMs = System.currentTimeMillis()
                continueClicked = true
                delay(300L) // После каждого тапа ждать 300 мс и сразу следующий снимок
                break
            }
        }

        if (continueClicked) return

        // Проверяем, появилась ли новая кнопка continue другого цвета/позиции
        val newCalib = calibrateColor(bitmap, sW, sH, "continue_blue", floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f), ButtonKind.BLUE)
            ?: calibrateColor(bitmap, sW, sH, "continue_mvp", floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f), ButtonKind.YELLOW)
        if (newCalib != null) {
            fingerprintStore.saveContinue(newCalib)
            val tapX = newCalib.xFrac * sW
            val tapY = newCalib.yFrac * sH
            val timeMs = System.currentTimeMillis() - shotStart
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "FAST: тап ${newCalib.ruleId} ${tapX.toInt()},${tapY.toInt()} проб 9/9 источник=калибровка_новый снимок->тап ${timeMs}мс"
            )
            onActionUpdate("Нажатие: ПРОДОЛЖИТЬ", "Экран результатов", "НАЖАТИЕ: continue (${tapX.toInt()}, ${tapY.toInt()})")
            performSafeTap(tapX, tapY)
            lastResultActionMs = System.currentTimeMillis()
            delay(300L)
            return
        }

        // Если continue не видно, проверяем start
        val startFp = fingerprintStore.getStart(sW, sH)
        if (startFp != null && verifyFingerprint(bitmap, startFp) >= 7) {
            transitionTo(State.LOBBY)
            return
        }

        // Если ничего не найдено 8 секунд — возврат в IN_MATCH
        if (System.currentTimeMillis() - lastResultActionMs > 8000L) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "FAST: в RESULT ничего не найдено 8 сек -> возврат в IN_MATCH")
            transitionTo(State.IN_MATCH)
        }
    }

    /**
     * Быстрая 9-точечная проверка отпечатка.
     * Читает только пиксели 9 проб из Bitmap через getPixel (без копирования экрана).
     */
    private fun verifyFingerprint(bitmap: Bitmap, fp: ButtonFingerprint): Int {
        val sW = bitmap.width
        val sH = bitmap.height
        val boxW = fp.boxWidthFrac * sW
        val boxH = fp.boxHeightFrac * sH
        val boxLeft = fp.xFrac * sW - boxW / 2f
        val boxTop = fp.yFrac * sH - boxH / 2f

        var matched = 0
        for (sample in fp.samples) {
            val px = (boxLeft + sample.relX * boxW).toInt().coerceIn(1, sW - 2)
            val py = (boxTop + sample.relY * boxH).toInt().coerceIn(1, sH - 2)

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
        kind: ButtonKind
    ): ColorBlobFinder.Blob? {
        val x0 = (sW * region[0]).toInt().coerceIn(0, sW - 1)
        val x1 = (sW * region[1]).toInt().coerceIn(x0 + 1, sW)
        val y0 = (sH * region[2]).toInt().coerceIn(0, sH - 1)
        val y1 = (sH * region[3]).toInt().coerceIn(y0 + 1, sH)
        val roiW = x1 - x0
        val roiH = y1 - y0
        if (roiW < 10 || roiH < 10) return null

        val requiredSize = roiW * roiH
        if (roiPixelsBuffer.size < requiredSize) {
            roiPixelsBuffer = IntArray(requiredSize)
        }
        bitmap.getPixels(roiPixelsBuffer, 0, roiW, x0, y0, roiW, roiH)

        val result = if (kind == ButtonKind.YELLOW) {
            ColorBlobFinder.findYellowButton(roiPixelsBuffer, roiW, roiH, step = 4, screenW = sW, screenH = sH)
        } else {
            ColorBlobFinder.findBlueButton(roiPixelsBuffer, roiW, roiH, step = 4, screenW = sW, screenH = sH)
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
        if (rejected != null && rejected.effectivePixels >= 10) {
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
        val blob = searchColorBlob(bitmap, sW, sH, region, kind) ?: return null
        val fp = buildFingerprint(bitmap, ruleId, blob.centerX, blob.centerY, blob.width, blob.height, kind)
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "FAST: запомнил кнопку $ruleId ${blob.centerX.toInt()},${blob.centerY.toInt()} проб 9/9"
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
    }
}
