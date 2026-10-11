package com.example.autoclicker.service

import kotlinx.coroutines.launch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.autoclicker.data.ClickerSettings
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.data.SettingsRepository
import com.example.autoclicker.data.SmartConfigManager
import com.example.autoclicker.engine.SmartEngine
import com.example.autoclicker.ui.theme.NeonTheme

/**
 * Отдельное плавающее окно настроек (TYPE_APPLICATION_OVERLAY) в стиле liquid-glass-neon.
 * Перетаскивается за заголовок, содержит кнопку «✕». Ширина не более 70% экрана.
 * Окна: Умный режим, Ещё (яркость и цвет неона, сброс, закрыть).
 */
@SuppressLint("ClickableViewAccessibility")
class SettingsPopupWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    private val settingsRepo: SettingsRepository,
    private val configManager: SmartConfigManager,
    private val smartEngine: SmartEngine,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onOpenMainActivity()
        fun onCloseService()
        fun onDismiss()
        fun onOpenBadgeSettings()
    }

    enum class WindowType {
        SMART, MORE
    }

    var currentType: WindowType? = null
        private set

    private var rootLayout: LinearLayout? = null
    private var contentContainer: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var isShown = false
    private val density = context.resources.displayMetrics.density

    // Слайдеры, которые сейчас может тянуть палец: пока тянем, окно не перерисовываем
    private val sliders = mutableListOf<NeonGlassSlider>()

    private fun dp(value: Int): Int = (value * density).toInt()

    fun isShowing(): Boolean = isShown

    fun show(type: WindowType, anchorX: Int = 0, anchorY: Int = 0, anchorWidth: Int = 0, anchorHeight: Int = 0) {
        if (isShown) {
            dismiss()
        }
        currentType = type

        val (screenWidth, screenHeight) = getScreenSize()
        val windowWidth = (minOf(screenWidth, screenHeight) * 0.70f).toInt().coerceAtLeast(dp(240))
        val maxWindowHeight = (screenHeight * 0.65f).toInt()

        val initialX = if (anchorX + anchorWidth + windowWidth + dp(12) < screenWidth) {
            anchorX + anchorWidth + dp(8)
        } else {
            (anchorX - windowWidth - dp(8)).coerceAtLeast(dp(8))
        }
        val initialY = anchorY.coerceIn(dp(24), (screenHeight - maxWindowHeight - dp(24)).coerceAtLeast(dp(24)))

        val params = WindowManager.LayoutParams(
            windowWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        windowParams = params

        val root = NeonPanel(context, 24f).apply {
            setPadding(dp(14), dp(12), dp(14), dp(14))
            isClickable = true
        }
        rootLayout = root

        root.addView(createHeader(type))

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        contentContainer = content

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                minOf(maxWindowHeight, dp(420))
            )
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
        root.addView(scrollView)

        populateContent(type)

        try {
            windowManager.addView(root, params)
            isShown = true
        } catch (e: Exception) {
            EventLogManager.log(
                EventLogManager.TAG_OVERLAY,
                "ERROR: не удалось открыть popup: ${e.message}",
                isError = true
            )
            isShown = false
        }
    }

    private fun createHeader(type: WindowType): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(2), dp(8))

            val titleText = when (type) {
                WindowType.SMART -> "Умный режим"
                WindowType.MORE -> "Ещё"
            }

            addView(TextView(context).apply {
                text = titleText
                setTextColor(0xFFF1F4FB.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            addView(NeonGlassButton(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                cornerDp = 12f
                hueOffset = -60f
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                setOnClickListener { dismiss() }
            })

            setupDrag(this)
        }
    }

    private fun setupDrag(view: View) {
        var startX = 0
        var startY = 0
        var touchStartX = 0f
        var touchStartY = 0f

        view.setOnTouchListener { _, event ->
            val p = windowParams ?: return@setOnTouchListener false
            val root = rootLayout ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = p.x
                    startY = p.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = startX + (event.rawX - touchStartX).toInt()
                    p.y = startY + (event.rawY - touchStartY).toInt()
                    try {
                        windowManager.updateViewLayout(root, p)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    fun updateSettings(settings: ClickerSettings) {
        if (!isShown) return
        val type = currentType ?: return
        // Не пересоздаём окно, пока палец на слайдере (иначе слайдер «вылетит» из-под пальца)
        if (sliders.any { it.dragging }) return
        populateContent(type)
    }

    fun updateNeonColor() {
        if (!isShown) return
        rootLayout?.invalidate()
    }

    private fun populateContent(type: WindowType) {
        val container = contentContainer ?: return
        container.removeAllViews()
        sliders.clear()

        when (type) {
            WindowType.SMART -> populateSmartMode(container)
            WindowType.MORE -> populateMore(container)
        }
    }

    // ---------- Вспомогательные фабрики ----------

    private fun card(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(0x2214182C)
            setStroke(dp(1), 0x26FFFFFF)
        }
        setPadding(dp(10), dp(8), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
    }

    private fun infoRow(left: String, right: String, rightColor: Int, rightSp: Float = 11f): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = left
                setTextColor(0xFFF1F4FB.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = right
                setTextColor(rightColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, rightSp)
                typeface = Typeface.DEFAULT_BOLD
            })
        }

    private fun twoButtons(left: NeonGlassButton, right: NeonGlassButton): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            left.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(6) }
            right.layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            addView(left)
            addView(right)
        }

    private fun twoButtons(left: View, mid: View, right: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            left.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            mid.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            right.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(left)
            addView(mid)
            addView(right)
        }

    private fun fullButton(btn: NeonGlassButton, heightDp: Int = 44, bottomDp: Int = 8): NeonGlassButton = btn.apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp)
        ).apply { bottomMargin = dp(bottomDp) }
    }

    private fun emptyText(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0xFFB0BEC5.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(16), dp(8), dp(16))
    }

    private fun toggle(text: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit): NeonGlassToggle =
        NeonGlassToggle(context).apply {
            this.text = text
            setCheckedSilently(checked)
            isEnabled = enabled
            onCheckedChange = onChange
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(8) }
        }

    private fun slider(min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): NeonGlassSlider =
        NeonGlassSlider(context).apply {
            this.min = min
            this.max = max
            this.value = value
            formatter = fmt
            this.onChange = onChange
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { bottomMargin = dp(8) }
            sliders.add(this)
        }

    private fun showConfirmDialog(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Сбросить") { _, _ -> onConfirm() }
            .setNegativeButton("Отмена", null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        dialog.show()
    }


    // ===== «УМНЫЙ РЕЖИМ» =====
    private fun populateSmartMode(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()
        val isApi30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        container.addView(
            toggle("Умный режим", settings.isSmartMode && isApi30, enabled = isApi30) {
                settingsRepo.updateRunSmart(it)
            }
        )

        val descView = TextView(context).apply {
            text = "Активный конфиг: " + configManager.describeActive()
            setTextColor(Color.parseColor("#80D8FF"))
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(6))
        }
        container.addView(descView)

        container.addView(
            fullButton(
                glassButton(context, "Сбросить умный режим", hueOffset = -90f, textSp = 12f) {
                    showConfirmDialog("Вы уверены?", "Сбросить умный режим?") {
                        com.example.autoclicker.data.ResetManager.resetSmartMode(context)
                        populateContent(WindowType.SMART)
                    }
                }, heightDp = 40, bottomDp = 8
            )
        )

        // ===== НАСТРОЙКИ ПЛАВАЮЩЕЙ КНОПКИ =====
        container.addView(neonLabel(context, "ПЛАВАЮЩАЯ КНОПКА"))
        container.addView(
            glassButton(context, "Настройки кнопки (размер, прозрачность…)", hueOffset = 10f, textSp = 12f) {
                callbacks.onOpenBadgeSettings()
            }
        )
        container.addView(TextView(context).apply {
            text = "Открывает настройки самой плавающей кнопки (чёрная дыра)."
            setTextColor(0xFF90A4AE.toInt())
            textSize = 11f
            setPadding(dp(8), dp(2), dp(8), dp(8))
        })

        // ===== ДОПОЛНИТЕЛЬНО (ПЕРЕНЕСЕНО ИЗ «ЕЩЁ») =====
        container.addView(neonLabel(context, "ДЕЙСТВИЯ"))
        container.addView(
            fullButton(glassButton(context, "Открыть приложение", 44f, 18f, 40f, false, 12f) {
                callbacks.onOpenMainActivity()
                dismiss()
            }, heightDp = 42, bottomDp = 8)
        )
        container.addView(
            fullButton(glassButton(context, "Сбросить ВСЁ", 44f, 18f, -110f, false, 12f) {
                showConfirmDialog("Вы уверены?", "Сбросить все настройки и режимы?") {
                    com.example.autoclicker.data.ResetManager.resetAll(context)
                    populateContent(WindowType.SMART)
                }
            }.apply { setTextColor(0xFFFF8A80.toInt()) }, heightDp = 42, bottomDp = 4)
        )

    }

    // ===== «ЕЩЁ» =====
    private fun populateMore(container: LinearLayout) {
        val settings = settingsRepo.getLatestSettings()



        // Неон: яркость и цвет
        container.addView(neonLabel(context, "ЯРКОСТЬ НЕОНА"))
        container.addView(
            slider(0, 100, settings.neonBrightness, { "$it%" }) { settingsRepo.updateNeonBrightness(it) }
        )
        container.addView(
            fullButton(
                glassButton(context, "Сбросить яркость неона", hueOffset = -90f, textSp = 11f) {
                    showConfirmDialog("Вы уверены?", "Сбросить яркость неона?") {
                        com.example.autoclicker.data.ResetManager.resetNeonBrightness(context)
                        populateContent(WindowType.MORE)
                    }
                }, heightDp = 36, bottomDp = 8
            )
        )
        container.addView(
            toggle("Автосмена цвета", NeonTheme.isAuto) { NeonTheme.setAuto(it) }
        )
        container.addView(
            fullButton(
                glassButton(context, "Следующий цвет неона", 44f, 18f, 30f, false, 12f) {
                    NeonTheme.setHue(NeonTheme.getHue() + 30f)
                    populateContent(WindowType.MORE)
                }, 44, 8
            )
        )

        // Сброс всего
        container.addView(neonLabel(context, "СБРОС"))
        container.addView(
            fullButton(glassButton(context, "Сбросить ВСЁ", 44f, 18f, -110f, false, 12f) {
                showConfirmDialog("Вы уверены?", "Сбросить все настройки и режимы?") {
                    com.example.autoclicker.data.ResetManager.resetAll(context)
                    populateContent(WindowType.MORE)
                }
            }.apply { setTextColor(0xFFFF8A80.toInt()) }, 44, 10)
        )

        // Остальное
        container.addView(
            fullButton(glassButton(context, "Открыть приложение", 44f, 18f, 40f, false, 12f) {
                callbacks.onOpenMainActivity()
                dismiss()
            })
        )
        container.addView(
            fullButton(glassButton(context, "Закрыть плавающее окно", 44f, 18f, -110f, false, 12f) {
                callbacks.onCloseService()
            }.apply { setTextColor(0xFFFF8A80.toInt()) }, 44, 0)
        )
    }

    fun dismiss() {
        if (!isShown) return
        isShown = false
        currentType = null
        rootLayout?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        rootLayout = null
        contentContainer = null
        windowParams = null
        sliders.clear()
        callbacks.onDismiss()
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(m)
            Pair(m.widthPixels, m.heightPixels)
        }
    }
}
