package com.example.autoclicker.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.example.autoclicker.data.EventLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class AutoClickAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityServiceHolder.setService(this)
        AccessibilityStatus.setRunning(true)
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис подключен")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Не требуется для кликера
    }

    override fun onInterrupt() {
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис прерван")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AccessibilityServiceHolder.setService(null)
        AccessibilityStatus.setRunning(false)
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис отвязан системой (onUnbind)")
        return true
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        instance = this
        AccessibilityServiceHolder.setService(this)
        AccessibilityStatus.setRunning(true)
        EventLogManager.log(EventLogManager.TAG_ACCESSIBILITY, "ACCESSIBILITY: Сервис переподключен (onRebind)")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        AccessibilityServiceHolder.setService(null)
        AccessibilityStatus.setRunning(false)
        serviceScope.cancel()
        screenshotExecutor.shutdown()
    }

    @Volatile
    private var lastScreenshotErrorCode = 0

    suspend fun takeScreenshotCompat(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Скриншот не поддерживается на Android < 11")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            lastScreenshotErrorCode = 0
                            var hardwareBuffer: android.hardware.HardwareBuffer? = null
                            var wrappedBitmap: Bitmap? = null
                            var copy: Bitmap? = null
                            try {
                                hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                if (hardwareBuffer != null) {
                                    wrappedBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                    copy = wrappedBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                }
                            } catch (e: Exception) {
                                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Ошибка обработки скриншота: ${e.message}")
                            } finally {
                                try {
                                    wrappedBitmap?.recycle()
                                } catch (_: Throwable) {}
                                try {
                                    hardwareBuffer?.close()
                                } catch (_: Throwable) {}
                            }
                            if (continuation.isActive) {
                                continuation.resume(copy)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            lastScreenshotErrorCode = errorCode
                            val n = System.currentTimeMillis()
                            if (n - lastShotFailLogAt > 5000L) {
                                lastShotFailLogAt = n
                                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Ошибка захвата экрана: код $errorCode")
                            }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                EventLogManager.log(EventLogManager.TAG_AUTO_CLICKER, "SMART: Исключение при вызове takeScreenshot: ${e.message}")
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private var lastScreenshotAt = 0L
    private var lastShotFailLogAt = 0L

    suspend fun takeScreenshotBitmap(minIntervalMs: Long = 350L): Bitmap? {
        val interval = minIntervalMs.coerceAtLeast(350L)
        val now = System.currentTimeMillis()
        val wait = interval - (now - lastScreenshotAt)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        lastScreenshotAt = System.currentTimeMillis()
        var bmp = takeScreenshotCompat()
        if (bmp == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            lastScreenshotErrorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        ) {
            kotlinx.coroutines.delay(150L)
            lastScreenshotAt = System.currentTimeMillis()
            bmp = takeScreenshotCompat()
        }
        return bmp
    }

    companion object {
        @Volatile
        var instance: AutoClickAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        val isServiceRunning: Boolean
            get() = instance != null
    }
}
