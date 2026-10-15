package com.example.autoclicker.data

/**
 * Набор настроек автокликера (только умный режим).
 *
 * @param overlayX Последняя сохранённая координата X плавающего окна.
 * @param overlayY Последняя сохранённая координата Y плавающего окна.
 * @param isSmartMode Включён ли Умный режим.
 * @param isDebugScreenshots Сохранять ли отладочные снимки экрана.
 * @param neonBrightness Яркость неона 0..100.
 */
data class ClickerSettings(
    val overlayX: Int = 100,
    val overlayY: Int = 200,
    val isSmartMode: Boolean = true,
    val isDebugScreenshots: Boolean = false,
    val neonBrightness: Int = 85
)
