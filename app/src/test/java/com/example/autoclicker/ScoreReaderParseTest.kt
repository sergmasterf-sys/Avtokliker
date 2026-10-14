package com.example.autoclicker

import com.example.autoclicker.engine.MatchScore
import com.example.autoclicker.engine.OcrToken
import com.example.autoclicker.engine.ScoreReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Чистый JVM-тест разбора счёта (без ML Kit): проверяет геометрию панели на разных экранах,
 * независимое чтение сторон, фильтрацию по высоте и расстоянию между цифрами.
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
        assertEquals(MatchScore(39, 26, 40, 2 * 60 + 55), s)
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
    fun returnsPartialScoreWhenOnlyOneSideIsRecognized() {
        // Синяя сторона не распозналась, красная вернула 26
        val s = parse(listOf(OcrToken("26", 793f, 30f)), 1340, 800)
        assertEquals(MatchScore(null, 26, null), s)

        // Только синяя сторона вернула 15
        val s2 = parse(listOf(OcrToken("15", 548f, 30f)), 1340, 800)
        assertEquals(MatchScore(15, null, null), s2)
    }

    @Test
    fun filtersOutTokensWithAbnormalBoundingBoxHeight() {
        // Медианная высота цифр 25px. Токен мусора "1" высотой 8px (отличие > 40%) отбрасывается
        val s = parse(
            listOf(
                OcrToken("3", 540f, 30f, width = 14f, height = 25f),
                OcrToken("9", 556f, 30f, width = 14f, height = 24f),
                OcrToken("1", 520f, 30f, width = 4f, height = 8f), // артефакт/пылинка
                OcrToken("5", 793f, 30f, width = 14f, height = 25f)
            ), 1340, 800
        )
        assertEquals(MatchScore(39, 5, null), s)
    }

    @Test
    fun doesNotConcatenateFarApartTokensOnSameSide() {
        // Два токена на синей стороне стоят слишком далеко (> 32u) друг от друга: не склеиваем в 38!
        val s = parse(
            listOf(
                OcrToken("3", 490f, 30f),
                OcrToken("8", 580f, 30f)
            ), 1340, 800
        )
        // Выбирается один валидный токен, а не склеенное "38" из разных концов плашки
        assertEquals(3, s?.left)
    }

    @Test
    fun rejectsTimerAndWords() {
        // Таймер и надписи не должны распознаваться как счёт
        assertNull(ScoreReader.normalizeDigits("08:26"))
        assertNull(ScoreReader.normalizeDigits("Цель"))
        assertNull(ScoreReader.normalizeDigits("ПОДБОР"))
        assertEquals("40", ScoreReader.normalizeDigits("40"))
        assertEquals("7", ScoreReader.normalizeDigits("7"))
    }

    @Test
    fun noBannerMeansNull() {
        assertNull(parse(listOf(OcrToken("НАЧАТЬ", 136f, 714f), OcrToken("00:07", 120f, 690f)), 1340, 800))
        assertNull(parse(listOf(OcrToken("08:01", 688f, 41f)), 1340, 800))
        assertNull(parse(listOf(OcrToken("40", 300f, 30f), OcrToken("12", 1100f, 30f)), 1340, 800))
    }

    @Test
    fun parsesMatchTimerCorrectly() {
        assertEquals(8 * 60 + 26, ScoreReader.parseTimerSeconds(listOf(OcrToken("08:26", 670f, 45f))))
        assertEquals(15, ScoreReader.parseTimerSeconds(listOf(OcrToken("00:15", 670f, 45f))))
        assertEquals(8, ScoreReader.parseTimerSeconds(listOf(OcrToken("0:08", 670f, 45f))))
        assertNull(ScoreReader.parseTimerSeconds(listOf(OcrToken("Цель: 40", 670f, 15f))))
        assertNull(ScoreReader.parseTimerSeconds(listOf(OcrToken("38", 530f, 30f))))

        // Проверка включения таймера в MatchScore при наличии счёта
        val s = parse(
            listOf(
                OcrToken("25", 530f, 30f, height = 30f),
                OcrToken("22", 810f, 30f, height = 30f),
                OcrToken("40", 670f, 15f),
                OcrToken("00:18", 670f, 45f)
            ),
            1340, 800
        )
        assertEquals(25, s?.left)
        assertEquals(22, s?.right)
        assertEquals(40, s?.target)
        assertEquals(18, s?.timerSeconds)
    }
}
