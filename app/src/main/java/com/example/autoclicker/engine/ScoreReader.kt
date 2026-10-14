package com.example.autoclicker.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.example.autoclicker.data.EventLogManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Один распознанный фрагмент текста: центр и размеры в пикселях ЭКРАНА (не обрезка).
 * [width] и [height] по умолчанию 0f для обратной совместимости.
 */
data class OcrToken(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float = 0f,
    val height: Float = 0f
)

/**
 * Счёт матча из верхней панели PUBG Mobile: [left] — синяя команда, [right] — красная,
 * [target] — число после «Цель:», [timerSeconds] — оставшееся время (мм:сс под «Цель»).
 * Любое поле может быть null, если его не удалось прочитать.
 */
data class MatchScore(
    val left: Int?,
    val right: Int?,
    val target: Int?,
    val timerSeconds: Int? = null
) {
    val max: Int get() = maxOf(left ?: -1, right ?: -1)
    override fun toString(): String {
        val tStr = if (timerSeconds != null) " timer ${timerSeconds / 60}:${(timerSeconds % 60).toString().padStart(2, '0')}" else ""
        return "${left ?: "?"}:${right ?: "?"} цель ${target ?: "?"}$tStr"
    }
}

/**
 * Чтение счёта по ЦИФРАМ (а не по цвету плашек) с независимыми кропами для каждой стороны,
 * предобработкой (бинаризация, дилатация штрихов) и временной стабилизацией через [ScoreTracker].
 *
 * Все размеры считаются в «единицах высоты 800 px»: u = пиксели / (высота_экрана / 800).
 * Панель счёта прижата к центру верхнего края, а её размер растёт вместе с высотой экрана,
 * поэтому геометрия одинакова на 8", 10", 12", 13" и на телефонах с любым соотношением сторон.
 */
class ScoreReader {

    companion object {
        const val HALF_WIDTH_OF_HEIGHT = 0.30f
        const val HEIGHT_OF_HEIGHT = 0.085f

        const val SIDE_MIN_U = 80f
        const val SIDE_MAX_U = 205f
        const val TARGET_MAX_ABS_U = 70f
        const val TARGET_MAX_Y_U = 28f
        const val BANNER_HEIGHT_U = 68f
        const val MAX_PLAUSIBLE_SCORE = 150

        /**
         * Приводит типичные ошибки OCR к цифрам: «I2» -> «12», «O» -> «0».
         * Счёт в TDM 0..40, поэтому длина токена строго <= 2 символов.
         * null, если это не число или слово (например «Цель» или таймер «08:26»).
         */
        fun normalizeDigits(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty() || t.length > 2) return null
            val sb = StringBuilder()
            for (ch in t) {
                val m = when (ch) {
                    in '0'..'9' -> ch
                    'O', 'o', 'D', 'Q' -> '0'
                    'I', 'l', '|', 'i', '!' -> '1'
                    'S', 's' -> '5'
                    'B' -> '8'
                    'Z', 'z' -> '2'
                    else -> return null
                }
                sb.append(m)
            }
            return sb.toString()
        }

