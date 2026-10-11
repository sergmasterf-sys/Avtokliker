package com.example.autoclicker.data

import com.example.autoclicker.data.EventLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Режим работы умного режима.
 */
enum class SmartConfigMode {
    DEFAULT_ONLY, // Только встроенные правила (PUBG)
    CUSTOM_ONLY,  // Только выбранный пользовательский конфиг
    MERGED        // Встроенный + пользовательский (с заменой по id)
}

/**
 * Вариант картинки/шаблона внутри правила.
 */
data class SmartImage(
    val file: String? = null,
    val source: String? = null,
    val box: FloatArray? = null, // [left, top, right, bottom] в долях 0..1
    val scaleRange: FloatArray = floatArrayOf(0.6f, 1.6f) // [min, max]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SmartImage
        if (file != other.file) return false
        if (source != other.source) return false
        if (box != null) {
            if (other.box == null || !box.contentEquals(other.box)) return false
        } else if (other.box != null) return false
        if (!scaleRange.contentEquals(other.scaleRange)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = file?.hashCode() ?: 0
        result = 31 * result + (source?.hashCode() ?: 0)
        result = 31 * result + (box?.contentHashCode() ?: 0)
        result = 31 * result + scaleRange.contentHashCode()
        return result
    }
}

/**
 * Одно правило обнаружения и действия для Smart Mode.
 */
data class SmartRule(
    val id: String,
    val priority: Int,
    val imageFile: String? = null,
    val imageSource: String? = null,
    val imageBox: FloatArray? = null, // [left, top, right, bottom] в долях 0..1
    val region: FloatArray? = null,   // [left, top, right, bottom] в долях 0..1
    val threshold: Float = 0.80f,
    val tapTarget: String = "found",  // "found" или "fixed"
    val tapX: Float? = null,
    val tapY: Float? = null,
    val afterDelayMs: Long = 400L,
    val refHeight: Int = 800,
    val builtin: Boolean = false,
    val configDir: File? = null,
    val images: List<SmartImage> = emptyList(),
    val refWidth: Int = 1340
) {
    val allImages: List<SmartImage> = when {
        images.isNotEmpty() -> images
        imageFile != null || imageSource != null -> listOf(
            SmartImage(
                file = imageFile,
                source = imageSource,
                box = imageBox
            )
        )
        else -> emptyList()
    }

    /**
     * Возвращает эффективную область поиска:
     * - Если region задан: region;
     * - Если region не задан и есть source + box: box, расширенный на 25% в каждую сторону;
     * - Если region не задан и указан file: весь экран [0, 0, 1, 1].
     */
    fun getEffectiveRegion(): FloatArray {
        if (region != null && region.size == 4) {
            return region
        }
        val firstBox = imageBox ?: allImages.firstOrNull { it.box != null }?.box
        if (firstBox != null && firstBox.size == 4) {
            val bL = firstBox[0]
            val bT = firstBox[1]
            val bR = firstBox[2]
            val bB = firstBox[3]
            val w = bR - bL
            val h = bB - bT
            val padX = w * 0.25f
            val padY = h * 0.25f
            return floatArrayOf(
                (bL - padX).coerceIn(0f, 1f),
                (bT - padY).coerceIn(0f, 1f),
                (bR + padX).coerceIn(0f, 1f),
                (bB + padY).coerceIn(0f, 1f)
            )
        }
        return floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SmartRule
        if (id != other.id) return false
        if (priority != other.priority) return false
        if (imageFile != other.imageFile) return false
        if (imageSource != other.imageSource) return false
        if (imageBox != null) {
            if (other.imageBox == null || !imageBox.contentEquals(other.imageBox)) return false
        } else if (other.imageBox != null) return false
        if (region != null) {
            if (other.region == null || !region.contentEquals(other.region)) return false
        } else if (other.region != null) return false
        if (threshold != other.threshold) return false
        if (tapTarget != other.tapTarget) return false
        if (tapX != other.tapX) return false
        if (tapY != other.tapY) return false
        if (afterDelayMs != other.afterDelayMs) return false
        if (refHeight != other.refHeight) return false
        if (builtin != other.builtin) return false
        if (refWidth != other.refWidth) return false
        if (images != other.images) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + priority
        result = 31 * result + (imageFile?.hashCode() ?: 0)
        result = 31 * result + (imageSource?.hashCode() ?: 0)
        result = 31 * result + (imageBox?.contentHashCode() ?: 0)
        result = 31 * result + (region?.contentHashCode() ?: 0)
        result = 31 * result + threshold.hashCode()
        result = 31 * result + tapTarget.hashCode()
        result = 31 * result + (tapX?.hashCode() ?: 0)
        result = 31 * result + (tapY?.hashCode() ?: 0)
        result = 31 * result + afterDelayMs.hashCode()
        result = 31 * result + refHeight
        result = 31 * result + builtin.hashCode()
        result = 31 * result + refWidth
        result = 31 * result + images.hashCode()
        return result
    }
}

/**
 * Конфиг правил умного режима.
 */
