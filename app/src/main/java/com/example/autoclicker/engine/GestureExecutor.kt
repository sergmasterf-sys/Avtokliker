package com.example.autoclicker.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.example.autoclicker.data.EventLogManager
import com.example.autoclicker.service.AccessibilityServiceHolder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Исполнитель жестов.
 * Отвечает ТОЛЬКО за формирование и отправку жестов в Android AccessibilityService.
 */
class GestureExecutor {

    /**
     * Выполняет короткое нажатие (TAP) по заданным координатам экрана.
     *
     * @param x Координата X
     * @param y Координата Y
     * @param duration Длительность касания в миллисекундах (по умолчанию 50ms)
     * @return true если жест успешно выполнен, false при ошибке или отмене
     */
    suspend fun performTap(x: Float, y: Float, duration: Long = 80L): Boolean {
        val service = AccessibilityServiceHolder.service.value
        if (service == null) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: AccessibilityService недоступен или отключен в настройках системы",
                isError = true
            )
            return false
        }

        if (x <= 0f && y <= 0f) {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: Некорректные координаты клика ($x, $y)",
                isError = true
            )
            return false
        }

        // Ограничиваем ожидание завершения жеста таймаутом в 800 мс
        val result = withTimeoutOrNull(800L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                try {
                    val path = Path().apply {
                        moveTo(x, y)
                        lineTo(x, y + 1f)
                    }
                    val strokeDuration = duration.coerceIn(40L, 200L)
                    val stroke = GestureDescription.StrokeDescription(path, 0L, strokeDuration)
                    val gesture = GestureDescription.Builder()
                        .addStroke(stroke)
                        .build()

                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "GESTURE: completed на (${x.toInt()}, ${y.toInt()}) [длительность ${strokeDuration}мс]"
                            )
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            EventLogManager.log(
                                EventLogManager.TAG_GESTURE,
                                "GESTURE: cancelled на (${x.toInt()}, ${y.toInt()}) — жест отменен системой",
                                isError = true
                            )
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }

                    val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
                    if (!dispatched) {
                        EventLogManager.log(
                            EventLogManager.TAG_GESTURE,
                            "GESTURE: rejected — dispatchGesture вернул false. Проверьте разрешение жестов у сервиса.",
                            isError = true
                        )
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                } catch (e: Exception) {
                    EventLogManager.log(
                        EventLogManager.TAG_GESTURE,
                        "ERROR: Exception in dispatchGesture: ${e.message}",
                        isError = true
                    )
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        }

        return result ?: run {
            EventLogManager.log(
                EventLogManager.TAG_GESTURE,
                "ERROR: Gesture execution timed out",
                isError = true
            )
            false
        }
    }
}
