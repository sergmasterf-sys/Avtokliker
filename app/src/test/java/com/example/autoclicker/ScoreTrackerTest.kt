package com.example.autoclicker

import com.example.autoclicker.engine.MatchScore
import com.example.autoclicker.engine.ScoreTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScoreTrackerTest {

    @Test
    fun tracksNormalScoreProgression() {
        val tracker = ScoreTracker()
        assertNull(tracker.update(null))

        val s1 = tracker.update(MatchScore(3, 4, 40))
        assertEquals(MatchScore(3, 4, 40), s1)

        val s2 = tracker.update(MatchScore(5, 4, 40))
        assertEquals(MatchScore(5, 4, 40), s2)

        val s3 = tracker.update(MatchScore(7, 7, 40))
        assertEquals(MatchScore(7, 7, 40), s3)
    }

    @Test
    fun recoversLostDigitWhenOneSideIsNull() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(3, 4, 40))

        // Следующий кадр: левая цифра потерялась (null), правая прочиталась как 5
        val s2 = tracker.update(MatchScore(null, 5, 40))
        // Левая должна восстановиться из прошлого состояния (3), правая обновиться до 5
        assertEquals(MatchScore(3, 5, 40), s2)

        // Следующий кадр: скриншот 7:7
        val s3 = tracker.update(MatchScore(7, 7, 40))
        assertEquals(MatchScore(7, 7, 40), s3)
    }

    @Test
    fun recoversLostDigitInScreenshotsScenario() {
        val tracker = ScoreTracker()
        // Матч шёл, счёт 6:4
        tracker.update(MatchScore(6, 4, 40))

        // Кадр со скриншота: счёт 6:5, но левая не распозналась -> null:5
        val s = tracker.update(MatchScore(null, 5, 40))
        assertEquals(MatchScore(6, 5, 40), s)

        // Далее прочитался 7:7
        val s2 = tracker.update(MatchScore(7, 7, 40))
        assertEquals(MatchScore(7, 7, 40), s2)
    }

    @Test
    fun rejectsNegativeScoreDrops() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(12, 10, 40))

        // OCR ошибочно прочитал только одну цифру «2» вместо «12»
        val s = tracker.update(MatchScore(2, 10, 40))
        assertEquals(MatchScore(12, 10, 40), s)
    }

    @Test
    fun rejectsSingleFrameGlitchJumps() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(5, 4, 40))

        // Одиночный артефакт OCR: скачок до 38
        val glitch = tracker.update(MatchScore(38, 5, 40))
        assertEquals(MatchScore(5, 5, 40), glitch)

        // Нормальное следующее чтение: 7:6
        val normal = tracker.update(MatchScore(7, 6, 40))
        assertEquals(MatchScore(7, 6, 40), normal)
    }

    @Test
    fun detectsNewMatchWhenScoreResets() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(35, 38, 40))

        // Кадр 1 нового матча: 1:0
        val s1 = tracker.update(MatchScore(1, 0, 40))
        // Первый кадр ожидает подтверждения
        assertEquals(MatchScore(35, 38, 40), s1)

        // Кадр 2 нового матча: 1:1 -> подтверждение сброса
        val s2 = tracker.update(MatchScore(1, 1, 40))
        assertEquals(MatchScore(1, 1, 40), s2)
    }

    @Test
    fun explicitResetClearsHistory() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(35, 38, 40))
        tracker.reset()

        val s = tracker.update(MatchScore(0, 0, 40))
        assertEquals(MatchScore(0, 0, 40), s)
    }
}
