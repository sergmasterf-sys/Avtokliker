package com.example.autoclicker.engine

import android.graphics.Bitmap
import com.example.autoclicker.data.EventLogManager
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Найденный крестик закрытия рекламы: центр и размер в пикселях ЭКРАНА. */
data class AdCrossCandidate(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val score: Float,
    val contrast: Int
)

/**
 * Чистый (без Android) детектор крестика «×» по форме, а не по шаблону картинки.
 * Работает на любом разрешении: размеры считаются в «единицах высоты 800 px».
 *
 * Идея: в окне NxN (сетка проб 9x9) у настоящего крестика
 *  - центр и 4 угла окна одного цвета (штрихи диагоналей), а
 *  - середины сторон (верх/низ/лево/право) и вся «внедиагональная» область — однородный фон.
 * «+», сплошные пятна, полосы текста и фото под это не подходят.
 * Подходят оба варианта: светлый крестик на тёмном и тёмный на светлом.
 *
 * Улучшения v2/v3: расширен диапазон размеров, снижен порог контраста для белых крестиков
 * на полупрозрачных рекламах, ROI сильнее прижат к правому верхнему углу.
 * Явно отсекается форма «+» (плюс в UI лобби), оставляется только «×».
 */
object AdCrossDetector {
    // Область поиска: правая верхняя часть (рекламный крестик почти всегда там)
    const val ROI_X0 = 0.65f
    const val ROI_X1 = 1.0f
    const val ROI_Y0 = 0.0f
    const val ROI_Y1 = 0.30f

    /** Размеры окна крестика в единицах высоты 800. Расширено для разных экранов и реклам. */
    val SIZES_U = intArrayOf(10, 12, 14, 16, 20, 24, 30, 36, 44)
    const val STRIDE_U = 2

    const val MIN_CONTRAST = 38          // было 50 — белый крестик на полупрозрачном фоне часто слабее
    const val MIN_DIAG_FRAC = 0.78f      // было 0.85 — чуть мягче к неидеальным диагоналям
    const val MIN_OFF_FRAC = 0.85f       // было 0.90
    private const val GRID = 9

    /** [x0, y0, x1, y1] области поиска в пикселях экрана. */
    fun roiRect(screenW: Int, screenH: Int): IntArray {
        val x0 = (ROI_X0 * screenW).toInt().coerceIn(0, screenW - 1)
        val y0 = (ROI_Y0 * screenH).toInt().coerceIn(0, screenH - 1)
        val x1 = (ROI_X1 * screenW).toInt().coerceIn(x0 + 1, screenW)
        val y1 = (ROI_Y1 * screenH).toInt().coerceIn(y0 + 1, screenH)
        return intArrayOf(x0, y0, x1, y1)
    }

    /**
     * [pixels] — ARGB пиксели области поиска размером [w] x [h] (в пикселях экрана),
     * [unit] = высота_экрана / 800, [roiLeft]/[roiTop] — смещение области на экране.
     * Возвращает лучшего кандидата или null.
     */
    fun detect(
        pixels: IntArray,
        w: Int,
        h: Int,
        unit: Float,
        roiLeft: Int,
        roiTop: Int
    ): AdCrossCandidate? {
        if (w <= 0 || h <= 0 || unit <= 0f) return null
        val workW = (w / unit).toInt()
        val workH = (h / unit).toInt()
        if (workW < 16 || workH < 16) return null

        // Яркость в рабочем масштабе (1 рабочий пиксель = 1 единица)
        val luma = IntArray(workW * workH)
        val sxTable = IntArray(workW) { wx -> min(w - 1, ((wx + 0.5f) * unit).toInt()) }
        for (wy in 0 until workH) {
            val sy = min(h - 1, ((wy + 0.5f) * unit).toInt())
            val rowSrc = sy * w
            val rowDst = wy * workW
            for (wx in 0 until workW) {
                val p = pixels[rowSrc + sxTable[wx]]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                luma[rowDst + wx] = (r * 77 + g * 150 + b * 29) shr 8
            }
        }

        var best: AdCrossCandidate? = null
        var bestRank = -1f
        val off = IntArray(GRID)

        for (size in SIZES_U) {
            if (size > workW || size > workH) continue
            for (k in 0 until GRID) off[k] = k * (size - 1) / (GRID - 1)

            var wy = 0
            while (wy + size <= workH) {
                var wx = 0
                while (wx + size <= workW) {
                    val rank = evaluate(luma, workW, wx, wy, off, size, workW, workH)
                    if (rank != null && rank.first > bestRank) {
                        bestRank = rank.first
                        best = AdCrossCandidate(
                            x = roiLeft + (wx + size / 2f) * unit,
                            y = roiTop + (wy + size / 2f) * unit,
                            sizePx = size * unit,
                            score = rank.second,
                            contrast = rank.third
                        )
                    }
                    wx += STRIDE_U
                }
                wy += STRIDE_U
            }
        }
        return best
    }

