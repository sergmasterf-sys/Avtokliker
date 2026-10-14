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
 * Улучшения v2: расширен диапазон размеров, снижен порог контраста для белых крестиков
 * на полупрозрачных рекламах, ROI сильнее прижат к правому верхнему углу.
 */
object AdCrossDetector {
    // Область поиска: правая верхняя часть (рекламный крестик почти всегда там)
    const val ROI_X0 = 0.55f
    const val ROI_X1 = 1.0f
    const val ROI_Y0 = 0.0f
    const val ROI_Y1 = 0.42f

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
 * Закрывает рекламные окна («обновление версии» и т.п.) в лобби PUBG:
 * ищет крестик в правом верхнем углу и нажимает на него, пока крестики есть
 * (окон может быть 2 и больше). Когда крестиков не осталось, [step] возвращает false,
 * и обычная логика (кнопка «НАЧАТЬ») продолжается.
 *
 * Защита от ложных нажатий:
 *  - крестик должен быть виден на [CONFIRM_FRAMES] кадрах подряд в одной точке;
 *  - между нажатиями минимум [TAP_GAP_MS];
 *  - если после [MAX_SAME_SPOT_TAPS] нажатий на одну точку крестик не исчез, точка в чёрном списке на [BLACKLIST_MS];
 *  - не больше [MAX_SERIES_TAPS] нажатий подряд, потом пауза [SERIES_PAUSE_MS].
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
        const val CONFIRM_FRAMES = 2
        const val TAP_GAP_MS = 700L
        const val SETTLE_AFTER_TAP_MS = 1400L   // после нажатия ждём анимацию закрытия / появление следующей рекламы
        const val MAX_SAME_SPOT_TAPS = 3
        const val BLACKLIST_MS = 45_000L
        const val MAX_SERIES_TAPS = 12
        const val SERIES_PAUSE_MS = 25_000L
        private const val SPOT_TOL_FRAC_OF_H = 0.05f
    }

    private class Spot(val x: Float, val y: Float, val untilMs: Long)

    private var pixelBuf = IntArray(0)

    private var pending: AdCrossCandidate? = null
    private var pendingFrames = 0
    private var lastTapMs = 0L
    private var lastTapX = -1f
    private var lastTapY = -1f
    private var sameSpotTaps = 0
    private var seriesTaps = 0
    private var pausedUntilMs = 0L
    private val blacklist = ArrayList<Spot>()
    private var lastErrorLogMs = 0L

    fun reset() {
        pending = null
        pendingFrames = 0
        sameSpotTaps = 0
        seriesTaps = 0
        lastTapX = -1f
        lastTapY = -1f
    }

    /**
     * Обрабатывает один кадр. true — кадр «занят рекламой» (нажали крестик, ждём подтверждения
     * или закрытия окна): остальную логику на этом кадре пропустить. false — рекламы нет.
     */
    suspend fun step(bitmap: Bitmap, screenW: Int, screenH: Int): Boolean {
        val now = clock()
        if (now < pausedUntilMs) return false

        var cand = findCandidate(bitmap, screenW, screenH)
        val tol = screenH * SPOT_TOL_FRAC_OF_H
        blacklist.removeAll { it.untilMs <= now }
        if (cand != null && blacklist.any { hypot((it.x - cand!!.x).toDouble(), (it.y - cand!!.y).toDouble()) <= tol }) {
            cand = null
        }

        if (cand == null) {
            pending = null
            pendingFrames = 0
            if (seriesTaps > 0) {
                if (now - lastTapMs < SETTLE_AFTER_TAP_MS) return true // ждём закрытия / следующее окно
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "AD: реклама закрыта (нажато крестиков: $seriesTaps), продолжаю обычную логику"
                )
                seriesTaps = 0
                sameSpotTaps = 0
                lastTapX = -1f
                lastTapY = -1f
            }
            return false
        }

        val prev = pending
        if (prev != null && hypot((prev.x - cand.x).toDouble(), (prev.y - cand.y).toDouble()) <= tol) {
            pendingFrames++
        } else {
            pendingFrames = 1
        }
        pending = cand
        if (pendingFrames < CONFIRM_FRAMES) return true
        if (now - lastTapMs < TAP_GAP_MS) return true

        val repeat = lastTapX >= 0f &&
            hypot((lastTapX - cand.x).toDouble(), (lastTapY - cand.y).toDouble()) <= tol
        val tapsOnSpot = if (repeat) sameSpotTaps else 0
        if (tapsOnSpot >= MAX_SAME_SPOT_TAPS) {
            blacklist.add(Spot(cand.x, cand.y, now + BLACKLIST_MS))
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "AD: крестик ${cand.x.toInt()},${cand.y.toInt()} не исчезает после $tapsOnSpot нажатий — считаю ложным, игнорирую ${BLACKLIST_MS / 1000}с",
                isError = true
            )
            pending = null
            pendingFrames = 0
            sameSpotTaps = 0
            lastTapX = -1f
            lastTapY = -1f
            return false
        }
        if (seriesTaps >= MAX_SERIES_TAPS) {
            pausedUntilMs = now + SERIES_PAUSE_MS
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "AD: $seriesTaps нажатий подряд, реклама не заканчивается — пауза ${SERIES_PAUSE_MS / 1000}с",
                isError = true
            )
            seriesTaps = 0
            reset()
            return false
        }

        onAction("Закрытие рекламы", "Нажатие на крестик ${cand.x.toInt()}, ${cand.y.toInt()}")
        EventLogManager.log(
            EventLogManager.TAG_AUTO_CLICKER,
            "AD: найден крестик ${cand.x.toInt()},${cand.y.toInt()} размер ${cand.sizePx.toInt()}px " +
                "оценка ${"%.2f".format(cand.score)} контраст ${cand.contrast} -> тап (подряд: ${seriesTaps + 1}, на этой точке: ${tapsOnSpot + 1})"
        )
        val ok = tap(cand.x, cand.y)
        lastTapMs = clock()
        if (ok) {
            seriesTaps++
            sameSpotTaps = tapsOnSpot + 1
            lastTapX = cand.x
            lastTapY = cand.y
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
