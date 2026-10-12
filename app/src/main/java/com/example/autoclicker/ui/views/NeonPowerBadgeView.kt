package com.example.autoclicker.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Плавающая кнопка питания по дизайну neon-power-button.html.
 * Полностью нативная отрисовка на Canvas без WebView и фоновых циклов анимации.
 *
 * Базовый размер 64x64 dp, умножается на BUTTON_SCALE = 1.3.
 * Красный цвет (#ff2a3d) при выключении, салатовый неон (#39ff14) при включении.
 */
class NeonPowerBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        const val BUTTON_SCALE = 1.3f
        const val BASE_SIZE_DP = 64f
    }

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

    var pressedLook: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val colorRed = Color.parseColor("#FF2A3D")
    private val colorGreen = Color.parseColor("#39FF14")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val powerPath = Path()
    private val linePath = Path()
    private val arcBounds = RectF()

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val s = BUTTON_SCALE * density

        val cx = w / 2f
        val cy = h / 2f
        val r = (BASE_SIZE_DP / 2f) * s // 32 * 1.3 * density = 41.6 * density

        val isOn = isRunning || ringAlways
        val neonColor = if (isOn) colorGreen else colorRed

        // 1. Halo (мягкое неоновое свечение вокруг кнопки)
        // box-shadow: 0 0 14px 1px (off, opacity .35) / 0 0 20px 3px (on, opacity .6)
        val haloSpread = if (isOn) 22f * s else 15f * s
        val haloAlpha = if (isOn) 0.60f else 0.35f
        val haloColorStart = Color.argb(
            (haloAlpha * 255).toInt(),
            Color.red(neonColor),
            Color.green(neonColor),
            Color.blue(neonColor)
        )
        val haloShader = RadialGradient(
            cx, cy, r + haloSpread,
            intArrayOf(haloColorStart, haloColorStart.copyAlpha(haloAlpha * 0.5f), Color.TRANSPARENT),
            floatArrayOf(r / (r + haloSpread), (r + haloSpread * 0.6f) / (r + haloSpread), 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = haloShader
        canvas.drawCircle(cx, cy, r + haloSpread, fillPaint)
        fillPaint.shader = null

        // 2. Внешняя тень outer: box-shadow 0 6px 12px rgba(0,0,0,.6)
        val shadowDy = 6f * s
        val shadowRadius = 12f * s
        shadowPaint.color = Color.argb(150, 0, 0, 0)
        shadowPaint.setShadowLayer(shadowRadius, 0f, shadowDy, Color.argb(150, 0, 0, 0))
        canvas.drawCircle(cx, cy, r, shadowPaint)
        shadowPaint.clearShadowLayer()

        // 3. Outer (внешнее кольцо)
        // background: linear-gradient(180deg, #343436 0%, #131315 60%, #0b0b0d 100%)
        fillPaint.shader = LinearGradient(
            cx, cy - r, cx, cy + r,
            intArrayOf(Color.parseColor("#343436"), Color.parseColor("#131315"), Color.parseColor("#0B0B0D")),
            floatArrayOf(0f, 0.60f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, fillPaint)
        fillPaint.shader = null

        // Outer: inset 0 1px 1px rgba(255,255,255,.2) (верхний блик) и inset 0 -2px 1px rgba(255,255,255,.22)
        strokePaint.strokeWidth = 1f * s
        strokePaint.color = Color.argb(50, 255, 255, 255)
        canvas.drawCircle(cx, cy, r - 0.5f * s, strokePaint)

        // 4. Gap (чёрный зазор с тонким неоновым кольцом)
        // inset: 5px
        val gapR = r - 5f * s
        fillPaint.color = Color.parseColor("#070708")
        canvas.drawCircle(cx, cy, gapR, fillPaint)

        // gap shadow: inset 0 2px 4px rgba(0,0,0,.9)
        strokePaint.strokeWidth = 2f * s
        strokePaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawCircle(cx, cy - 1f * s, gapR - 1f * s, strokePaint)

        // gap::after (тонкое неоновое кольцо): inset 1px, border 1px solid var(--c), opacity .3
        val ringR = gapR - 1.5f * s
        strokePaint.strokeWidth = 1f * s
        strokePaint.color = Color.argb(76, Color.red(neonColor), Color.green(neonColor), Color.blue(neonColor))
        canvas.drawCircle(cx, cy, ringR, strokePaint)

        // 5. Face (верхняя площадка кнопки)
        // inset: 9px. Если down: scale(.95)
        val faceBaseR = r - 9f * s
        val faceR = if (pressedLook) faceBaseR * 0.95f else faceBaseR

        // 1px чёрная окантовка вокруг face
        strokePaint.strokeWidth = 1f * s
        strokePaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, faceR + 0.5f * s, strokePaint)

        // face background: linear-gradient(180deg, #5d5d5f 0%, #2b2b2d 48%, #151517 100%)
        fillPaint.shader = LinearGradient(
            cx, cy - faceR, cx, cy + faceR,
            intArrayOf(Color.parseColor("#5D5D5F"), Color.parseColor("#2B2B2D"), Color.parseColor("#151517")),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, faceR, fillPaint)
        fillPaint.shader = null

        // Внутренние тени / блики face
        if (pressedLook) {
            // box-shadow: inset 0 3px 5px rgba(0,0,0,.8)
            strokePaint.strokeWidth = 3f * s
            strokePaint.color = Color.argb(200, 0, 0, 0)
            canvas.drawCircle(cx, cy - 1f * s, faceR - 1.5f * s, strokePaint)
        } else {
            // inset 0 1px 1px rgba(255,255,255,.35), inset 0 -2px 1px rgba(255,255,255,.3)
            strokePaint.strokeWidth = 1f * s
            strokePaint.color = Color.argb(89, 255, 255, 255)
            canvas.drawCircle(cx, cy - 0.5f * s, faceR - 0.5f * s, strokePaint)
        }

        // 6. Вдавленный диск под значком (face::after)
        // inset: 4px внутри face
        val diskR = faceR - 4f * s
        // radial-gradient(circle at 50% 35%, #2a2a2c 0%, #121214 75%)
        fillPaint.shader = RadialGradient(
            cx, cy - diskR * 0.30f, diskR,
            intArrayOf(Color.parseColor("#2A2A2C"), Color.parseColor("#121214")),
            floatArrayOf(0f, 0.75f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, diskR, fillPaint)
        fillPaint.shader = null

        // disk inner shadow inset 0 2px 4px rgba(0,0,0,.8)
        strokePaint.strokeWidth = 2f * s
        strokePaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawCircle(cx, cy - 1f * s, diskR - 1f * s, strokePaint)

        // 7. Значок питания (icon)
        // SVG viewBox 24x24: M12 3v8, M6.3 6.6a8 8 0 1 0 11.4 0
        // stroke-width: 2.3, stroke-linecap: round
        // filter: drop-shadow(0 0 2px var(--c)) drop-shadow(0 0 6px var(--c)) (+ 14px on)
        val iconScale = (24f * s) / 24f // масштаб относительно 24x24
        val iconStroke = 2.3f * iconScale

        powerPath.reset()
        linePath.reset()

        // Вертикальная линия: от (12, 3) до (12, 11)
        val lineX = cx
        val lineY1 = cy + (3f - 12f) * iconScale
        val lineY2 = cy + (11f - 12f) * iconScale
        linePath.moveTo(lineX, lineY1)
        linePath.lineTo(lineX, lineY2)

        // Дуга: радиус 8 от (6.3, 6.6) до (17.7, 6.6)
        val arcR = 8f * iconScale
        arcBounds.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        // Углы: дуга с разрывом вверху (от -137 градусов по часовой стрелке 286 градусов)
        powerPath.arcTo(arcBounds, -137.5f, 275f, false)

        // Свечение значка
        iconGlowPaint.color = neonColor
        iconGlowPaint.strokeWidth = iconStroke

        if (isOn) {
            // drop-shadow 0 0 14px
            iconGlowPaint.setShadowLayer(14f * density, 0f, 0f, neonColor)
            canvas.drawPath(linePath, iconGlowPaint)
            canvas.drawPath(powerPath, iconGlowPaint)
        }

        // drop-shadow 0 0 6px
        iconGlowPaint.setShadowLayer(6f * density, 0f, 0f, neonColor)
        canvas.drawPath(linePath, iconGlowPaint)
        canvas.drawPath(powerPath, iconGlowPaint)

        // drop-shadow 0 0 2px
        iconGlowPaint.setShadowLayer(2f * density, 0f, 0f, neonColor)
        canvas.drawPath(linePath, iconGlowPaint)
        canvas.drawPath(powerPath, iconGlowPaint)
        iconGlowPaint.clearShadowLayer()

        // Основные чёткие линии значка
        iconPaint.color = neonColor
        iconPaint.strokeWidth = iconStroke
        canvas.drawPath(linePath, iconPaint)
        canvas.drawPath(powerPath, iconPaint)
    }

    private fun Int.copyAlpha(alpha: Float): Int {
        val a = (Color.alpha(this) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))
    }

    fun pauseAnimation() {}
    fun resumeAnimation() {}

    fun setOnBadgeClick(listener: () -> Unit) {
        clickListener = listener
    }

    fun setOnBadgeLongClick(listener: () -> Unit) {
        longClickListener = listener
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = true
}
