package com.example.autoclicker.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator
import kotlin.math.abs

/**
 * Неоновый ползунок-переключатель по дизайну neon-glass-slider.html.
 * Полностью нативная отрисовка на Canvas без WebView.
 *
 * Базовый размер 104x38 dp, ход ручки 66 dp, масштаб SLIDER_SCALE = 1.5 (156x57 dp).
 * Красный цвет (#ff2a3d) при выключении, салатовый неон (#39ff14) при включении.
 */
class NeonSwitchSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val SLIDER_SCALE = 1.5f
        const val BASE_W_DP = 104f
        const val BASE_H_DP = 38f
        const val BASE_TRAVEL_DP = 66f
        const val BASE_KNOB_SIZE_DP = 28f
    }

    private var _isOn: Boolean = false
    val isOn: Boolean
        get() = _isOn

    var onToggle: ((Boolean) -> Unit)? = null

    private val colorRed = Color.parseColor("#FF2A3D")
    private val colorGreen = Color.parseColor("#39FF14")

    private var currentKnobOffset: Float = 0f
    private var isDragging: Boolean = false
    private var touchStartX: Float = 0f
    private var dragStartOffset: Float = 0f
    private var hasMoved: Boolean = false
    private var moveAnimator: ValueAnimator? = null

    private val cubicInterpolator = PathInterpolator(0.3f, 0.9f, 0.3f, 1.0f)

    // Графика
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val bezelRect = RectF()
    private val glowRect = RectF()
    private val rimRect = RectF()
    private val panelRect = RectF()
    private val clipPath = Path()

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        updateAccessibility()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desiredW = (BASE_W_DP * SLIDER_SCALE * density).toInt()
        val desiredH = (BASE_H_DP * SLIDER_SCALE * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(desiredH, heightMeasureSpec)
        )
    }

    private fun updateAccessibility() {
        contentDescription = "Автокликер: ${if (_isOn) "включено" else "выключено"}"
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = _isOn
        info.contentDescription = "Автокликер: ${if (_isOn) "включено" else "выключено"}"
    }

    fun setOn(on: Boolean, notify: Boolean = false, animate: Boolean = true) {
        if (isDragging) {
            // Если сейчас пользователь тянет ручку пальцем — не прерывать движение
            return
        }
        if (_isOn == on && moveAnimator?.isRunning != true) return

        _isOn = on
        updateAccessibility()

        val density = resources.displayMetrics.density
        val s = SLIDER_SCALE * density
        val maxTravel = BASE_TRAVEL_DP * s
        val targetOffset = if (on) maxTravel else 0f

        moveAnimator?.cancel()
        if (animate) {
            moveAnimator = ValueAnimator.ofFloat(currentKnobOffset, targetOffset).apply {
                duration = 280L
                interpolator = cubicInterpolator
                addUpdateListener { anim ->
                    currentKnobOffset = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            currentKnobOffset = targetOffset
            invalidate()
        }

        if (notify) {
            onToggle?.invoke(on)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val s = SLIDER_SCALE * density
        val maxTravel = BASE_TRAVEL_DP * s

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                moveAnimator?.cancel()
                isDragging = true
                hasMoved = false
                touchStartX = event.x
                dragStartOffset = currentKnobOffset
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - touchStartX
                    if (abs(dx) > 3f * density) {
                        hasMoved = true
                    }
                    currentKnobOffset = (dragStartOffset + dx).coerceIn(0f, maxTravel)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                val newState = if (hasMoved) {
                    currentKnobOffset > maxTravel / 2f
                } else {
                    !_isOn
                }
                setOn(newState, notify = true, animate = true)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                setOn(_isOn, notify = false, animate = true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val s = SLIDER_SCALE * density
        val totalW = BASE_W_DP * s
        val totalH = BASE_H_DP * s
        val maxTravel = BASE_TRAVEL_DP * s

        // Центрируем ползунок внутри view
        val ox = (w - totalW) / 2f
        val oy = (h - totalH) / 2f

        canvas.save()
        canvas.translate(ox, oy)

        val visualOn = currentKnobOffset > maxTravel / 2f

        // 1. Корпус (bezel)
        bezelRect.set(0f, 0f, totalW, totalH)
        val bezelR = totalH / 2f

        // Bezel shadow: 0 4px 9px rgba(0,0,0,.6)
        glowPaint.color = Color.argb(150, 0, 0, 0)
        glowPaint.setShadowLayer(9f * s, 0f, 4f * s, Color.argb(150, 0, 0, 0))
        canvas.drawRoundRect(bezelRect, bezelR, bezelR, glowPaint)
        glowPaint.clearShadowLayer()

        // Bezel background: linear-gradient(180deg, #43444d 0%, #1a1a20 35%, #0b0b10 100%)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, totalH,
            intArrayOf(Color.parseColor("#43444D"), Color.parseColor("#1A1A20"), Color.parseColor("#0B0B10")),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(bezelRect, bezelR, bezelR, fillPaint)
        fillPaint.shader = null

        // 1px border rgba(255,255,255,.06) + inset 0 1px 1px rgba(255,255,255,.22)
        strokePaint.strokeWidth = 1f * s
        strokePaint.color = Color.argb(45, 255, 255, 255)
        canvas.drawRoundRect(
            0.5f * s, 0.5f * s, totalW - 0.5f * s, totalH - 0.5f * s,
            bezelR - 0.5f * s, bezelR - 0.5f * s, strokePaint
        )

        // 2. Нейтральная кайма корпуса (без цветного свечения)
        rimRect.set(2.5f * s, 2.5f * s, totalW - 2.5f * s, totalH - 2.5f * s)
        val rimR = rimRect.height() / 2f
        strokePaint.strokeWidth = 1f * s
        strokePaint.shader = null
        strokePaint.color = Color.argb(30, 255, 255, 255)
        canvas.drawRoundRect(rimRect, rimR, rimR, strokePaint)

        // 3. Тёмная панель внутри (panel): inset 3.5px
        panelRect.set(3.5f * s, 3.5f * s, totalW - 3.5f * s, totalH - 3.5f * s)
        val panelR = panelRect.height() / 2f
        fillPaint.shader = LinearGradient(
            0f, panelRect.top, 0f, panelRect.bottom,
            intArrayOf(Color.parseColor("#07070B"), Color.parseColor("#15161D")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(panelRect, panelR, panelR, fillPaint)
        fillPaint.shader = null

        // Inner shadow панели: inset 0 2px 5px rgba(0,0,0,.8)
        strokePaint.strokeWidth = 2f * s
        strokePaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRoundRect(
            panelRect.left + 1f * s, panelRect.top + 1f * s,
            panelRect.right - 1f * s, panelRect.bottom - 1f * s,
            panelR - 1f * s, panelR - 1f * s, strokePaint
        )

        // 4. Кружки-индикаторы (dot.l и dot.rt)
        // dot: top 15px, width 8px, height 8px (center at (15+4)*s, (15+4)*s = 19*s, 19*s)
        val dotR = 4f * s
        val dotLeftCx = (15f + 4f) * s
        val dotLeftCy = (15f + 4f) * s
        val dotRightCx = (81f + 4f) * s
        val dotRightCy = (15f + 4f) * s

        // Левая точка (красная)
        if (!visualOn) {
            // Активная: background #ff3a4b, box-shadow 0 0 4px 1px var(--r), 0 0 10px 2px rgba(255,42,61,.6), inset 0 0 2px #ffb3ba
            glowPaint.color = colorRed
            glowPaint.setShadowLayer(10f * s, 0f, 0f, Color.argb(160, 255, 42, 61))
            canvas.drawCircle(dotLeftCx, dotLeftCy, dotR, glowPaint)
            glowPaint.setShadowLayer(4f * s, 0f, 0f, colorRed)
            canvas.drawCircle(dotLeftCx, dotLeftCy, dotR, glowPaint)
            glowPaint.clearShadowLayer()

            fillPaint.color = Color.parseColor("#FF3A4B")
            canvas.drawCircle(dotLeftCx, dotLeftCy, dotR, fillPaint)

            fillPaint.color = Color.parseColor("#FFB3BA")
            canvas.drawCircle(dotLeftCx, dotLeftCy, dotR * 0.45f, fillPaint)
        } else {
            // Выключенная: background #2c1115
            fillPaint.color = Color.parseColor("#2C1115")
            canvas.drawCircle(dotLeftCx, dotLeftCy, dotR, fillPaint)
            strokePaint.strokeWidth = 1f * s
            strokePaint.color = Color.argb(180, 0, 0, 0)
            canvas.drawCircle(dotLeftCx, dotLeftCy, dotR - 0.5f * s, strokePaint)
        }

        // Правая точка (салатовая)
        if (visualOn) {
            // Активная: background #7dff5a, box-shadow 0 0 4px 1px var(--g), 0 0 10px 2px rgba(57,255,20,.6), inset 0 0 2px #e4ffd9
            glowPaint.color = colorGreen
            glowPaint.setShadowLayer(10f * s, 0f, 0f, Color.argb(160, 57, 255, 20))
            canvas.drawCircle(dotRightCx, dotRightCy, dotR, glowPaint)
            glowPaint.setShadowLayer(4f * s, 0f, 0f, colorGreen)
            canvas.drawCircle(dotRightCx, dotRightCy, dotR, glowPaint)
            glowPaint.clearShadowLayer()

            fillPaint.color = Color.parseColor("#7DFF5A")
            canvas.drawCircle(dotRightCx, dotRightCy, dotR, fillPaint)

            fillPaint.color = Color.parseColor("#E4FFD9")
            canvas.drawCircle(dotRightCx, dotRightCy, dotR * 0.45f, fillPaint)
        } else {
            // Выключенная: background #16231a
            fillPaint.color = Color.parseColor("#16231A")
            canvas.drawCircle(dotRightCx, dotRightCy, dotR, fillPaint)
            strokePaint.strokeWidth = 1f * s
            strokePaint.color = Color.argb(180, 0, 0, 0)
            canvas.drawCircle(dotRightCx, dotRightCy, dotR - 0.5f * s, strokePaint)
        }

        // 5. Ручка-бульбашка (#knob)
        // left: 5px + offset, top: 5px, width: 28px, height: 28px
        val knobLeft = 5f * s + currentKnobOffset
        val knobTop = 5f * s
        val knobSize = BASE_KNOB_SIZE_DP * s
        val knobR = knobSize / 2f
        val kcx = knobLeft + knobR
        val kcy = knobTop + knobR

        // Тень ручки: 0 3px 6px rgba(0,0,0,.5)
        shadowPaint.color = Color.argb(128, 0, 0, 0)
        shadowPaint.setShadowLayer(6f * s, 0f, 3f * s, Color.argb(128, 0, 0, 0))
        canvas.drawCircle(kcx, kcy, knobR, shadowPaint)
        shadowPaint.clearShadowLayer()

        // Тело стекла ручки (замена backdrop-filter: blur(1px) saturate(1.4))
        fillPaint.color = Color.argb(120, 20, 22, 32)
        canvas.drawCircle(kcx, kcy, knobR, fillPaint)

        // Радиальный градиент стекла (50% 50% rgba(255,255,255,0) 50% to rgba(255,255,255,.16) 100%)
        fillPaint.shader = RadialGradient(
            kcx, kcy, knobR,
            intArrayOf(Color.TRANSPARENT, Color.argb(41, 255, 255, 255)),
            floatArrayOf(0.50f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(kcx, kcy, knobR, fillPaint)

        // Блик стекла (70% 75% rgba(255,255,255,.5) 0 to rgba(255,255,255,0) 9%)
        fillPaint.shader = RadialGradient(
            kcx + knobR * 0.40f, kcy + knobR * 0.50f, knobR * 0.60f,
            intArrayOf(Color.argb(128, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.50f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(kcx, kcy, knobR, fillPaint)
        fillPaint.shader = null

        // Радужная плёнка по краю (статично, animation film без постоянной перерисовки):
        // conic-gradient (255,90,170), (90,200,255), (130,255,170), (255,225,100), (255,90,170)
        // маска radial-gradient 52% to 78%
        strokePaint.strokeWidth = knobR * 0.28f // 78% - 52% = 26%
        strokePaint.shader = SweepGradient(
            kcx, kcy,
            intArrayOf(
                Color.argb(110, 255, 90, 170),
                Color.argb(110, 90, 200, 255),
                Color.argb(100, 130, 255, 170),
                Color.argb(100, 255, 225, 100),
                Color.argb(110, 255, 90, 170)
            ),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f)
        )
        canvas.drawCircle(kcx, kcy, knobR * 0.65f, strokePaint)
        strokePaint.shader = null

        // Внутренний нейтральный блик ручки
        strokePaint.strokeWidth = 2f * s
        strokePaint.color = Color.argb(40, 255, 255, 255)
        canvas.drawCircle(kcx, kcy, knobR - 1.5f * s, strokePaint)

        // Внутренний нижний блик: inset 0 -2px 4px rgba(255,255,255,.12)
        strokePaint.strokeWidth = 2f * s
        strokePaint.color = Color.argb(35, 255, 255, 255)
        canvas.drawCircle(kcx, kcy - 1f * s, knobR - 1f * s, strokePaint)

        // Ободок ручки: border 1px solid rgba(255,255,255,.35)
        strokePaint.strokeWidth = 1f * s
        strokePaint.color = Color.argb(89, 255, 255, 255)
        canvas.drawCircle(kcx, kcy, knobR - 0.5f * s, strokePaint)

        // Главный блик ручки (#knob::before): left 5px, top 3.5px, width 10px, height 6px, rotate(-30deg)
        canvas.save()
        val hlCx = knobLeft + (5f + 5f) * s
        val hlCy = knobTop + (3.5f + 3f) * s
        canvas.rotate(-30f, hlCx, hlCy)
        val hlRect = RectF(
            knobLeft + 5f * s,
            knobTop + 3.5f * s,
            knobLeft + (5f + 10f) * s,
            knobTop + (3.5f + 6f) * s
        )
        fillPaint.shader = LinearGradient(
            hlRect.left, hlRect.top, hlRect.left, hlRect.bottom,
            intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(30, 255, 255, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(hlRect, fillPaint)
        fillPaint.shader = null
        canvas.restore()

        canvas.restore()
    }
}
