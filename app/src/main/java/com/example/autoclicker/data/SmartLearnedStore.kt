package com.example.autoclicker.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Единое хранилище выученных позиций кнопок умного режима (встроенных и пользовательских).
 * Живёт в SharedPreferences — переживает перезапуск процесса.
 *
 * Ключ: размер экрана (ширина x высота) + имя конфига ("builtin" по умолчанию) + id правила.
 * Значение: центр кнопки (xFrac, yFrac в долях экрана), масштаб (multiplier),
 * последний score, число успешных нажатий (hitCount), время записи (recordedAtMs).
 */
object SmartLearnedStore {

    data class Learned(
        val multiplier: Float,
        val xFrac: Float,
        val yFrac: Float,
        val score: Float,
        val hitCount: Int = 1,
        val recordedAtMs: Long = System.currentTimeMillis()
    )

    private const val PREFS_NAME = "smart_learned"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _countFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    val countFlow: kotlinx.coroutines.flow.StateFlow<Int> = _countFlow

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    _countFlow.value = count()
                }
            }
        }
    }

    private fun ensurePrefs(context: Context? = null): SharedPreferences? {
        if (prefs == null && context != null) {
            init(context)
        }
        return prefs
    }

    /**
     * Построение ключа с учётом размера экрана, конфига и id правила.
     */
    fun buildKey(screenW: Int, screenH: Int, ruleId: String, configName: String = "builtin"): String {
        val cfg = if (configName.isBlank()) "builtin" else configName
        return "${screenW}x${screenH}:$cfg:$ruleId"
    }

    /**
     * Старый ключ для обратной совместимости.
     */
    private fun legacyKey(screenW: Int, screenH: Int, ruleId: String): String =
        "${screenW}x${screenH}:$ruleId"

    /**
     * Получить запомненные координаты правила для конкретного разрешения и конфига.
     */
    fun get(screenW: Int, screenH: Int, ruleId: String, configName: String = "builtin"): Learned? {
        val p = prefs ?: return null
        val fullKey = buildKey(screenW, screenH, ruleId, configName)
        val raw = p.getString(fullKey, null) ?: p.getString(legacyKey(screenW, screenH, ruleId), null) ?: return null
        val parts = raw.split(';')
        if (parts.size < 4) return null
        return try {
            val mult = parts[0].toFloat()
            val xf = parts[1].toFloat()
            val yf = parts[2].toFloat()
            val sc = parts[3].toFloat()
            val hits = if (parts.size >= 5) parts[4].toIntOrNull() ?: 1 else 1
            val ts = if (parts.size >= 6) parts[5].toLongOrNull() ?: System.currentTimeMillis() else System.currentTimeMillis()
            Learned(
                multiplier = mult,
                xFrac = xf,
                yFrac = yf,
                score = sc,
                hitCount = hits,
                recordedAtMs = ts
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Сохранить/обновить выученную позицию правила.
     */
    fun put(
        screenW: Int,
        screenH: Int,
        ruleId: String,
        multiplier: Float,
        xFrac: Float,
        yFrac: Float,
        score: Float,
        hitCount: Int? = null,
        configName: String = "builtin"
    ) {
        val p = prefs ?: return
        val current = get(screenW, screenH, ruleId, configName)
        val finalHits = hitCount ?: ((current?.hitCount ?: 0) + 1)
        val now = System.currentTimeMillis()

        val fullKey = buildKey(screenW, screenH, ruleId, configName)
        val value = "$multiplier;$xFrac;$yFrac;$score;$finalHits;$now"

        p.edit()
            .putString(fullKey, value)
            // Также сохраняем по legacy-ключу для обратной совместимости
            .putString(legacyKey(screenW, screenH, ruleId), "$multiplier;$xFrac;$yFrac;$score")
            .apply()
        _countFlow.value = count()
    }

    /**
     * Количество всех запомненных правил в хранилище.
     */
    fun count(): Int {
        val p = prefs ?: return 0
        // Считаем уникальные комбинации ключей, исключая legacy дубли
        return p.all.keys.filter { key ->
            // Считаем только ключи с тройным разделителем (screen:config:rule)
            // либо если только legacy — считаем их
            key.contains(':')
        }.map { key ->
            // нормализуем по ruleId и размеру
            val parts = key.split(':')
            if (parts.size >= 3) "${parts[0]}:${parts[1]}:${parts[2]}" else key
        }.distinct().size
    }

    /**
     * Очистить всё хранилище выученных позиций.
     */
    fun clear() {
        prefs?.edit()?.clear()?.apply()
        _countFlow.value = 0
    }
}
