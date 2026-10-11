package com.example.autoclicker.ui.components

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Неоновые HUD-кнопки: Accessibility / Overlay / Вызов плавающей.
 * launchOnly=true — только постоянная кнопка вызова оверлея.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PermissionButtonsWebView(
    isOverlayGranted: Boolean,
    isAccessibilityGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onLaunchOverlay: () -> Unit = {},
    showLaunchButton: Boolean = true,
    launchOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val overlayRef = remember { arrayOf(onRequestOverlay) }
    val accRef = remember { arrayOf(onRequestAccessibility) }
    val launchRef = remember { arrayOf(onLaunchOverlay) }
    overlayRef[0] = onRequestOverlay
    accRef[0] = onRequestAccessibility
    launchRef[0] = onLaunchOverlay

    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val heightDp = if (launchOnly) 90.dp else 110.dp

    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewHolder[0] = this
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.allowFileAccess = true
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun requestOverlay() {
                        post { overlayRef[0].invoke() }
                    }

                    @JavascriptInterface
                    fun requestAccessibility() {
                        post { accRef[0].invoke() }
                    }

                    @JavascriptInterface
                    fun requestLaunch() {
                        post { launchRef[0].invoke() }
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        applyState(view, isOverlayGranted, isAccessibilityGranted, showLaunchButton, launchOnly)
                    }

                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                        com.example.autoclicker.data.EventLogManager.log(
                            com.example.autoclicker.data.EventLogManager.TAG_OVERLAY,
                            "WEBVIEW: Render process gone in PermissionButtonsWebView (didCrash=${detail?.didCrash()})",
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
                loadUrl("file:///android_asset/permission_buttons.html")
            }
        },
        update = { wv ->
            applyState(wv, isOverlayGranted, isAccessibilityGranted, showLaunchButton, launchOnly)
        }
    )

    LaunchedEffect(isOverlayGranted, isAccessibilityGranted, showLaunchButton, launchOnly) {
        applyState(webViewHolder[0], isOverlayGranted, isAccessibilityGranted, showLaunchButton, launchOnly)
    }
}

private fun applyState(
    view: WebView?,
    overlay: Boolean,
    accessibility: Boolean,
    showLaunch: Boolean,
    launchOnly: Boolean
) {
    if (view == null) return
    val js = "window.setPermissions && window.setPermissions({" +
        "overlay: ${if (overlay) "true" else "false"}, " +
        "accessibility: ${if (accessibility) "true" else "false"}, " +
        "showLaunch: ${if (showLaunch) "true" else "false"}, " +
        "launchOnly: ${if (launchOnly) "true" else "false"}});"
    try {
        view.evaluateJavascript(js, null)
    } catch (_: Exception) {
    }
}