    /** Возвращает (ранг, оценка 0..1, контраст) или null, если окно не крестик. */
    private fun evaluate(
        luma: IntArray,
        stride: Int,
        wx: Int,
        wy: Int,
        off: IntArray,
        size: Int,
        workW: Int,
        workH: Int
    ): Triple<Float, Float, Int>? {
        fun g(i: Int, j: Int): Int = luma[(wy + off[i]) * stride + wx + off[j]]

        // Быстрая отсечка: центр, 4 угла и 4 середины сторон
        val m0 = g(0, 4)
        val m1 = g(4, 0)
        val m2 = g(8, 4)
        val m3 = g(4, 8)
        val mMax = max(max(m0, m1), max(m2, m3))
        val mMin = min(min(m0, m1), min(m2, m3))
        if (mMax - mMin > 35) return null
        val bg = (m0 + m1 + m2 + m3) / 4f

        val c = g(4, 4)
        val diff = c - bg
        val contrast = abs(diff).toInt()
        if (contrast < MIN_CONTRAST) return null
        val pol = if (diff > 0f) 1 else -1
        val fgThr = contrast * 0.45f   // чуть мягче порог переднего плана

        if ((g(0, 0) - bg) * pol < fgThr) return null
        if ((g(0, 8) - bg) * pol < fgThr) return null
        if ((g(8, 0) - bg) * pol < fgThr) return null
        if ((g(8, 8) - bg) * pol < fgThr) return null

        // Отсекаем «+» (плюс): у плюса середины сторон (верх/низ/лево/право) — тоже штрих,
        // у настоящего крестика «×» середины сторон = фон.
        // Если 3+ из 4 середин сторон похожи на крестик — это плюс, не крестик.
        var plusArms = 0
        if ((g(0, 4) - bg) * pol >= fgThr) plusArms++ // верх
        if ((g(8, 4) - bg) * pol >= fgThr) plusArms++ // низ
        if ((g(4, 0) - bg) * pol >= fgThr) plusArms++ // лево
        if ((g(4, 8) - bg) * pol >= fgThr) plusArms++ // право
        if (plusArms >= 3) return null

        val bgTol = max(24f, contrast * 0.32f)

        fun fg(i: Int, j: Int): Boolean =
            i in 0 until GRID && j in 0 until GRID && (g(i, j) - bg) * pol >= fgThr

        var diagOk = 0
        var diagTot = 0
        var offOk = 0
        var offTot = 0
        for (i in 0 until GRID) {
            for (j in 0 until GRID) {
                val d1 = abs(i - j)
                val d2 = abs(i + j - (GRID - 1))
                if (d1 == 0 || d2 == 0) {
                    diagTot++
                    // штрих может быть смещён на клетку: смотрим и соседей поперёк диагонали
                    var hit = fg(i, j)
                    if (!hit && d1 == 0) hit = fg(i - 1, j + 1) || fg(i + 1, j - 1)
                    if (!hit && d2 == 0) hit = fg(i - 1, j - 1) || fg(i + 1, j + 1)
                    if (hit) diagOk++
                } else if (d1 >= 3 && d2 >= 3) {
                    offTot++
                    if (abs(g(i, j) - bg) <= bgTol) offOk++
                }
            }
        }
        if (diagTot == 0 || offTot == 0) return null
        val diagFrac = diagOk.toFloat() / diagTot
        val offFrac = offOk.toFloat() / offTot
        if (diagFrac < MIN_DIAG_FRAC || offFrac < MIN_OFF_FRAC) return null

        val score = min(diagFrac, offFrac)
        val cx = (wx + size / 2f) / workW
        val cy = (wy + size / 2f) / workH
        // Сильное предпочтение: контрастнее и ближе к правому верхнему углу
        val rank = score * 100f + contrast * 0.12f + cx * 8f + (1f - cy) * 8f
        return Triple(rank, score, contrast)
    }
}

