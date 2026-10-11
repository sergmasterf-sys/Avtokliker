package com.example.autoclicker.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Чёрная дыра внутри приложения: анимация из assets/black_hole_app.html
 * (диск, джеты, падающие частицы, неоновое кольцо при нажатии).
 * Нажатие вызывает [onClick] — запуск плавающей кнопки.
 * Если WebView не создался — показывается обычная кнопка, приложение не вылетает.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun BlackHoleWebButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp
) {
    val context = LocalContext.current
    val clickRef = remember { arrayOf(onClick) }
    clickRef[0] = onClick

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        factory = {
            try {
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(Color.TRANSPARENT)
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.allowFileAccess = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    isVerticalScrollBarEnabled = false
                    isHorizontalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_NEVER

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onBlackHoleClick() {
                            post { clickRef[0].invoke() }
                        }
                    }, "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(
                                "window.dispatchEvent(new Event('resize'))", null
                            )
                        }

                        override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                            com.example.autoclicker.data.EventLogManager.log(
                                com.example.autoclicker.data.EventLogManager.TAG_OVERLAY,
                                "WEBVIEW: Render process gone in BlackHoleWebButton (didCrash=${detail?.didCrash()})",
                                isError = true
                            )
                            try {
                                view?.let {
                                    (it.parent as? ViewGroup)?.removeView(it)
                                    it.destroy()
                                }
                            } catch (_: Throwable) {}
                            return true
                        }
                    }
                    loadUrl("file:///android_asset/black_hole_app.html")
                }
            } catch (_: Throwable) {
                FrameLayout(context).apply {
                    addView(TextView(context).apply {
                        text = "Запустить плавающую кнопку"
                        setTextColor(Color.parseColor("#FF7A18"))
                        textSize = 16f
                        gravity = android.view.Gravity.CENTER
                        setOnClickListener { clickRef[0].invoke() }
                    }, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                }
            }
        },
        onRelease = { v ->
            try {
                (v as? WebView)?.let {
                    it.loadUrl("about:blank")
                    it.destroy()
                }
            } catch (_: Throwable) {
            }
        }
    )
}
