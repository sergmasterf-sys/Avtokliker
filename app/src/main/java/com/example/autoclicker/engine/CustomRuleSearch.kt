package com.example.autoclicker.engine

import android.graphics.Bitmap
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SmartImageLoader
import com.example.autoclicker.data.SmartRule
import java.io.File
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CustomEval(
    val score: Float,
    val absoluteScale: Float,
    val x: Float,
    val y: Float,
    val timedOut: Boolean
)

data class RoiSearchResult(
    val score: Float,
    val clickX: Float,
    val clickY: Float,
    val timedOut: Boolean
)

data class CustomTemplateVariant(
    val scale: Float,
    val factor: Int,
    val tDownW: Int,
    val tDownH: Int,
    val tDiff: FloatArray,
    val sigmaT: Double
)

class PreparedCustomImage(
    val index: Int,
    val bitmap: Bitmap,
    val scaleRange: FloatArray,
    val sampleSize: Int = 1
)

class PreparedCustomRule(
    val rule: SmartRule,
    val images: List<PreparedCustomImage>,
    val errors: List<String>
)

object CustomRuleSearch {

    class SimpleLruMap<K, V>(
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
        fun containsKey(key: K): Boolean = map.containsKey(key)

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
    }

    private val cacheLock = Any()
    private val variantsCache = SimpleLruMap<String, CustomTemplateVariant?>(40)
    private val loggedSmallTemplates = Collections.synchronizedSet(mutableSetOf<String>())
    private var cachedPreparedRules: List<PreparedCustomRule>? = null
    private var cachedRulesKey: String = ""

    fun clearCaches() = synchronized(cacheLock) {
        variantsCache.clear()
        loggedSmallTemplates.clear()
        cachedPreparedRules?.forEach { rule ->
            rule.images.forEach { img ->
                if (!img.bitmap.isRecycled) {
                    img.bitmap.recycle()
                }
            }
        }
        cachedPreparedRules = null
        cachedRulesKey = ""
    }

    /**
     * Очистка только вычисленных кэшей шаблонов (без освобождения используемых bitmap).
     */
    fun clearTemplateCaches() = synchronized(cacheLock) {
        variantsCache.clear()
        loggedSmallTemplates.clear()
    }

    /**
     * 4.1. Геометрическая прогрессия масштабов.
     */
    fun scaleList(base: Float, min: Float, max: Float, count: Int = 9): List<Float> {
        if (count <= 1) return listOf(base * min)
        val ratio = (max / min).toDouble()
        return List(count) { i ->
            val power = i.toDouble() / (count - 1)
            (base * min * ratio.pow(power)).toFloat()
        }
    }

