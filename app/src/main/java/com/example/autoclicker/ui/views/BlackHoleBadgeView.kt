package com.example.autoclicker.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Плавающая кнопка «чёрная дыра», лёгкая нативная версия (без WebView и без JavaScript).
 * Рисуется один раз и перерисовывается только при смене состояния, поэтому почти не расходует
 * память и процессор.
 *
 * isRunning = true: зелёный контур. ringAlways = true: контур горит всегда.
 * Публичный интерфейс тот же, что и у прежней версии, FloatingOverlayService менять не нужно.
 */
class BlackHoleBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var clickListener: (() -> Unit)? = null
    private var longClickListener: (() -> Unit)? = null

    var isRunning: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var ringAlways: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val base = minOf(w, h) / 2f
        if (base > 0f) {
            glowPaint.shader = RadialGradient(
                w / 2f, h / 2f, base,
                intArrayOf(Color.argb(150, 255, 122, 24), Color.argb(60, 255, 122, 24), Color.TRANSPARENT),
                floatArrayOf(0.45f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f
        if (base <= 0f) return

        val coreR = base * 0.38f
        val ringR = base * 0.52f

        // Мягкое свечение вокруг
        c.drawCircle(cx, cy, base, glowPaint)

        // Контур: зелёный, когда работает (или всегда, если включено), иначе оранжевый
        ringPaint.strokeWidth = base * 0.10f
        ringPaint.color = if (isRunning || ringAlways) Color.parseColor("#2EE66B") else Color.parseColor("#FF7A18")
        c.drawCircle(cx, cy, ringR, ringPaint)

        // Тёмное ядро
        c.drawCircle(cx, cy, coreR, corePaint)
    }

    /** Оставлено для совместимости: анимации больше нет. */
    fun pauseAnimation() {}

    /** Оставлено для совместимости: анимации больше нет. */
    fun resumeAnimation() {}

    fun setOnBadgeClick(listener: () -> Unit) {
        clickListener = listener
    }

    fun setOnBadgeLongClick(listener: () -> Unit) {
        longClickListener = listener
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Перехватываем касания для перетаскивания в FloatingOverlayService
        return true
    }
}
