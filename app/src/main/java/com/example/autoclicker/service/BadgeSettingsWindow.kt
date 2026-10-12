package com.example.autoclicker.service

/**
 * Параметры плавающей кнопки (чёрная дыра). Окна настроек кнопки больше нет,
 * значения фиксированные: кнопка не плавает по экрану и не тратит ресурсы.
 */
data class BadgeCfg(
    val size: Int = 120,        // сторона окна кнопки, dp
    val opacity: Int = 100,     // 30..100 %
    val float: Boolean = false, // плавать по экрану (выключено)
    val speed: Int = 40,        // dp в секунду (не используется, пока float = false)
    val ringAlways: Boolean = false
)
