package com.example.autoclicker

import com.example.autoclicker.engine.ColorBlobFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистый JVM юнит-тест для ColorBlobFinder (без Robolectric и без Android-зависимостей).
 */
class ColorBlobFinderTest {

    @Test
    fun testFindYellowButtonWithTextAndNearbyNoise() {
        val width = 800
        val height = 400
        val screenW = 1340
        val screenH = 800

        val pixels = IntArray(width * height)
        val bgPixel = (0xFF shl 24) or (30 shl 16) or (35 shl 8) or 40
        pixels.fill(bgPixel)

        val yellowPixel = (0xFF shl 24) or (245 shl 16) or (190 shl 8) or 25
        val blackPixel = (0xFF shl 24) or (15 shl 16) or (15 shl 8) or 15

        // 1. Рисуем кнопку "НАЧАТЬ": 250 x 90, левый верхний угол (100, 100)
        val btnLeft = 100
        val btnTop = 100
        val btnWidth = 250
        val btnHeight = 90
        val btnRight = btnLeft + btnWidth - 1
        val btnBottom = btnTop + btnHeight - 1

        for (y in btnTop..btnBottom) {
            val row = y * width
            for (x in btnLeft..btnRight) {
                pixels[row + x] = yellowPixel
            }
        }

        // 2. Имитируем чёрный текст "НАЧАТЬ" внутри кнопки
        // Несколько полос черных пикселей (буквы)
        for (y in (btnTop + 25)..(btnTop + 65)) {
            val row = y * width
            // Буква Н
            for (x in (btnLeft + 40)..(btnLeft + 45)) pixels[row + x] = blackPixel
            for (x in (btnLeft + 60)..(btnLeft + 65)) pixels[row + x] = blackPixel
            if (y in (btnTop + 42)..(btnTop + 48)) {
                for (x in (btnLeft + 45)..(btnLeft + 60)) pixels[row + x] = blackPixel
            }
            // Буква А
            for (x in (btnLeft + 75)..(btnLeft + 80)) pixels[row + x] = blackPixel
            for (x in (btnLeft + 95)..(btnLeft + 100)) pixels[row + x] = blackPixel
            if (y in (btnTop + 25)..(btnTop + 30) || y in (btnTop + 45)..(btnTop + 50)) {
                for (x in (btnLeft + 80)..(btnLeft + 95)) pixels[row + x] = blackPixel
            }
            // Буква Ч
            for (x in (btnLeft + 110)..(btnLeft + 115)) pixels[row + x] = blackPixel
            for (x in (btnLeft + 130)..(btnLeft + 135)) pixels[row + x] = blackPixel
            // Буква Т
            for (x in (btnLeft + 155)..(btnLeft + 160)) pixels[row + x] = blackPixel
            if (y in (btnTop + 25)..(btnTop + 30)) {
                for (x in (btnLeft + 145)..(btnLeft + 170)) pixels[row + x] = blackPixel
            }
        }

        // 3. Отдельное желтое пятно (имитация кроссовок персонажа рядом: 70 x 45 на (480, 120))
        val shoeLeft = 480
        val shoeTop = 120
        val shoeRight = shoeLeft + 70
        val shoeBottom = shoeTop + 45
        for (y in shoeTop..shoeBottom) {
            val row = y * width
            for (x in shoeLeft..shoeRight) {
                pixels[row + x] = yellowPixel
            }
        }

        // 4. Поиск через ColorBlobFinder
        val result = ColorBlobFinder.findYellowButton(
            pixels = pixels,
            width = width,
            height = height,
            step = 2,
            screenW = screenW,
            screenH = screenH
        )

        // 5. Проверка: находится именно кнопка
        assertNotNull("Кнопка должна быть найдена", result.best)
        val best = result.best!!

        // Размеры рамки должны соответствовать кнопке 250 x 90 (с погрешностью шага step=2 и закрытия)
        assertTrue("Ширина кнопки должна быть около 250 (найдено: ${best.width})", best.width in 240..265)
        assertTrue("Высота кнопки должна быть около 90 (найдено: ${best.height})", best.height in 85..100)

        // Центр должен быть в середине кнопки: X ~ 224.5, Y ~ 144.5
        val expectedCenterX = (btnLeft + btnRight) / 2f
        val expectedCenterY = (btnTop + btnBottom) / 2f
        assertEquals("Координата центра X", expectedCenterX, best.centerX, 4.0f)
        assertEquals("Координата центра Y", expectedCenterY, best.centerY, 4.0f)

        // Плотность кнопки должна быть высокой (>= 0.70 благодаря закрытию дырок от текста)
        assertTrue("Плотность кнопки должна быть >= 0.70 (найдено: ${best.density})", best.density >= 0.70f)
    }
}
