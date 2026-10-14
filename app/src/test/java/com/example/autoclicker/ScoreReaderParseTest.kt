package com.example.autoclicker

import com.example.autoclicker.engine.MatchScore
import com.example.autoclicker.engine.OcrToken
import com.example.autoclicker.engine.ScoreReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Чистый JVM-тест разбора счёта (без ML Kit): проверяет геометрию панели на разных экранах.
 * Эталон 1340x800: синий счёт ~ x=548, красный ~ x=793, «Цель: 40» по центру сверху, таймер под ним.
 */
class ScoreReaderParseTest {

    private fun parse(tokens: List<OcrToken>, w: Int, h: Int) = ScoreReader.parseScoreTokens(tokens, w, h)

    @Test
    fun readsScoreAndTarget() {
        val s = parse(
            listOf(
                OcrToken("39", 548f, 30f), OcrToken("26", 793f, 30f),
                OcrToken("Цель:", 655f, 15f), OcrToken("40", 710f, 15f), OcrToken("02:55", 688f, 41f)
            ), 1340, 800
        )
        assertEquals(MatchScore(39, 26, 40), s)
    }

    @Test
    fun fixesTypicalOcrMistakes() {
        assertEquals(MatchScore(12, 8, null), parse(listOf(OcrToken("I2", 548f, 30f), OcrToken("8", 793f, 30f)), 1340, 800))
        assertEquals(MatchScore(12, 8, null), parse(listOf(OcrToken("1", 540f, 30f), OcrToken("2", 556f, 30f), OcrToken("8", 793f, 30f)), 1340, 800))
    }

    @Test
    fun worksOnOtherScreenSizes() {
        val k = 1.35f
        val phone = parse(
            listOf(OcrToken("39", 1200f - 122 * k, 30 * k), OcrToken("26", 1200f + 123 * k, 30 * k), OcrToken("40", 1200f + 18 * k, 15 * k)),
            2400, 1080
        )
        assertEquals(MatchScore(39, 26, 40), phone)
        val tablet = parse(listOf(OcrToken("37", 800f - 183f, 45f), OcrToken("30", 800f + 184f, 45f)), 1600, 1200)
        assertEquals(MatchScore(37, 30, null), tablet)
    }

    @Test
    fun noBannerMeansNull() {
        assertNull(parse(listOf(OcrToken("НАЧАТЬ", 136f, 714f), OcrToken("00:07", 120f, 690f)), 1340, 800))
        assertNull(parse(listOf(OcrToken("08:01", 688f, 41f)), 1340, 800))
        assertNull(parse(listOf(OcrToken("40", 300f, 30f), OcrToken("12", 1100f, 30f)), 1340, 800))
    }
}
