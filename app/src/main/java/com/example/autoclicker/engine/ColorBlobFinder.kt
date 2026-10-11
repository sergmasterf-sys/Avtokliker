package com.example.autoclicker.engine

/**
 * Чистый Kotlin-класс для поиска цветных прямоугольных кнопок по связным компонентам.
 * Без зависимостей от Android SDK для максимальной производительности и возможности unit-тестирования на JVM.
 */
object ColorBlobFinder {

    data class Blob(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val pixelCount: Int,
        val effectivePixels: Int,
        val width: Int,
        val height: Int,
        val aspect: Float,
        val density: Float,
        val metric: Float
    ) {
        val centerX: Float get() = (minX + maxX) / 2f
        val centerY: Float get() = (minY + maxY) / 2f
    }

    data class Result(
        val best: Blob?,
        val bestRejected: Blob?,
        val allBlobs: List<Blob>
    )

    /**
     * Проверка желтого/золотистого цвета в HSV:
     * Hue: 35..60 градусов, Saturation >= 0.55, Value >= 0.55.
     * Правая половина кнопки "НАЧАТЬ" темнее (оранжево-жёлтая) и также проходит этот фильтр.
     */
    fun isYellowHsv(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, maxOf(g, b))
        if (max < 140) return false // value >= 0.55 (140 / 255 ≈ 0.55)
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        if (delta == 0) return false
        if (delta.toFloat() / max < 0.55f) return false // saturation >= 0.55