data class SmartConfig(
    val name: String,
    val version: Int,
    val refWidth: Int,
    val refHeight: Int,
    val scanIntervalMs: Long = 300L,
    val idleTimeoutMin: Int = 5,
    val rules: List<SmartRule>
) {
    companion object {
        /**
         * Встроенный конфиг "default" (PUBG). Нельзя удалить или изменить.
         */
        fun createDefaultConfig(): SmartConfig {
            val rules = listOf(
                SmartRule(
                    id = "continue_mvp",
                    priority = 1,
                    imageFile = "templates/continue_mvp.png",
                    region = floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f),
                    threshold = 0.75f,
                    tapTarget = "found",
                    afterDelayMs = 400L,
                    refHeight = 800,
                    refWidth = 1340,
                    builtin = true
                ),
                SmartRule(
                    id = "continue_blue",
                    priority = 2,
                    imageFile = "templates/continue_blue.png",
                    region = floatArrayOf(0.50f, 0.65f, 1.0f, 1.0f),
                    threshold = 0.75f,
                    tapTarget = "found",
                    afterDelayMs = 400L,
                    refHeight = 800,
                    refWidth = 1340,
                    builtin = true
                ),
                SmartRule(
                    id = "start",
                    priority = 3,
                    imageFile = "templates/start.png",
                    region = floatArrayOf(0.0f, 0.42f, 0.65f, 1.0f),
                    threshold = 0.75f,
                    tapTarget = "found",
                    afterDelayMs = 400L,
                    refHeight = 800,
                    refWidth = 1340,
                    builtin = true
                )
            )

            return SmartConfig(
                name = "default",
                version = 1,
                refWidth = 1340,
                refHeight = 800,
                scanIntervalMs = 300L,
                idleTimeoutMin = 5,
                rules = rules
            )
        }

        /**
         * Обрезает посторонний текст после закрывающей фигурной скобки основного объекта JSON.
         */
        fun extractRootJsonObject(raw: String): Pair<String, Boolean> {
            var inString = false
            var escape = false
            var depth = 0
            var startIndex = -1
            var endIndex = -1

            for (i in raw.indices) {
                val c = raw[i]
                if (startIndex == -1) {
                    if (c == '{') {
                        startIndex = i
                        depth = 1
                    }
                    continue
                }

                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\' && inString) {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') {
                        depth++
                    } else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            endIndex = i
                            break
                        }
                    }
                }
            }

            if (startIndex != -1 && endIndex != -1) {
                val json = raw.substring(startIndex, endIndex + 1)
                val hasTrailingText = raw.substring(endIndex + 1).trim().isNotEmpty()
                return Pair(json, hasTrailingText)
            }
            return Pair(raw, false)
        }

        /**
         * Парсинг и строгая валидация JSON конфига.
         */
        fun fromJson(jsonStr: String, configDir: File? = null): SmartConfig {
            val (cleanJson, _) = extractRootJsonObject(jsonStr)

            val json = JSONObject(cleanJson)
            val name = json.optString("name", "").trim()
            if (name.isEmpty()) {
                throw IllegalArgumentException("Поле 'name' не может быть пустым")
            }
            val version = json.optInt("version", 1)
            val refWidth = json.optInt("refWidth", 1340).coerceAtLeast(100)
            val refHeight = json.optInt("refHeight", 800).coerceAtLeast(100)
            val scanIntervalMs = json.optLong("scanIntervalMs", 300L).coerceIn(200L, 10000L)
            val idleTimeoutMin = json.optInt("idleTimeoutMin", 5).coerceIn(1, 60)

            val rulesArr = json.optJSONArray("rules")
                ?: throw IllegalArgumentException("Отсутствует массив 'rules'")
            if (rulesArr.length() == 0) {
                throw IllegalArgumentException("Массив 'rules' не должен быть пустым")
            }

            val rulesList = mutableListOf<SmartRule>()
            for (i in 0 until rulesArr.length()) {
                val rObj = rulesArr.getJSONObject(i)
                val id = rObj.optString("id", "").trim()
                if (id.isEmpty()) {
                    throw IllegalArgumentException("Правило #${i + 1}: 'id' не может быть пустым")
                }
                val priority = rObj.optInt("priority", i + 1)

                fun parseImageObj(imgObj: JSONObject): SmartImage {
                    val imgFile = if (imgObj.has("file")) imgObj.getString("file").trim() else null
                    val imgSrc = if (imgObj.has("source")) imgObj.getString("source").trim() else null

                    var boxArray: FloatArray? = null
                    if (imgObj.has("box")) {
                        val bArr = imgObj.getJSONArray("box")
                        if (bArr.length() != 4) {
                            throw IllegalArgumentException("Правило '$id': box должен содержать 4 координаты")
                        }
                        val l = bArr.getDouble(0).toFloat()
                        val t = bArr.getDouble(1).toFloat()
                        val r = bArr.getDouble(2).toFloat()
                        val b = bArr.getDouble(3).toFloat()
                        validateFractions("Правило '$id': box", l, t, r, b)
                        if (l >= r || t >= b) {
                            throw IllegalArgumentException("Правило '$id': box некорректен (левый < правый, верхний < нижний)")
                        }
                        boxArray = floatArrayOf(l, t, r, b)
                    }

                    if (imgFile.isNullOrEmpty() && (imgSrc.isNullOrEmpty() || boxArray == null)) {
                        throw IllegalArgumentException("Правило '$id': в image должно быть указано либо 'file', либо 'source' с 'box'")
                    }

                    var scaleRange = floatArrayOf(0.6f, 1.6f)
                    if (imgObj.has("scaleRange")) {
                        val sr = imgObj.getJSONArray("scaleRange")
                        if (sr.length() != 2) {
                            throw IllegalArgumentException("Правило '$id': scaleRange должен содержать 2 значения [min, max]")
                        }
                        val sMin = sr.getDouble(0).toFloat()
                        val sMax = sr.getDouble(1).toFloat()
                        if (sMin < 0.2f || sMin >= sMax || sMax > 3.0f) {
                            throw IllegalArgumentException("Правило '$id': scaleRange должен удовлетворять 0.2 <= min < max <= 3.0 (получено [$sMin, $sMax])")
                        }
                        scaleRange = floatArrayOf(sMin, sMax)
                    }

                    return SmartImage(
                        file = imgFile,
                        source = imgSrc,
                        box = boxArray,
                        scaleRange = scaleRange
                    )
                }

                val parsedImages = mutableListOf<SmartImage>()
                if (rObj.has("images")) {
                    val arr = rObj.getJSONArray("images")
                    if (arr.length() == 0) {
                        throw IllegalArgumentException("Правило '$id': массив 'images' не должен быть пустым")
                    }
                    for (j in 0 until arr.length()) {
                        parsedImages.add(parseImageObj(arr.getJSONObject(j)))
                    }
                } else if (rObj.has("image")) {
                    parsedImages.add(parseImageObj(rObj.getJSONObject("image")))
                } else {
                    throw IllegalArgumentException("Правило '$id': отсутствует блок 'image' или 'images'")
                }

                // Проверка region (если задан)
                var regionArray: FloatArray? = null
                if (rObj.has("region") && !rObj.isNull("region")) {
                    val regArr = rObj.getJSONArray("region")
                    if (regArr.length() != 4) {
                        throw IllegalArgumentException("Правило '$id': region должен содержать 4 координаты")
                    }
                    val l = regArr.getDouble(0).toFloat()
                    val t = regArr.getDouble(1).toFloat()
                    val r = regArr.getDouble(2).toFloat()
                    val b = regArr.getDouble(3).toFloat()
                    validateFractions("Правило '$id': region", l, t, r, b)
                    if (l >= r || t >= b) {
                        throw IllegalArgumentException("Правило '$id': region некорректен (левый < правый, верхний < нижний)")
                    }
                    regionArray = floatArrayOf(l, t, r, b)
                }

                val threshold = rObj.optDouble("threshold", 0.80).toFloat().coerceIn(0.50f, 0.99f)

                // Проверка tap
                var target = "found"
                var tapX: Float? = null
                var tapY: Float? = null
                if (rObj.has("tap")) {
                    val tapObj = rObj.getJSONObject("tap")
                    target = tapObj.optString("target", "found").trim().lowercase()
                    if (target != "found" && target != "fixed") {
                        throw IllegalArgumentException("Правило '$id': tap.target должен быть 'found' или 'fixed'")
                    }
                    if (target == "fixed") {
                        if (!tapObj.has("x") || !tapObj.has("y")) {
                            throw IllegalArgumentException("Правило '$id': для tap.target='fixed' обязательны 'x' и 'y'")
                        }
                        val tx = tapObj.getDouble("x").toFloat()
                        val ty = tapObj.getDouble("y").toFloat()
                        validateFractions("Правило '$id': tap", tx, ty)
                        tapX = tx
                        tapY = ty
                    }
                } else {
                    throw IllegalArgumentException("Правило '$id': отсутствует блок 'tap'")
                }

                val afterDelayMs = rObj.optLong("afterDelayMs", 500L).coerceAtLeast(50L)
                val primaryImg = parsedImages.firstOrNull()

                rulesList.add(
                    SmartRule(
                        id = id,
                        priority = priority,
                        imageFile = primaryImg?.file,
                        imageSource = primaryImg?.source,
                        imageBox = primaryImg?.box,
                        region = regionArray,
                        threshold = threshold,
                        tapTarget = target,
                        tapX = tapX,
                        tapY = tapY,
                        afterDelayMs = afterDelayMs,
                        refHeight = refHeight,
                        builtin = false,
                        configDir = configDir,
                        images = parsedImages,
                        refWidth = refWidth
                    )
                )
            }

            return SmartConfig(
                name = name,
                version = version,
                refWidth = refWidth,
                refHeight = refHeight,
                scanIntervalMs = scanIntervalMs,
                idleTimeoutMin = idleTimeoutMin,
                rules = rulesList
            )
        }

        private fun validateFractions(label: String, vararg values: Float) {
            for (v in values) {
                if (v < 0f || v > 1f) {
                    throw IllegalArgumentException("$label: все координаты должны быть в долях 0..1 (получено $v)")
                }
            }
        }
    }
}
