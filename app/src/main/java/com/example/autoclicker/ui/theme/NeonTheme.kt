package com.example.autoclicker.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Глобальный синглтон параметров неона. Им пользуются и Compose-экран, и нативные View плавающего окна.
 *
 * Как в HTML-макете: базовый оттенок 110° (зелёный), полный круг из 36 цветов за 108 секунд
 * (36 шагов × 3 с), яркость неона = прозрачность слоёв свечения.
 * Цвет можно пустить по кругу автоматически или зафиксировать вручную (setHue).
 */
object NeonTheme {
    const val BASE_HUE = 110f
    const val CYCLE_MS = 108_000L
    const val SWATCH_COUNT = 36

    /** Яркость неона 0.0 .. 1.0 (ставится из SettingsRepository). */
    @Volatile
    var brightness: Float = 0.85f

    /** Последний вычисленный оттенок (оставлено для совместимости со старым кодом). */
    @Volatile
    var currentHue: Float = BASE_HUE

    /** true — цвет плавно меняется сам, false — зафиксирован вручную. */
    @Volatile
    var isAuto: Boolean = true
        private set

    @Volatile
    private var manualHue: Float = BASE_HUE

    @Volatile
    private var epoch: Long = SystemClock.elapsedRealtime()

    private var prefs: SharedPreferences? = null

    /** Вызвать один раз (MainActivity / FloatingOverlayService) — подгружает сохранённый выбор цвета. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("neon_theme", Context.MODE_PRIVATE)
        prefs = p
        manualHue = p.getFloat("manual_hue", BASE_HUE)
        isAuto = true
        epoch = SystemClock.elapsedRealtime() - hueToOffsetMs(p.getFloat("last_hue", BASE_HUE))
    }

    private fun hueToOffsetMs(h: Float): Long {
        val d = (((h - BASE_HUE) % 360f) + 360f) % 360f
        return (d / 360f * CYCLE_MS).toLong()
    }

    /** Текущий оттенок 0..360 (для автосмены считается по часам, поэтому не зависит от того, кто рисует). */
    fun getHue(): Float {
        val h = if (isAuto) {
            val t = (SystemClock.elapsedRealtime() - epoch).toFloat()
            (BASE_HUE + t / CYCLE_MS * 360f) % 360f
        } else manualHue
        currentHue = h
        return h
    }

    /** Включить/выключить автосмену. При включении цвет продолжает с текущего оттенка без скачка. */
    fun setAuto(on: Boolean) {
        if (on == isAuto) return
        val h = getHue()
        isAuto = on
        if (on) epoch = SystemClock.elapsedRealtime() - hueToOffsetMs(h) else manualHue = h
        save()
        notifyChanged()
    }

    /** Выбрать конкретный цвет (выключает автосмену). */
    fun setHue(h: Float) {
        manualHue = ((h % 360f) + 360f) % 360f
        isAuto = false
        save()
        notifyChanged()
    }

    private fun save() {
        prefs?.edit()
            ?.putBoolean("auto", isAuto)
            ?.putFloat("manual_hue", manualHue)
            ?.putFloat("last_hue", getHue())
            ?.apply()
    }

    // ---------- Подписчики (нативные View плавающего окна) ----------
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val handler = Handler(Looper.getMainLooper())
    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (listeners.isEmpty()) {
                ticking = false
                return
            }
            notifyChanged()
            handler.postDelayed(this, 120L)
        }
    }

    fun addListener(r: Runnable) {
        listeners.add(r)
        if (!ticking) {
            ticking = true
            handler.postDelayed(ticker, 120L)
        }
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    fun notifyChanged() {
        for (l in listeners) l.run()
    }

    // ---------- Цвета ----------
    /** HSL (h: 0..360, s,l: 0..1) -> ARGB Int. Как hsl() в CSS. */
    fun hsl(h: Float, s: Float, l: Float, a: Float = 1f): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = l - c / 2f
        val r: Float
        val g: Float
        val b: Float
        when {
            hh < 60f -> { r = c; g = x; b = 0f }
            hh < 120f -> { r = x; g = c; b = 0f }
            hh < 180f -> { r = 0f; g = c; b = x }
            hh < 240f -> { r = 0f; g = x; b = c }
            hh < 300f -> { r = x; g = 0f; b = c }
            else -> { r = c; g = 0f; b = x }
        }
        return AndroidColor.argb(
            (a.coerceIn(0f, 1f) * 255f).roundToInt(),
            ((r + m) * 255f).roundToInt().coerceIn(0, 255),
            ((g + m) * 255f).roundToInt().coerceIn(0, 255),
            ((b + m) * 255f).roundToInt().coerceIn(0, 255)
        )
    }

    /** Яркость свечения как --gl в HTML: 0.2 .. 1.0 */
    fun glowLevel(): Float = 0.2f + 0.8f * brightness.coerceIn(0f, 1f)

    fun getCurrentColorInt(): Int = hsl(getHue(), 1f, 0.55f)

    fun getGlowColorInt(alpha: Float = 1.0f): Int = hsl(getHue(), 1f, 0.58f, (alpha * glowLevel()).coerceIn(0f, 1f))

    /** Цвет неоновой рамки (светлее основного, как border в HTML: hsl(h 100% 62%)). */
    fun getBorderColorInt(): Int = hsl(getHue(), 1f, 0.62f, (0.35f + 0.65f * brightness.coerceIn(0f, 1f)).coerceIn(0f, 1f))
}