    /**
     * 4.2. ROI со сжатием блока factor x factor.
     */
    fun createDownsampledRoiN(
        screenshot: Bitmap,
        fracX0: Float,
        fracX1: Float,
        fracY0: Float,
        fracY1: Float,
        factor: Int = 2
    ): DownsampledRoi? {
        val screenW = screenshot.width
        val screenH = screenshot.height

        val roiX0 = (screenW * fracX0).toInt().coerceIn(0, screenW - 1)
        val roiX1 = (screenW * fracX1).toInt().coerceIn(roiX0 + 1, screenW)
        val roiY0 = (screenH * fracY0).toInt().coerceIn(0, screenH - 1)
        val roiY1 = (screenH * fracY1).toInt().coerceIn(roiY0 + 1, screenH)

        val rawW = roiX1 - roiX0
        val rawH = roiY1 - roiY0

        val evenW = rawW - (rawW % factor)
        val evenH = rawH - (rawH % factor)

        val downW = evenW / factor
        val downH = evenH / factor
        if (downW < 2 || downH < 2) return null

        val pixels = IntArray(evenW * evenH)
        screenshot.getPixels(pixels, 0, evenW, roiX0, roiY0, evenW, evenH)

        val gray = FloatArray(downW * downH)
        val factorSq = (factor * factor * 1000f)

        for (dy in 0 until downH) {
            val srcY0 = dy * factor
            val dstOffset = dy * downW
            for (dx in 0 until downW) {
                val srcX0 = dx * factor
                var blockSum = 0L
                for (fy in 0 until factor) {
                    val r = (srcY0 + fy) * evenW
                    for (fx in 0 until factor) {
                        val p = pixels[r + srcX0 + fx]
                        val g = ((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114
                        blockSum += g
                    }
                }
                gray[dstOffset + dx] = blockSum / factorSq
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
            intW = intW,
            factor = factor
        )
    }

    /**
     * 4.2. Поиск шаблона в ROI с шагом step и дедлайном.
     */
    fun searchTemplateInRoiN(
        roi: DownsampledRoi,
        variant: CustomTemplateVariant,
        step: Int,
        deadlineNanos: Long,
        refineMinScore: Float = 0.65f
    ): RoiSearchResult {
        val tW = variant.tDownW
        val tH = variant.tDownH
        if (roi.downW < tW || roi.downH < tH || variant.sigmaT <= 1e-4) {
            return RoiSearchResult(-1f, 0f, 0f, false)
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
        var timedOut = false

        var y = 0
        while (y <= maxSearchY) {
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true
                break
            }
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
                x += step
            }
            y += step
        }

        // Уточнение в окне ±2 клетки при шаге > 1
        if (!timedOut && bestScore >= refineMinScore && bestX >= 0 && bestY >= 0 && step > 1) {
            val rMinX = (bestX - 2).coerceAtLeast(0)
            val rMaxX = (bestX + 2).coerceAtMost(maxSearchX)
            val rMinY = (bestY - 2).coerceAtLeast(0)
            val rMaxY = (bestY + 2).coerceAtMost(maxSearchY)

            for (ry in rMinY..rMaxY) {
                if (System.nanoTime() > deadlineNanos) {
                    timedOut = true
                    break
                }
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

        val clickX = if (bestX >= 0) roi.roiX0 + (bestX + tW / 2f) * roi.factor else 0f
        val clickY = if (bestY >= 0) roi.roiY0 + (bestY + tH / 2f) * roi.factor else 0f

        return RoiSearchResult(bestScore, clickX, clickY, timedOut)
    }

    /**
     * 4.2а. Подготовка и кеширование варианта шаблона.
     */
    fun getOrComputeVariant(
        ruleId: String,
        imageIndex: Int,
        bmp: Bitmap,
        scale: Float,
        factor: Int
    ): CustomTemplateVariant? = synchronized(cacheLock) {
        val key = "${ruleId}_${imageIndex}_${String.format(Locale.US, "%.4f", scale)}_$factor"
        if (variantsCache.containsKey(key)) {
            return variantsCache[key]
        }
        val computed = run {
            val w = (bmp.width * scale).roundToInt()
            val h = (bmp.height * scale).roundToInt()
            if (minOf(w, h) < 12) {
                val warnKey = "${ruleId}_${String.format(Locale.US, "%.2f", scale)}"
                if (loggedSmallTemplates.add(warnKey)) {
                    EventLogManager.log(
                        EventLogManager.TAG_AUTO_CLICKER,
                        "SMART: предупреждение: шаблон $ruleId слишком мал при масштабе ${String.format(Locale.US, "%.2f", scale)}, масштаб пропущен"
                    )
                }
                return@run null
            }

            val downW = (w / factor).coerceAtLeast(2)
            val downH = (h / factor).coerceAtLeast(2)

            val scaledBmp = try {
                Bitmap.createScaledBitmap(bmp, downW, downH, true)
            } catch (t: Throwable) {
                return@run null
            }

            val pixels = IntArray(downW * downH)
            scaledBmp.getPixels(pixels, 0, downW, 0, 0, downW, downH)
            if (scaledBmp !== bmp && !scaledBmp.isRecycled) {
                scaledBmp.recycle()
            }

            val tGray = FloatArray(downW * downH)
            var sumT = 0.0
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val grayVal = (r * 299 + g * 587 + b * 114) / 1000f
                tGray[i] = grayVal
                sumT += grayVal
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

            CustomTemplateVariant(
                scale = scale,
                factor = factor,
                tDownW = downW,
                tDownH = downH,
                tDiff = tDiff,
                sigmaT = sigmaT
            )
        }
        variantsCache[key] = computed
        return computed
    }

    /**
     * 4.4. Подготовка пользовательских правил с отдельным кешем.
     */
    fun prepareCustomRules(
        rules: List<SmartRule>,
        configDir: File?
    ): List<PreparedCustomRule> = synchronized(cacheLock) {
        val dirLastMod = configDir?.lastModified() ?: 0L
        val currentKey = "${rules.hashCode()}_$dirLastMod"
        if (cachedPreparedRules != null && cachedRulesKey == currentKey) {
            val allValid = cachedPreparedRules!!.all { pr ->
                pr.images.all { !it.bitmap.isRecycled }
            }
            if (allValid) return cachedPreparedRules!!
        }

        clearCaches()

        val preparedList = mutableListOf<PreparedCustomRule>()
        for (rule in rules) {
            if (rule.builtin) continue

            val errors = mutableListOf<String>()
            val images = mutableListOf<PreparedCustomImage>()

            rule.allImages.forEachIndexed { idx, img ->
                val targetFileName = if (!img.file.isNullOrEmpty()) {
                    File(img.file).name
                } else if (!img.source.isNullOrEmpty()) {
                    File(img.source).name
                } else {
                    null
                }

                if (targetFileName == null) {
                    val err = "Правило '${rule.id}': не указан файл или источник изображения"
                    errors.add(err)
                    EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: $err", isError = true)
                    return@forEachIndexed
                }

                val targetDir = rule.configDir ?: configDir
                val file = if (targetDir != null) {
                    SmartImageLoader.resolve(targetDir, targetFileName)
                } else {
                    File(targetFileName).takeIf { it.exists() }
                }

                if (file == null || !file.exists()) {
                    val err = "В папке конфига отсутствует файл '$targetFileName' для правила '${rule.id}'"
                    errors.add(err)
                    EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: $err", isError = true)
                    return@forEachIndexed
                }

                val decodeRes = SmartImageLoader.decode(file)
                if (decodeRes.isFailure) {
                    val err = decodeRes.exceptionOrNull()?.message ?: "Не удалось прочитать картинку $targetFileName"
                    errors.add(err)
                    EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: $err", isError = true)
                    return@forEachIndexed
                }

                val decoded = decodeRes.getOrNull()
                val rawBmp = decoded?.bitmap
                val sampleSize = decoded?.sampleSize ?: 1
                if (rawBmp == null) {
                    val err = "Не удалось прочитать картинку $targetFileName"
                    errors.add(err)
                    EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: $err", isError = true)
                    return@forEachIndexed
                }

                val finalBmp: Bitmap? = if (img.source != null && img.box != null) {
                    val cropped = SmartImageLoader.cropByBox(rawBmp, img.box)
                    rawBmp.recycle()
                    if (cropped == null) {
                        val err = "Не удалось вырезать шаблон для правила '${rule.id}' из $targetFileName"
                        errors.add(err)
                        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: $err", isError = true)
                    }
                    cropped
                } else {
                    rawBmp
                }

                if (finalBmp != null) {
                    if (SmartImageLoader.isFlat(finalBmp)) {
                        val err = "шаблон ${rule.id} слишком однотонный"
                        errors.add(err)
                        EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: $err", isError = false)
                        finalBmp.recycle()
                    } else {
                        images.add(PreparedCustomImage(idx, finalBmp, img.scaleRange, sampleSize))
                    }
                }
            }

            preparedList.add(PreparedCustomRule(rule, images, errors))
        }

        cachedPreparedRules = preparedList
        cachedRulesKey = currentKey
        return preparedList
    }

    /**
     * 4.3. Поиск пользовательского правила (грубый проход + точный проход + общий дедлайн).
     */
    fun findCustomMatch(
        screenshot: Bitmap,
        rule: SmartRule,
        preparedRules: List<PreparedCustomRule>
    ): CustomEval {
        val prepared = preparedRules.firstOrNull { it.rule.id == rule.id }
        if (prepared == null || prepared.images.isEmpty()) {
            return CustomEval(score = -1f, absoluteScale = 0f, x = 0f, y = 0f, timedOut = false)
        }

        val deadline = System.nanoTime() + 1_200_000_000L

        val base = minOf(screenshot.width, screenshot.height).toFloat() / minOf(rule.refWidth, rule.refHeight)

        val screenIsLandscape = screenshot.width >= screenshot.height
        val refIsLandscape = rule.refWidth >= rule.refHeight
        val useWholeScreen = (rule.region == null || screenIsLandscape != refIsLandscape)
        val reg = if (useWholeScreen) floatArrayOf(0f, 0f, 1f, 1f) else rule.region!!

        val coarseRoi = createDownsampledRoiN(
            screenshot,
            fracX0 = reg[0],
            fracX1 = reg[2],
            fracY0 = reg[1],
            fracY1 = reg[3],
            factor = 4
        ) ?: return CustomEval(score = -1f, absoluteScale = 0f, x = 0f, y = 0f, timedOut = false)

        var bestCoarseScore = -1f
        var bestCoarseScale = 0f
        var bestCoarseX = 0f
        var bestCoarseY = 0f
        var bestCoarseImg: PreparedCustomImage? = null
        var coarseTimedOut = false

        for (img in prepared.images) {
            val imgBase = base * img.sampleSize
            val scales = scaleList(imgBase, img.scaleRange[0], img.scaleRange[1], 9)
            for (scale in scales) {
                if (System.nanoTime() > deadline) {
                    coarseTimedOut = true
                    break
                }
                val variant = getOrComputeVariant(rule.id, img.index, img.bitmap, scale, factor = 4) ?: continue
                val res = searchTemplateInRoiN(coarseRoi, variant, step = 2, deadline, refineMinScore = 0.5f)
                if (res.score > bestCoarseScore) {
                    bestCoarseScore = res.score
                    bestCoarseScale = scale
                    bestCoarseX = res.clickX
                    bestCoarseY = res.clickY
                    bestCoarseImg = img
                }
                if (res.timedOut) {
                    coarseTimedOut = true
                    break
                }
            }
            if (coarseTimedOut) break
        }

        if (coarseTimedOut || System.nanoTime() > deadline) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: поиск ${rule.id} прерван по времени")
            return CustomEval(
                score = bestCoarseScore,
                absoluteScale = bestCoarseScale,
                x = bestCoarseX,
                y = bestCoarseY,
                timedOut = true
            )
        }

        if (bestCoarseImg == null || bestCoarseScore < 0f) {
            return CustomEval(
                score = bestCoarseScore,
                absoluteScale = bestCoarseScale,
                x = bestCoarseX,
                y = bestCoarseY,
                timedOut = false
            )
        }

        // Точный проход
        val fineScales = listOf(bestCoarseScale * 0.85f, bestCoarseScale * 1.0f, bestCoarseScale * 1.15f)
        val maxW = fineScales.maxOf { (bestCoarseImg.bitmap.width * it).roundToInt() }
        val maxH = fineScales.maxOf { (bestCoarseImg.bitmap.height * it).roundToInt() }

        val spanX = (maxW / 2f + maxOf(0.12f * maxW, 8f)).roundToInt()
        val spanY = (maxH / 2f + maxOf(0.12f * maxH, 8f)).roundToInt()

        val finePixelX0 = (bestCoarseX - spanX).toInt().coerceIn(0, screenshot.width - 1)
        val finePixelX1 = (bestCoarseX + spanX).toInt().coerceIn(finePixelX0 + 1, screenshot.width)
        val finePixelY0 = (bestCoarseY - spanY).toInt().coerceIn(0, screenshot.height - 1)
        val finePixelY1 = (bestCoarseY + spanY).toInt().coerceIn(finePixelY0 + 1, screenshot.height)

        val fracX0 = finePixelX0.toFloat() / screenshot.width
        val fracX1 = finePixelX1.toFloat() / screenshot.width
        val fracY0 = finePixelY0.toFloat() / screenshot.height
        val fracY1 = finePixelY1.toFloat() / screenshot.height

        val fineRoi = createDownsampledRoiN(
            screenshot,
            fracX0 = fracX0,
            fracX1 = fracX1,
            fracY0 = fracY0,
            fracY1 = fracY1,
            factor = 2
        )

        if (fineRoi == null) {
            return CustomEval(
                score = bestCoarseScore,
                absoluteScale = bestCoarseScale,
                x = bestCoarseX,
                y = bestCoarseY,
                timedOut = false
            )
        }

        var bestFineScore = -1f
        var bestFineScale = bestCoarseScale
        var bestFineX = bestCoarseX
        var bestFineY = bestCoarseY
        var fineTimedOut = false

        for (scale in fineScales) {
            if (System.nanoTime() > deadline) {
                fineTimedOut = true
                break
            }
            val variant = getOrComputeVariant(rule.id, bestCoarseImg.index, bestCoarseImg.bitmap, scale, factor = 2) ?: continue
            val res = searchTemplateInRoiN(fineRoi, variant, step = 1, deadline, refineMinScore = 0.65f)
            if (res.score > bestFineScore) {
                bestFineScore = res.score
                bestFineScale = scale
                bestFineX = res.clickX
                bestFineY = res.clickY
            }
            if (res.timedOut) {
                fineTimedOut = true
                break
            }
        }

        if (fineTimedOut || System.nanoTime() > deadline) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: поиск ${rule.id} прерван по времени")
            return if (bestFineScore >= 0f) {
                CustomEval(bestFineScore, bestFineScale, bestFineX, bestFineY, true)
            } else {
                CustomEval(bestCoarseScore, bestCoarseScale, bestCoarseX, bestCoarseY, true)
            }
        }

        return if (bestFineScore >= 0f) {
            CustomEval(bestFineScore, bestFineScale, bestFineX, bestFineY, false)
        } else {
            CustomEval(bestCoarseScore, bestCoarseScale, bestCoarseX, bestCoarseY, false)
        }
    }
}
