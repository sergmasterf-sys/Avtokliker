package com.example.autoclicker.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Один распознанный фрагмент текста: центр в пикселях ЭКРАНА (не обрезка). */
data class OcrToken(val text: String, val cx: Float, val cy: Float)

/**
 * Счёт матча из верхней панели PUBG Mobile: [left] — синяя команда, [right] — красная,
 * [target] — число после «Цель:». Любое поле может быть null, если его не удалось прочитать.
 */
data class MatchScore(val left: Int?, val right: Int?, val target: Int?) {
    val max: Int get() = maxOf(left ?: -1, right ?: -1)
    override fun toString(): String = "${left ?: "?"}:${right ?: "?"} цель ${target ?: "?"}"
}

/**
 * Чтение счёта по ЦИФРАМ (а не по цвету плашек).
 *
 * Все размеры считаются в «единицах высоты 800 px»: u = пиксели / (высота_экрана / 800).
 * Панель счёта прижата к центру верхнего края, а её размер растёт вместе с высотой экрана,
 * поэтому геометрия одинакова на 8", 10", 12", 13" и на телефонах с любым соотношением сторон.
 *
 * На эталоне 1340x800: синий счёт примерно в u = -120, красный в u = +120,
 * «Цель: 40» по центру (|u| < 70, самая верхняя строка), таймер под ним.
 */
class ScoreReader {

    companion object {
        /** Полуширина области чтения от центра экрана, в долях высоты экрана. */
        const val HALF_WIDTH_OF_HEIGHT = 0.30f
        /** Высота области чтения (от верхнего края), в долях высоты экрана. */
        const val HEIGHT_OF_HEIGHT = 0.085f

        private const val SIDE_MIN_U = 80f
        private const val SIDE_MAX_U = 205f
        private const val TARGET_MAX_ABS_U = 70f
        private const val TARGET_MAX_Y_U = 27f
        private const val MAX_PLAUSIBLE_SCORE = 150

        /** Приводит типичные ошибки OCR к цифрам: «I2» -> «12», «O» -> «0». null, если это не число. */
        fun normalizeDigits(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty() || t.length > 4) return null
            if (t.none { it in '0'..'9' }) return null
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

        /**
         * Чистая функция (без Android), её проверяет юнит-тест.
         * [tokens] — фрагменты текста с центрами в пикселях экрана.
         * Возвращает null, если панели счёта на экране нет.
         */
        fun parseScoreTokens(tokens: List<OcrToken>, screenW: Int, screenH: Int): MatchScore? {
            val unit = screenH / 800f
            if (unit <= 0f) return null
            val centerX = screenW / 2f

            val leftParts = ArrayList<Pair<Float, String>>()
            val rightParts = ArrayList<Pair<Float, String>>()
            var target: Int? = null

            for (t in tokens) {
                val digits = normalizeDigits(t.text) ?: continue
                val u = (t.cx - centerX) / unit
                val yU = t.cy / unit
                val absU = kotlin.math.abs(u)
                when {
                    absU in SIDE_MIN_U..SIDE_MAX_U -> {
                        if (u < 0f) leftParts.add(t.cx to digits) else rightParts.add(t.cx to digits)
                    }
                    absU <= TARGET_MAX_ABS_U && yU <= TARGET_MAX_Y_U && digits.length >= 2 -> {
                        val v = digits.toIntOrNull()
                        if (v != null && v in 10..200) target = v
                    }
                }
            }

            fun join(parts: List<Pair<Float, String>>): Int? {
                if (parts.isEmpty()) return null
                val s = parts.sortedBy { it.first }.joinToString("") { it.second }
                if (s.length > 3) return null
                val v = s.toIntOrNull() ?: return null
                return if (v in 0..MAX_PLAUSIBLE_SCORE) v else null
            }

            val l = join(leftParts)
            val r = join(rightParts)
            if (l == null && r == null) return null
            return MatchScore(l, r, target)
        }

        /** Прямоугольник области счёта в пикселях экрана: [left, top, right, bottom]. */
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

    /** Что последний раз вернул OCR (для журнала, чтобы видеть, как читаются цифры). */
    @Volatile
    var lastRawText: String = ""
        private set

    /**
     * Читает счёт с кадра. null — панели счёта нет (лобби, результаты, загрузка).
     * Бросает исключение, если сам OCR не работает (нет модели, ошибка ML Kit).
     */
    suspend fun read(bitmap: Bitmap): MatchScore? {
        val sW = bitmap.width
        val sH = bitmap.height
        val rect = scoreRect(sW, sH)
        val tokens = recognizeRect(bitmap, rect[0], rect[1], rect[2], rect[3])
        lastRawText = tokens.joinToString(" ") { it.text }
        return parseScoreTokens(tokens, sW, sH)
    }

    /**
     * Весь текст в прямоугольнике (доли экрана). Нужен для журнала: что написано на кнопке «Подбор».
     */
    suspend fun readText(bitmap: Bitmap, x0: Float, y0: Float, x1: Float, y1: Float): String {
        val sW = bitmap.width
        val sH = bitmap.height
        val left = (sW * x0).toInt().coerceIn(0, sW - 2)
        val top = (sH * y0).toInt().coerceIn(0, sH - 2)
        val right = (sW * x1).toInt().coerceIn(left + 2, sW)
        val bottom = (sH * y1).toInt().coerceIn(top + 2, sH)
        return recognizeRect(bitmap, left, top, right, bottom).joinToString(" ") { it.text }
    }

    private suspend fun recognizeRect(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): List<OcrToken> {
        val w = right - left
        val h = bottom - top
        if (w < 8 || h < 8) return emptyList()

        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        // На маленьких экранах цифры мелкие: приводим к эквиваленту высоты 800 px (но не больше x2.5).
        val up = (800f / bitmap.height).coerceIn(1f, 2.5f)
        val work = if (up > 1.05f) {
            Bitmap.createScaledBitmap(crop, (w * up).toInt(), (h * up).toInt(), true)
        } else {
            crop
        }
        try {
            val text = recognize(work)
            val out = ArrayList<OcrToken>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    for (el in line.elements) {
                        val bb = el.boundingBox ?: continue
                        out.add(
                            OcrToken(
                                text = el.text,
                                cx = left + bb.exactCenterX() / up,
                                cy = top + bb.exactCenterY() / up
                            )
                        )
                    }
                }
            }
            return out
        } finally {
            if (work !== crop) work.recycle()
            crop.recycle()
        }
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
