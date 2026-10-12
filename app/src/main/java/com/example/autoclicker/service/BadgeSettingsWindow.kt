package com.example.autoclicker.service

/**
 * Параметры плавающей кнопки (кнопка питания). Окна настроек кнопки больше нет,
 * значения фиксированные: компактный размер окна 100 dp, кнопка не плавает по экрану.
 */
data class BadgeCfg(
    val size: Int = 100,        // сторона окна кнопки, dp
    val opacity: Int = 100,     // 30..100 %
    val float: Boolean = false, // плавать по экрану (выключено)
    val speed: Int = 40,        // dp в секунду (не используется, пока float = false)
    val ringAlways: Boolean = false
)