        /** Извлекает число цели (10..200) из текста вида «40» или «Цель: 40». */
        fun extractTargetDigits(raw: String): Int? {
            val t = raw.trim()
            val digitsOnly = normalizeDigits(t)
            val asInt = digitsOnly?.toIntOrNull()
            if (asInt != null && asInt in 10..200) return asInt

            val match = Regex("""\b(\d{2,3})\b""").find(t)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null && num in 10..200) return num
            }
            return null
        }

        /**
         * Фильтрует токены одной стороны (синей или красной):
         * 1. Нормализация цифр (длина <= 2).
         * 2. Отбрасывание токенов с высотой рамки, отличающейся > 40% от медианной.
         * 3. Защита от склеивания далеко стоящих по X токенов (> 32u).
         */
        fun filterSideTokens(tokens: List<OcrToken>, unit: Float): Int? {
            if (tokens.isEmpty()) return null

            val valid = ArrayList<Pair<OcrToken, String>>()
            for (t in tokens) {
                val digits = normalizeDigits(t.text) ?: continue
                valid.add(t to digits)
            }
            if (valid.isEmpty()) return null

            // Фильтр рамки по высоте (только если известна высота элементов)
            val withHeight = valid.filter { it.first.height > 0f }
            val filtered = if (withHeight.size >= 2) {
                val heights = withHeight.map { it.first.height }.sorted()
                val medianH = heights[heights.size / 2]
                valid.filter { (tok, _) ->
                    tok.height <= 0f || kotlin.math.abs(tok.height - medianH) <= 0.40f * medianH
                }
            } else {
                valid
            }
            if (filtered.isEmpty()) return null

            val sorted = filtered.sortedBy { it.first.cx }
            if (sorted.size == 1) {
                val v = sorted[0].second.toIntOrNull() ?: return null
                return if (v in 0..MAX_PLAUSIBLE_SCORE) v else null
            }

            // Проверка расстояния между цифрами по X
            val uStep = if (unit > 0f) unit else 1f
            val distU = kotlin.math.abs(sorted[1].first.cx - sorted[0].first.cx) / uStep
            if (distU > 32f) {
                // Токены стоят слишком далеко друг от друга (разные надписи), не склеиваем
                val v1 = sorted[0].second.toIntOrNull()
                val v2 = sorted[1].second.toIntOrNull()
                return when {
                    v1 != null && v2 != null -> if (sorted[0].first.height >= sorted[1].first.height) v1 else v2
                    v1 != null -> v1
                    else -> v2
                }
            }

            val mergedStr = sorted.joinToString("") { it.second }
            if (mergedStr.length > 3) return null
            val v = mergedStr.toIntOrNull() ?: return null
            return if (v in 0..MAX_PLAUSIBLE_SCORE) v else null
        }

        fun parseTargetTokens(tokens: List<OcrToken>): Int? {
            for (t in tokens) {
                val v = extractTargetDigits(t.text)
                if (v != null && v in 10..200) return v
            }
            return null
        }

        fun parseTimerSeconds(tokens: List<OcrToken>): Int? {
            val regex = Regex("""(?:^|\D)(\d{1,2}):(\d{2})(?:\D|$)""")
            for (t in tokens) {
                val match = regex.find(t.text) ?: continue
                val mm = match.groupValues[1].toIntOrNull() ?: continue
                val ss = match.groupValues[2].toIntOrNull() ?: continue
                if (mm in 0..15 && ss in 0..59) {
                    return mm * 60 + ss
                }
            }
            return null
        }

        /**
         * Чистая функция (без Android), её проверяет юнит-тест.
         * [tokens] — фрагменты текста с центрами в пикселях экрана.
         * Возвращает null, если панели счёта на экране нет.
         */
        fun parseScoreTokens(tokens: List<OcrToken>, screenW: Int, screenH: Int): MatchScore? {
            val unit = screenH / 800f
            if (unit <= 0f) return null
            val centerX = screenW / 2f

            val leftTokens = ArrayList<OcrToken>()
            val rightTokens = ArrayList<OcrToken>()
            val centerTokens = ArrayList<OcrToken>()
            var target: Int? = null

            for (t in tokens) {
                val u = (t.cx - centerX) / unit
                val yU = t.cy / unit
                val absU = kotlin.math.abs(u)
                when {
                    absU in SIDE_MIN_U..SIDE_MAX_U -> {
                        if (u < 0f) leftTokens.add(t) else rightTokens.add(t)
                    }
                    absU <= TARGET_MAX_ABS_U && yU <= BANNER_HEIGHT_U -> {
                        centerTokens.add(t)
                        if (target == null) {
                            val v = extractTargetDigits(t.text)
                            if (v != null && v in 10..200) target = v
                        }
                    }
                }
            }

            val l = filterSideTokens(leftTokens, unit)
            val r = filterSideTokens(rightTokens, unit)
            val timer = parseTimerSeconds(centerTokens)
            if (l == null && r == null && target == null) return null
            return MatchScore(l, r, target, timer)
        }

        fun leftScoreRect(screenW: Int, screenH: Int): IntArray {
            val unit = screenH / 800f
            val centerX = screenW / 2f
            val x0 = (centerX - SIDE_MAX_U * unit).toInt().coerceIn(0, screenW)
            val x1 = (centerX - SIDE_MIN_U * unit).toInt().coerceIn(0, screenW)
            val y1 = (BANNER_HEIGHT_U * unit).toInt().coerceIn(8, screenH)
            return intArrayOf(x0, 0, x1, y1)
        }

        fun rightScoreRect(screenW: Int, screenH: Int): IntArray {
            val unit = screenH / 800f
            val centerX = screenW / 2f
            val x0 = (centerX + SIDE_MIN_U * unit).toInt().coerceIn(0, screenW)
            val x1 = (centerX + SIDE_MAX_U * unit).toInt().coerceIn(0, screenW)
            val y1 = (BANNER_HEIGHT_U * unit).toInt().coerceIn(8, screenH)
            return intArrayOf(x0, 0, x1, y1)
        }

        fun centerTargetRect(screenW: Int, screenH: Int): IntArray {
            val unit = screenH / 800f
            val centerX = screenW / 2f
            val x0 = (centerX - TARGET_MAX_ABS_U * unit).toInt().coerceIn(0, screenW)
            val x1 = (centerX + TARGET_MAX_ABS_U * unit).toInt().coerceIn(0, screenW)
            val y1 = (BANNER_HEIGHT_U * unit).toInt().coerceIn(8, screenH)
            return intArrayOf(x0, 0, x1, y1)
        }

        fun scoreRect(screenW: Int, screenH: Int): IntArray {
            val half = (HALF_WIDTH_OF_HEIGHT * screenH).toInt()
            val left = (screenW / 2 - half).coerceAtLeast(0)
            val right = (screenW / 2 + half).coerceAtMost(screenW)
            val bottom = (HEIGHT_OF_HEIGHT * screenH).toInt().coerceIn(8, screenH)
            return intArrayOf(left, 0, right, bottom)
        }
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Временной фильтр для сглаживания счёта и восстановления потерянных цифр. */
    val tracker = ScoreTracker()

    /** Сброс фильтра при начале нового матча или переходе в лобби. */
    fun resetTracker() {
        tracker.reset()
    }

    @Volatile
    var lastRawText: String = ""
        private set

    // Статистика производительности и ошибок
    private var statsLastLogMs = 0L
    private var statsTotalReads = 0
    private var statsTotalDurationMs = 0L
    private var statsLeftFailures = 0
    private var statsRightFailures = 0
    private var statsCenterFailures = 0

    /**
     * Читает счёт с кадра:
     * 1. Читает левую плашку (синие), правую (красные) и центр («Цель: 40») НЕЗАВИСИМО.
     * 2. Если хотя бы один элемент виден, собирает MatchScore и стабилизирует через [ScoreTracker].
     * 3. Если ни левая, ни правая, ни цель не прочитались — возвращает null (панели на экране нет).
     * 4. Замеряет время работы (требование <= 80мс) и логирует статистику каждые 15 сек.
     */
    suspend fun read(bitmap: Bitmap): MatchScore? {
        val sW = bitmap.width
        val sH = bitmap.height
        val t0 = SystemClock.uptimeMillis()

        val leftRect = leftScoreRect(sW, sH)
        val rightRect = rightScoreRect(sW, sH)
        val centerRect = centerTargetRect(sW, sH)

        val (leftScore, leftTokens) = recognizeSideWithFallback(bitmap, leftRect, sH)
        val (rightScore, rightTokens) = recognizeSideWithFallback(bitmap, rightRect, sH)
        val center = recognizeCenterWithFallback(bitmap, centerRect, sH)
        val targetScore = center.target
        val timerSeconds = center.timerSeconds
        val centerTokens = center.tokens

        val duration = SystemClock.uptimeMillis() - t0

        val allTokens = leftTokens + centerTokens + rightTokens
        lastRawText = allTokens.joinToString(" ") { it.text }

        recordMetrics(duration, leftScore != null, rightScore != null, targetScore != null, t0)

        // Панели счёта нет на экране
        if (leftScore == null && rightScore == null && targetScore == null) {
            return null
        }

        val rawScore = MatchScore(leftScore, rightScore, targetScore, timerSeconds)
        return tracker.update(rawScore)
    }

    private fun recordMetrics(
        duration: Long,
        leftOk: Boolean,
        rightOk: Boolean,
        centerOk: Boolean,
        now: Long
    ) {
        statsTotalReads++
        statsTotalDurationMs += duration
        if (!leftOk) statsLeftFailures++
        if (!rightOk) statsRightFailures++
        if (!centerOk) statsCenterFailures++

        if (now - statsLastLogMs >= 15_000L && statsTotalReads > 0) {
            val avgMs = statsTotalDurationMs / statsTotalReads
            val lFailPct = (statsLeftFailures * 100) / statsTotalReads
            val rFailPct = (statsRightFailures * 100) / statsTotalReads
            val cFailPct = (statsCenterFailures * 100) / statsTotalReads
            EventLogManager.log(
                EventLogManager.TAG_AUTO_CLICKER,
                "SCORE_STATS: n=$statsTotalReads avg=${avgMs}ms | Fails: L=$lFailPct% R=$rFailPct% C=$cFailPct%"
            )
            statsLastLogMs = now
            statsTotalReads = 0
            statsTotalDurationMs = 0L
            statsLeftFailures = 0
            statsRightFailures = 0
            statsCenterFailures = 0
        }
    }

    private suspend fun recognizeSideWithFallback(
        bitmap: Bitmap,
        rect: IntArray,
        screenH: Int
    ): Pair<Int?, List<OcrToken>> {
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return null to emptyList()

        val scale = ((800f / screenH) * 3.5f).coerceIn(1.5f, 4.0f)
        val sw = (w * scale).toInt().coerceIn(16, 600)
        val sh = (h * scale).toInt().coerceIn(16, 400)

        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        var scaled: Bitmap? = null
        var prep: Bitmap? = null
        var inv: Bitmap? = null
        try {
            scaled = if (scale > 1.05f) Bitmap.createScaledBitmap(crop, sw, sh, true) else crop

            // Попытка 1: Предобработанный (чёрные цифры на белом фоне)
            prep = createBinaryBitmap(scaled, invert = false)
            val tokens1 = recognizeTokens(prep, left, top, scale)
            val score1 = filterSideTokens(tokens1, screenH / 800f)
            if (score1 != null) {
                return score1 to tokens1
            }

            // Попытка 2: Инвертированный (белые цифры на чёрном фоне)
            inv = createBinaryBitmap(scaled, invert = true)
            val tokens2 = recognizeTokens(inv, left, top, scale)
            val score2 = filterSideTokens(tokens2, screenH / 800f)
            if (score2 != null) {
                return score2 to tokens2
            }

            // Попытка 3: Сырой масштабированный кроп
            val tokens3 = recognizeTokens(scaled, left, top, scale)
            val score3 = filterSideTokens(tokens3, screenH / 800f)
            return score3 to tokens3
        } finally {
            prep?.recycle()
            inv?.recycle()
            if (scaled != null && scaled !== crop) scaled.recycle()
            crop.recycle()
        }
    }

    data class CenterResult(val target: Int?, val timerSeconds: Int?, val tokens: List<OcrToken>)

    private suspend fun recognizeCenterWithFallback(
        bitmap: Bitmap,
        rect: IntArray,
        screenH: Int
    ): CenterResult {
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return CenterResult(null, null, emptyList())

        val scale = ((800f / screenH) * 2.5f).coerceIn(1.2f, 3.5f)
        val sw = (w * scale).toInt().coerceIn(16, 600)
        val sh = (h * scale).toInt().coerceIn(16, 300)

        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        var scaled: Bitmap? = null
        var prep: Bitmap? = null
        try {
            scaled = if (scale > 1.05f) Bitmap.createScaledBitmap(crop, sw, sh, true) else crop

            prep = createBinaryBitmap(scaled, invert = false)
            val tokens1 = recognizeTokens(prep, left, top, scale)
            val target1 = parseTargetTokens(tokens1)
            val timer1 = parseTimerSeconds(tokens1)
            if (target1 != null && timer1 != null) {
                return CenterResult(target1, timer1, tokens1)
            }

            val tokens2 = recognizeTokens(scaled, left, top, scale)
            val target2 = target1 ?: parseTargetTokens(tokens2)
            val timer2 = timer1 ?: parseTimerSeconds(tokens2)
            val combined = if (tokens2.isNotEmpty()) tokens2 else tokens1
            return CenterResult(target2, timer2, combined)
        } finally {
            prep?.recycle()
            if (scaled != null && scaled !== crop) scaled.recycle()
            crop.recycle()
        }
    }

    private var lastDebugSaveMs = 0L

    /**
     * Отладочное сохранение кропа панели при ошибке чтения (null или не прочитана одна сторона).
     * Сохраняет исходный и бинаризованный кроп в getExternalFilesDir/score_debug/.
     * Ограничения: не чаще раза в 5 секунд, не более 20 файлов в папке.
     */
    fun maybeSaveDebugCrop(
        context: Context,
        screenBitmap: Bitmap,
        score: MatchScore?,
        isDebugEnabled: Boolean,
        now: Long = System.currentTimeMillis()
    ) {
        if (!isDebugEnabled) return
        val isFail = score == null || score.left == null || score.right == null
        if (!isFail) return

        if (now - lastDebugSaveMs < 5000L) return
        lastDebugSaveMs = now

        val sW = screenBitmap.width
        val sH = screenBitmap.height
        val rect = scoreRect(sW, sH)
        val left = rect[0]
        val top = rect[1]
        val right = rect[2]
        val bottom = rect[3]
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return

        val crop = try {
            Bitmap.createBitmap(screenBitmap, left, top, w, h)
        } catch (_: Throwable) {
            return
        }

        val prep = try {
            createBinaryBitmap(crop, invert = false)
        } catch (_: Throwable) {
            crop.recycle()
            return
        }

        val rawTextCopy = lastRawText
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dir = File(context.getExternalFilesDir(null), "score_debug")
                if (!dir.exists()) dir.mkdirs()

                val sdf = SimpleDateFormat("HHmmss_SSS", Locale.US)
                val timeStr = sdf.format(Date(now))
                val rawClean = rawTextCopy.replace(Regex("""[^a-zA-Z0-9а-яА-Я_]+"""), "_").trim('_').take(30)
                val baseName = if (rawClean.isEmpty()) "${timeStr}_no_ocr" else "${timeStr}_${rawClean}"

                val rawFile = File(dir, "${baseName}_raw.png")
                val prepFile = File(dir, "${baseName}_prep.png")

                FileOutputStream(rawFile).use { out ->
                    crop.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                FileOutputStream(prepFile).use { out ->
                    prep.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                val allFiles = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
                if (allFiles.size > 20) {
                    val toDelete = allFiles.take(allFiles.size - 20)
                    toDelete.forEach { it.delete() }
                }
            } catch (e: Exception) {
                EventLogManager.log(
                    EventLogManager.TAG_AUTO_CLICKER,
                    "SCORE_DEBUG: ошибка сохранения кропа: ${e.message}",
                    isError = true
                )
            } finally {
                crop.recycle()
                prep.recycle()
            }
        }
    }

    /**
     * Создаёт бинарное изображение с 1-пиксельной дилатацией штрихов.
     * Выделяет белые пиксели: высокая яркость (max >= 155), низкая насыщенность (diff <= 55).
     */
    private fun createBinaryBitmap(scaled: Bitmap, invert: Boolean): Bitmap {
        val sw = scaled.width
        val sh = scaled.height
        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)

        val mask = BooleanArray(sw * sh)
        for (i in 0 until sw * sh) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val max = maxOf(r, maxOf(g, b))
            val min = minOf(r, minOf(g, b))
            val diff = max - min
            mask[i] = max >= 155 && diff <= 55
        }

        // 1-пиксельное расширение (дилатация)
        val dilated = BooleanArray(sw * sh)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until sw) {
                val i = row + x
                if (mask[i]) {
                    dilated[i] = true
                    if (x > 0) dilated[i - 1] = true
                    if (x + 1 < sw) dilated[i + 1] = true
                    if (y > 0) dilated[i - sw] = true
                    if (y + 1 < sh) dilated[i + sw] = true
                }
            }
        }

        val outPixels = IntArray(sw * sh)
        val textPixel = if (invert) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        val bgPixel = if (invert) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        for (i in 0 until sw * sh) {
            outPixels[i] = if (dilated[i]) textPixel else bgPixel
        }

        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, sw, 0, 0, sw, sh)
        return out
    }

    private suspend fun recognizeTokens(
        bmp: Bitmap,
        cropLeft: Int,
        cropTop: Int,
        scale: Float
    ): List<OcrToken> {
        val text = recognize(bmp)
        val out = ArrayList<OcrToken>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                for (el in line.elements) {
                    val bb = el.boundingBox ?: continue
                    val w = bb.width() / scale
                    val h = bb.height() / scale
                    out.add(
                        OcrToken(
                            text = el.text,
                            cx = cropLeft + bb.exactCenterX() / scale,
                            cy = cropTop + bb.exactCenterY() / scale,
                            width = w,
                            height = h
                        )
                    )
                }
            }
        }
        return out
    }

    suspend fun readText(bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): String {
        val sW = bitmap.width
        val sH = bitmap.height
        val left = (sW * x0).toInt().coerceIn(0, sW - 2)
        val top = (sH * y0).toInt().coerceIn(0, sH - 2)
        val right = (sW * x1).toInt().coerceIn(left + 2, sW)
        val bottom = (sH * y1).toInt().coerceIn(top + 2, sH)
        val rect = intArrayOf(left, top, right, bottom)
        val (_, tokens) = recognizeSideWithFallback(bitmap, rect, sH)
        return tokens.joinToString(" ") { it.text }
    }

    private suspend fun recognize(bmp: Bitmap): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    }

    fun close() {
        try {
            recognizer.close()
        } catch (_: Throwable) {
        }
    }
}
