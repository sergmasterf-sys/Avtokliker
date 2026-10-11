package com.example.autoclicker.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Плавающая кнопка: WebView со сценой из assets/black_hole_button.html?mode=badge
 * isRunning=true → зелёный контур ядра. ringAlways → контур горит всегда.
 * Если WebView недоступен (или упал при создании) — рисуется простая нативная чёрная дыра,
 * чтобы приложение не вылетало.
 */
class BlackHoleBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var webView: WebView? = null
    private var clickListener: (() -> Unit)? = null
    private var longClickListener: (() -> Unit)? = null

    var isRunning: Boolean = false
        set(value) {
            field = value
            pushRing()
        }

    var ringAlways: Boolean = false
        set(value) {
            field = value
            pushRing()
        }

    private fun pushRing() {
        post {
            try {
                webView?.evaluateJavascript(
                    "window.setRunning && window.setRunning(${isRunning});" +
                            "window.setRingAlways && window.setRingAlways(${ringAlways});",
                    null
                )
            } catch (_: Throwable) {
            }
        }
    }

    private var fallbackView: View? = null
    private var lastCrashTime = 0L

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        clipToPadding = false
        val wv = try {
            createWebView()
        } catch (_: Throwable) {
            null
        }
        if (wv != null) {
            webView = wv
            addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            showFallback()
        }
    }

    private fun showFallback() {
        if (fallbackView == null) {
            val fb = FallbackHoleView(context)
            fallbackView = fb
            addView(fb, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        fallbackView?.visibility = View.VISIBLE
    }

    private fun scheduleRecreation() {
        val now = System.currentTimeMillis()
        if (now - lastCrashTime >= 30_000L) {
            lastCrashTime = now
            postDelayed({
                try {
                    val wv = createWebView()
                    webView = wv
                    fallbackView?.visibility = View.GONE
                    addView(wv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    com.example.autoclicker.data.EventLogManager.log(
                        com.example.autoclicker.data.EventLogManager.TAG_OVERLAY,
                        "WEBVIEW: BlackHoleBadgeView успешно пересоздан"
                    )
                } catch (t: Throwable) {
                    com.example.autoclicker.data.EventLogManager.log(
                        com.example.autoclicker.data.EventLogManager.TAG_OVERLAY,
                        "WEBVIEW: Не удалось пересоздать BlackHoleBadgeView: ${t.message}",
                        isError = true
                    )
                }
            }, 30_000L)
        }
    }

    fun pauseAnimation() {
        post {
            try {
                webView?.onPause()
                webView?.pauseTimers()
                webView?.evaluateJavascript("window.pause && window.pause();", null)
            } catch (_: Throwable) {}
        }
    }

    fun resumeAnimation() {
        post {
            try {
                webView?.onResume()
                webView?.resumeTimers()
                webView?.evaluateJavascript("window.resume && window.resume();", null)
            } catch (_: Throwable) {}
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            resumeAnimation()
        } else {
            pauseAnimation()
        }
    }

    fun setOnBadgeClick(listener: () -> Unit) {
        clickListener = listener
    }

    fun setOnBadgeLongClick(listener: () -> Unit) {
        longClickListener = listener
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createWebView(): WebView {
        return WebView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(LAYER_TYPE_HARDWARE, null)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            isClickable = false
            isFocusable = false

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onBlackHoleClick() {
                    post { clickListener?.invoke() }
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pushRing()
                }

                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    com.example.autoclicker.data.EventLogManager.log(
                        com.example.autoclicker.data.EventLogManager.TAG_OVERLAY,
                        "WEBVIEW: Render process gone in BlackHoleBadgeView (didCrash=${detail?.didCrash()})",
                        isError = true
                    )
                    try {
                        view?.let {
                            (it.parent as? ViewGroup)?.removeView(it)
                            it.destroy()
                        }
                    } catch (_: Throwable) {}
                    webView = null
                    showFallback()
                    scheduleRecreation()
                    return true
                }
            }
            loadUrl("file:///android_asset/black_hole_button.html?mode=badge")
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // перехватываем touch для drag в FloatingOverlayService
        return true
    }

    override fun onDetachedFromWindow() {
        try {
            webView?.let { wv ->
                wv.loadUrl("about:blank")
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        } catch (_: Throwable) {
        }
        webView = null
        super.onDetachedFromWindow()
    }

    /** Запасной вариант без WebView: чёрный круг с оранжевым кольцом. */
    private class FallbackHoleView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.2f
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.35f
            p.color = Color.parseColor("#FF7A18")
            c.drawCircle(cx, cy, r * 1.5f, p)
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            c.drawCircle(cx, cy, r, p)
        }
    }
}