/**
 * Закрывает рекламные окна при входе в игру (крестик «×» в правом верхнем углу).
 *
 * Работает «сеансом», а не постоянно:
 *  - сеанс начинается при включении ползунка (или при включении переключателя «Закрывать рекламу»);
 *  - пока идёт сеанс, ищем крестики в лобби: каждый найденный запоминаем (координаты),
 *    нажимаем только после подтверждения на [CONFIRM_FRAMES] кадрах подряд, не больше
 *    [MAX_SPOT_TAPS] раз на одно место (пока крестик не исчез между нажатиями);
 *  - после каждого нажатия ждём и снова проверяем, не появилась ли следующая реклама;
 *  - если крестиков нет [QUIET_MS] после появления лобби, сеанс завершается и больше
 *    ничего не ищется (после матча и в матче крестик не ищем вообще);
 *  - сеанс также завершается: при начале матча, после нажатия «НАЧАТЬ», по лимиту нажатий
 *    [MAX_TOTAL_TAPS] или времени [SESSION_MAX_MS].
 *
 * @param tap    выполняет нажатие (в FastPipeline это performSafeTap)
 * @param onAction  обновляет строки статуса в плавающем окне (текущее действие, следующее)
 */
class AdCloser(
    private val tap: suspend (Float, Float) -> Boolean,
    private val onAction: (current: String, next: String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val CONFIRM_FRAMES = 3            // крестик должен стоять на месте столько кадров подряд
        const val TAP_GAP_MS = 700L
        const val SETTLE_AFTER_TAP_MS = 1400L   // после нажатия ждём закрытия / появления следующей рекламы
        const val QUIET_MS = 1800L              // столько тишины (нет крестиков) после лобби, и сеанс закончен
        const val MAX_SPOT_TAPS = 2             // нажатий на одно место, пока крестик не исчезал
        const val MAX_TOTAL_TAPS = 8
        const val SESSION_MAX_MS = 60_000L
        private const val GONE_FRAMES_FOR_NEW_AD = 2
        private const val SPOT_TOL_FRAC_OF_H = 0.05f
    }

    /** Запомненное место крестика. */
    private class Spot(val x: Float, val y: Float) {
        var taps = 0
        var blocked = false
    }

    private var pixelBuf = IntArray(0)

    private var active = true
    private val spots = ArrayList<Spot>()
    private var pending: AdCrossCandidate? = null
    private var pendingFrames = 0
    private var sessionStartMs = 0L
    private var lobbySeenMs = 0L
    private var lastCrossSeenMs = 0L
    private var lastTapMs = 0L
    private var goneFramesSinceTap = 0
    private var totalTaps = 0
    private var lastErrorLogMs = 0L

    /** true, пока сеанс поиска рекламы идёт. */
    val isActive: Boolean
        get() = active

    /** Начать новый сеанс (ползунок включили / включили «Закрывать рекламу»). */
    fun restartSession() {
        active = true
        spots.clear()
        pending = null
        pendingFrames = 0
        sessionStartMs = 0L
        lobbySeenMs = 0L
        lastCrossSeenMs = 0L
        lastTapMs = 0L
        goneFramesSinceTap = 0
        totalTaps = 0
        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "AD: начат поиск рекламы (крестиков)")
    }

    /** Совместимость с кодом конвейера */
    fun reset() = restartSession()

    /** Завершить сеанс: до следующего [restartSession] крестики не ищутся и не нажимаются. */
    fun finish(reason: String) {
        if (!active) return
        active = false
        pending = null
        pendingFrames = 0
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "AD: поиск рекламы завершён ($reason), нажато крестиков: $totalTaps, запомнено мест: ${spots.size}"
        )
    }

    /** Вызывать, когда на экране видно лобби: с этого момента начинает идти «время тишины». */
    fun onLobbyVisible() {
        if (active && lobbySeenMs == 0L) {
            lobbySeenMs = clock()
            if (sessionStartMs == 0L) sessionStartMs = lobbySeenMs
        }
    }

    private fun spotAt(x: Float, y: Float, tol: Float): Spot? =
        spots.firstOrNull { hypot((it.x - x).toDouble(), (it.y - y).toDouble()) <= tol }

    /**
     * Обрабатывает один кадр. true — кадр «занят рекламой» (подтверждаем крестик, нажали,
     * ждём закрытия или ждём возможную следующую рекламу): остальную логику пропустить.
     * false — можно выполнять обычную логику.
     */
    suspend fun step(bitmap: Bitmap, screenW: Int, screenH: Int): Boolean {
        if (!active) return false
        val now = clock()
        if (sessionStartMs > 0L && now - sessionStartMs > SESSION_MAX_MS) {
            finish("лимит времени")
            return false
        }

        var cand = findCandidate(bitmap, screenW, screenH)
        val tol = screenH * SPOT_TOL_FRAC_OF_H
        if (cand != null && spotAt(cand.x, cand.y, tol)?.blocked == true) cand = null

        if (cand == null) {
            pending = null
            pendingFrames = 0
            if (lastTapMs > 0L) goneFramesSinceTap++
            // Только что нажали: ждём анимацию закрытия и возможную следующую рекламу
            if (lastTapMs > 0L && now - lastTapMs < SETTLE_AFTER_TAP_MS) return true
            val anchor = maxOf(lobbySeenMs, lastCrossSeenMs, lastTapMs)
            if (anchor == 0L) return false // игра ещё грузится, лобби не видно
            if (now - anchor >= QUIET_MS) {
                finish(if (totalTaps > 0) "реклама закрыта" else "рекламы нет")
                return false
            }
            return true // короткое окно ожидания: вдруг реклама ещё появится
        }

        lastCrossSeenMs = now
        if (sessionStartMs == 0L) sessionStartMs = now
        val prev = pending
        pendingFrames = if (prev != null && hypot((prev.x - cand.x).toDouble(), (prev.y - cand.y).toDouble()) <= tol) {
            pendingFrames + 1
        } else {
            1
        }
        pending = cand
        if (pendingFrames < CONFIRM_FRAMES) return true
        if (now - lastTapMs < TAP_GAP_MS) return true

        var spot = spotAt(cand.x, cand.y, tol)
        if (spot == null) {
            spot = Spot(cand.x, cand.y)
            spots.add(spot)
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "AD: запомнил крестик ${cand.x.toInt()},${cand.y.toInt()} размер ${cand.sizePx.toInt()}px " +
                    "оценка ${"%.2f".format(cand.score)} контраст ${cand.contrast}"
            )
        } else if (goneFramesSinceTap >= GONE_FRAMES_FOR_NEW_AD) {
            // Между нажатиями крестик пропадал: это новая реклама на том же месте
            spot.taps = 0
        }

        if (spot.taps >= MAX_SPOT_TAPS) {
            spot.blocked = true
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "AD: крестик ${spot.x.toInt()},${spot.y.toInt()} не исчезает после ${spot.taps} нажатий — считаю ложным, больше не трогаю",
                isError = true
            )
            pending = null
            pendingFrames = 0
            return false
        }
        if (totalTaps >= MAX_TOTAL_TAPS) {
            finish("лимит нажатий")
            return false
        }

        onAction("Закрытие рекламы", "Нажатие на крестик ${cand.x.toInt()}, ${cand.y.toInt()}")
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "AD: тап по крестику ${cand.x.toInt()},${cand.y.toInt()} (всего: ${totalTaps + 1}, на этом месте: ${spot.taps + 1})"
        )
        val ok = tap(cand.x, cand.y)
        lastTapMs = clock()
        goneFramesSinceTap = 0
        if (ok) {
            spot.taps++
            totalTaps++
        } else {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "AD: тап по крестику не выполнен системой, повтор", isError = true)
        }
        pending = null
        pendingFrames = 0
        return true
    }

    private fun findCandidate(bitmap: Bitmap, screenW: Int, screenH: Int): AdCrossCandidate? {
        return try {
            if (bitmap.isRecycled) return null
            val roi = AdCrossDetector.roiRect(screenW, screenH)
            val w = roi[2] - roi[0]
            val h = roi[3] - roi[1]
            if (w < 16 || h < 16 || roi[2] > bitmap.width || roi[3] > bitmap.height) return null
            if (pixelBuf.size < w * h) pixelBuf = IntArray(w * h)
            bitmap.getPixels(pixelBuf, 0, w, roi[0], roi[1], w, h)
            AdCrossDetector.detect(pixelBuf, w, h, screenH / 800f, roi[0], roi[1])
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val now = clock()
            if (now - lastErrorLogMs >= 5000L) {
                lastErrorLogMs = now
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "AD: ошибка поиска крестика: ${t.javaClass.simpleName}: ${t.message}",
                    isError = true
                )
            }
            null
        }
    }
}