        val hue = when (max) {
            r -> {
                val h = 60f * (g - b).toFloat() / delta
                if (h < 0f) h + 360f else h
            }
            g -> 60f * (((b - r).toFloat() / delta) + 2f)
            else -> 60f * (((r - g).toFloat() / delta) + 4f)
        }
        return hue in 35f..60f
    }

    /**
     * Проверка синего цвета в HSV:
     * Hue: 190..230 градусов, Saturation >= 0.45, Value >= 0.40.
     */
    fun isBlueHsv(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, maxOf(g, b))
        if (max < 102) return false // value >= 0.40 (102 / 255 = 0.40)
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        if (delta == 0) return false
        if (delta.toFloat() / max < 0.45f) return false // saturation >= 0.45

        val hue = when (max) {
            r -> {
                val h = 60f * (g - b).toFloat() / delta
                if (h < 0f) h + 360f else h
            }
            g -> 60f * (((b - r).toFloat() / delta) + 2f)
            else -> 60f * (((r - g).toFloat() / delta) + 4f)
        }
        return hue in 190f..230f
    }

    /**
     * Проверка бледного серо-голубого цвета кнопки «Продолжить» в HSV:
     * Hue: 180..210 градусов, Saturation: 0.15..0.40, Value: 0.50..0.90.
     */
    fun isPaleBlueHsv(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, maxOf(g, b))
        // Value: 0.50..0.90 (0.50 * 255 = 127, 0.90 * 255 = 229)
        val v = max / 255f
        if (v < 0.50f || v > 0.90f) return false
        val min = minOf(r, minOf(g, b))
        val delta = max - min
        if (delta == 0) return false
        val sat = delta.toFloat() / max
        if (sat < 0.15f || sat > 0.40f) return false

        val hue = when (max) {
            r -> {
                val h = 60f * (g - b).toFloat() / delta
                if (h < 0f) h + 360f else h
            }
            g -> 60f * (((b - r).toFloat() / delta) + 2f)
            else -> 60f * (((r - g).toFloat() / delta) + 4f)
        }
        return hue in 180f..210f
    }

    /**
     * Поиск связных компонент по сетке (step):
     * 1. Булева сетка по шагу step.
     * 2. Морфологическое закрытие 5x5 для устранения дырок от черного/темного текста внутри кнопки.
     * 3. Итеративная заливка компонент связности со стеком (без рекурсии, 4 соседа).
     * 4. Расчет рамки и плотности с учетом темных пикселей внутри рамки.
     * 5. Фильтрация и выбор лучшей компоненты по максимуму (плотность * площадь).
     */
    fun findBlobs(
        pixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 2,
        minAllowedW: Int,
        maxAllowedW: Int,
        minAllowedH: Int,
        maxAllowedH: Int,
        minAspect: Float = 1.4f,
        maxAspect: Float = 6.5f,
        minDensity: Float = 0.45f,
        isColor: (r: Int, g: Int, b: Int) -> Boolean
    ): Result {
        if (width < 10 || height < 10 || step < 1) {
            return Result(null, null, emptyList())
        }

        val gridW = width / step
        val gridH = height / step
        if (gridW < 4 || gridH < 4) {
            return Result(null, null, emptyList())
        }

        val rawMask = BooleanArray(gridW * gridH)
        var totalRaw = 0
        for (gy in 0 until gridH) {
            val pRow = (gy * step) * width
            val gRow = gy * gridW
            for (gx in 0 until gridW) {
                val p = pixels[pRow + (gx * step)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (isColor(r, g, b)) {
                    rawMask[gRow + gx] = true
                    totalRaw++
                }
            }
        }

        if (totalRaw < 10) {
            return Result(null, null, emptyList())
        }

        // Морфологическое закрытие 5x5: склеивание букв текста ("НАЧАТЬ", "ПРОДОЛЖИТЬ")
        val closedMask = BooleanArray(gridW * gridH)
        for (gy in 0 until gridH) {
            val gRow = gy * gridW
            val pRow = (gy * step) * width
            val yMin = maxOf(0, gy - 2)
            val yMax = minOf(gridH - 1, gy + 2)

            for (gx in 0 until gridW) {
                val idx = gRow + gx
                if (rawMask[idx]) {
                    closedMask[idx] = true
                    continue
                }

                val p = pixels[pRow + (gx * step)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val isDark = r < 60 && g < 60 && b < 60

                val xMin = maxOf(0, gx - 2)
                val xMax = minOf(gridW - 1, gx + 2)
                var colorNeighbors = 0
                for (ny in yMin..yMax) {
                    val nRow = ny * gridW
                    for (nx in xMin..xMax) {
                        if (rawMask[nRow + nx]) {
                            colorNeighbors++
                        }
                    }
                }

                if ((isDark && colorNeighbors >= 4) || colorNeighbors >= 11) {
                    closedMask[idx] = true
                }
            }
        }

        // Поиск связных компонент по 4 соседям (итеративный стек)
        val visited = BooleanArray(gridW * gridH)
        val stack = IntArray(gridW * gridH)
        val allBlobs = mutableListOf<Blob>()
        val validCandidates = mutableListOf<Blob>()

        for (gy in 0 until gridH) {
            val gRow = gy * gridW
            for (gx in 0 until gridW) {
                val startIdx = gRow + gx
                if (!closedMask[startIdx] || visited[startIdx]) continue

                visited[startIdx] = true
                stack[0] = startIdx
                var stackPtr = 1

                var cMinGx = gx
                var cMaxGx = gx
                var cMinGy = gy
                var cMaxGy = gy
                var cPixels = 0

                while (stackPtr > 0) {
                    val curr = stack[--stackPtr]
                    cPixels++
                    val cx = curr % gridW
                    val cy = curr / gridW

                    if (cx < cMinGx) cMinGx = cx
                    if (cx > cMaxGx) cMaxGx = cx
                    if (cy < cMinGy) cMinGy = cy
                    if (cy > cMaxGy) cMaxGy = cy

                    if (cx > 0) {
                        val n = curr - 1
                        if (closedMask[n] && !visited[n]) {
                            visited[n] = true
                            stack[stackPtr++] = n
                        }
                    }
                    if (cx + 1 < gridW) {
                        val n = curr + 1
                        if (closedMask[n] && !visited[n]) {
                            visited[n] = true
                            stack[stackPtr++] = n
                        }
                    }
                    if (cy > 0) {
                        val n = curr - gridW
                        if (closedMask[n] && !visited[n]) {
                            visited[n] = true
                            stack[stackPtr++] = n
                        }
                    }
                    if (cy + 1 < gridH) {
                        val n = curr + gridW
                        if (closedMask[n] && !visited[n]) {
                            visited[n] = true
                            stack[stackPtr++] = n
                        }
                    }
                }

                if (cPixels < 8) continue

                val minX = cMinGx * step
                val maxX = cMaxGx * step
                val minY = cMinGy * step
                val maxY = cMaxGy * step
                val boxW = maxX - minX + 1
                val boxH = maxY - minY + 1
                val sampledW = cMaxGx - cMinGx + 1
                val sampledH = cMaxGy - cMinGy + 1
                val totalSampled = (sampledW * sampledH).coerceAtLeast(1)

                // Допуск темных пикселей (r,g,b < 60) внутри рамки компоненты при расчете плотности
                var darkCountInsideBox = 0
                for (cy in cMinGy..cMaxGy) {
                    val pRow = (cy * step) * width
                    val gR = cy * gridW
                    for (cx in cMinGx..cMaxGx) {
                        if (!closedMask[gR + cx]) {
                            val p = pixels[pRow + (cx * step)]
                            val r = (p shr 16) and 0xFF
                            val g = (p shr 8) and 0xFF
                            val b = p and 0xFF
                            if (r < 60 && g < 60 && b < 60) {
                                darkCountInsideBox++
                            }
                        }
                    }
                }

                val effectivePixels = (cPixels + darkCountInsideBox).coerceAtMost(totalSampled)
                val density = effectivePixels.toFloat() / totalSampled
                val aspect = boxW.toFloat() / boxH.coerceAtLeast(1)
                val metric = density * (boxW.toFloat() * boxH.toFloat())

                val blob = Blob(
                    minX = minX,
                    maxX = maxX,
                    minY = minY,
                    maxY = maxY,
                    pixelCount = cPixels,
                    effectivePixels = effectivePixels,
                    width = boxW,
                    height = boxH,
                    aspect = aspect,
                    density = density,
                    metric = metric
                )
                allBlobs.add(blob)

                if (boxW in minAllowedW..maxAllowedW && boxH in minAllowedH..maxAllowedH &&
                    aspect in minAspect..maxAspect && density >= minDensity
                ) {
                    validCandidates.add(blob)
                }
            }
        }

        val best = validCandidates.maxByOrNull { it.metric }
        val bestRejected = allBlobs.maxByOrNull { it.effectivePixels }
        return Result(best, bestRejected, allBlobs)
    }

    /**
     * Поиск желтой кнопки "НАЧАТЬ" по относительным размерам экрана.
     */
    fun findYellowButton(
        pixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 2,
        screenW: Int,
        screenH: Int
    ): Result {
        val minAllowedW = (screenW * 0.06f).toInt()
        val maxAllowedW = (screenW * 0.40f).toInt()
        val minAllowedH = (screenH * 0.03f).toInt()
        val maxAllowedH = (screenH * 0.22f).toInt()
        return findBlobs(
            pixels = pixels,
            width = width,
            height = height,
            step = step,
            minAllowedW = minAllowedW,
            maxAllowedW = maxAllowedW,
            minAllowedH = minAllowedH,
            maxAllowedH = maxAllowedH,
            minAspect = 1.4f,
            maxAspect = 6.5f,
            minDensity = 0.45f,
            isColor = ::isYellowHsv
        )
    }

    /**
     * Поиск синей кнопки "ПРОДОЛЖИТЬ" по относительным размерам экрана.
     */
    fun findBlueButton(
        pixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 2,
        screenW: Int,
        screenH: Int
    ): Result {
        val minAllowedW = (screenW * 0.05f).toInt()
        val maxAllowedW = (screenW * 0.35f).toInt()
        val minAllowedH = (screenH * 0.03f).toInt()
        val maxAllowedH = (screenH * 0.20f).toInt()
        return findBlobs(
            pixels = pixels,
            width = width,
            height = height,
            step = step,
            minAllowedW = minAllowedW,
            maxAllowedW = maxAllowedW,
            minAllowedH = minAllowedH,
            maxAllowedH = maxAllowedH,
            minAspect = 1.4f,
            maxAspect = 6.5f,
            minDensity = 0.45f,
            isColor = ::isBlueHsv
        )
    }

    /**
     * Поиск кнопки «Продолжить» (pale=true — бледная серо-голубая, pale=false — жёлтая MVP).
     * Фильтры: ширина 8%..22% экрана, высота 4%..9%, пропорция 2.2..5.0, minDensity 0.5.
     */
    fun findContinueButton(
        pixels: IntArray,
        width: Int,
        height: Int,
        step: Int = 2,
        screenW: Int,
        screenH: Int,
        pale: Boolean
    ): Result {
        val minAllowedW = (screenW * 0.08f).toInt()
        val maxAllowedW = (screenW * 0.22f).toInt()
        val minAllowedH = (screenH * 0.04f).toInt()
        val maxAllowedH = (screenH * 0.09f).toInt()
        return findBlobs(
            pixels = pixels,
            width = width,
            height = height,
            step = step,
            minAllowedW = minAllowedW,
            maxAllowedW = maxAllowedW,
            minAllowedH = minAllowedH,
            maxAllowedH = maxAllowedH,
            minAspect = 2.2f,
            maxAspect = 5.0f,
            minDensity = 0.5f,
            isColor = if (pale) ::isPaleBlueHsv else ::isYellowHsv
        )
    }
}
