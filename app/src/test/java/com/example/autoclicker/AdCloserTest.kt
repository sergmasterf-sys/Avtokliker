package com.example.autoclicker

import com.example.autoclicker.engine.AdCrossDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Чистый JVM юнит-тест детектора крестика (без Android). Картинки рисуем в коде.
 */
class AdCloserTest {

    private val W = 400
    private val H = 200

    private fun argb(v: Int): Int = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    private fun noisyBackground(seed: Long): IntArray {
        val rnd = Random(seed)
        return IntArray(W * H) { argb(60 + rnd.nextInt(40)) }
    }

    private fun fillCircle(px: IntArray, cx: Int, cy: Int, r: Int, v: Int) {
        for (y in 0 until H) for (x in 0 until W) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) px[y * W + x] = argb(v)
        }
    }

    private fun line(px: IntArray, x0: Int, y0: Int, x1: Int, y1: Int, thick: Int, v: Int) {
        val steps = maxOf(Math.abs(x1 - x0), Math.abs(y1 - y0)) * 2
        for (k in 0..steps) {
            val x = x0 + (x1 - x0) * k / steps
            val y = y0 + (y1 - y0) * k / steps
            for (dy in -(thick / 2)..(thick / 2)) for (dx in -(thick / 2)..(thick / 2)) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until W && yy in 0 until H) px[yy * W + xx] = argb(v)
            }
        }
    }

    private fun drawX(px: IntArray, cx: Int, cy: Int, half: Int, thick: Int, v: Int) {
        line(px, cx - half, cy - half, cx + half, cy + half, thick, v)
        line(px, cx + half, cy - half, cx - half, cy + half, thick, v)
    }

    @Test
    fun findsLightCrossOnDarkCircle() {
        val px = noisyBackground(1)
        fillCircle(px, 300, 50, 20, 30)
        drawX(px, 300, 50, 10, 3, 240)

        val c = AdCrossDetector.detect(px, W, H, 1f, 0, 0)
        assertNotNull(c)
        assertEquals(300f, c!!.x, 8f)
        assertEquals(50f, c.y, 8f)
    }

    @Test
    fun findsDarkCrossOnLightCircle() {
        val px = IntArray(W * H) { argb(190) }
        fillCircle(px, 300, 50, 20, 235)
        drawX(px, 300, 50, 9, 3, 20)

        val c = AdCrossDetector.detect(px, W, H, 1f, 0, 0)
        assertNotNull(c)
        assertEquals(300f, c!!.x, 8f)
        assertEquals(50f, c.y, 8f)
    }

    @Test
    fun appliesRoiOffsetAndScale() {
        // Экран в 2 раза крупнее рабочего масштаба: unit = 2, нарисуем крестик вдвое больше
        val bw = 800
        val bh = 400
        val px = IntArray(bw * bh) { argb(70) }
        for (y in 0 until bh) for (x in 0 until bw) {
            val dx = x - 600
            val dy = y - 100
            if (dx * dx + dy * dy <= 40 * 40) px[y * bw + x] = argb(30)
        }
        fun thickLine(x0: Int, y0: Int, x1: Int, y1: Int) {
            val steps = 400
            for (k in 0..steps) {
                val x = x0 + (x1 - x0) * k / steps
                val y = y0 + (y1 - y0) * k / steps
                for (dy in -3..3) for (dx in -3..3) px[(y + dy) * bw + (x + dx)] = argb(245)
            }
        }
        thickLine(580, 80, 620, 120)
        thickLine(620, 80, 580, 120)

        val c = AdCrossDetector.detect(px, bw, bh, 2f, 1000, 0)
        assertNotNull(c)
        assertEquals(1600f, c!!.x, 16f) // 1000 (смещение ROI) + 600
        assertEquals(100f, c.y, 16f)
    }

    @Test
    fun ignoresPlainBackgroundAndStripes() {
        val px = noisyBackground(2)
        assertNull(AdCrossDetector.detect(px, W, H, 1f, 0, 0))

        for (y in 20 until 120 step 10) {
            for (yy in y until y + 3) for (x in 100 until 380) px[yy * W + x] = argb(230)
        }
        assertNull(AdCrossDetector.detect(px, W, H, 1f, 0, 0))
    }

    @Test
    fun ignoresPlusSignAndSolidDisc() {
        val plus = noisyBackground(3)
        fillCircle(plus, 300, 50, 20, 30)
        line(plus, 290, 50, 310, 50, 3, 240)
        line(plus, 300, 40, 300, 60, 3, 240)
        assertNull(AdCrossDetector.detect(plus, W, H, 1f, 0, 0))

        val disc = noisyBackground(4)
        fillCircle(disc, 300, 50, 12, 240)
        assertNull(AdCrossDetector.detect(disc, W, H, 1f, 0, 0))
    }

    @Test
    fun roiIsInTopRightPart() {
        val r = AdCrossDetector.roiRect(2400, 1080)
        assertTrue(r[0] >= 1000)
        assertEquals(2400, r[2])
        assertEquals(0, r[1])
        assertTrue(r[3] <= 600)
    }
}
