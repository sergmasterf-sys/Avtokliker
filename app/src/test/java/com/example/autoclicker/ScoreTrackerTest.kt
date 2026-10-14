package com.example.autoclicker

import com.example.autoclicker.engine.MatchScore
import com.example.autoclicker.engine.ScoreTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        val s2 = tracker.update(MatchScore(null, 5, 40))
        assertEquals(MatchScore(3, 5, 40), s2)

        val s3 = tracker.update(MatchScore(7, 7, 40))
        assertEquals(MatchScore(7, 7, 40), s3)
    }

    @Test
    fun recoversLostDigitInScreenshotsScenario() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(6, 4, 40))

        val s = tracker.update(MatchScore(null, 5, 40))
        assertEquals(MatchScore(6, 5, 40), s)

        val s2 = tracker.update(MatchScore(7, 7, 40))
        assertEquals(MatchScore(7, 7, 40), s2)
    }

    @Test
    fun rejectsSingleFrameNegativeDrop() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(12, 10, 40))

        // OCR ошибочно прочитал только одну цифру «2» вместо «12» — один раз
        val s = tracker.update(MatchScore(2, 10, 40))
        assertEquals(MatchScore(12, 10, 40), s)

        // Следующее чтение снова нормальное: сомнение снято
        val s2 = tracker.update(MatchScore(12, 10, 40))
        assertEquals(MatchScore(12, 10, 40), s2)
        assertFalse(tracker.hasPendingConflict)
    }

    @Test
    fun rejectsSingleFrameGlitchJumps() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(5, 4, 40))

        // Одиночный артефакт OCR: скачок до 38
        val glitch = tracker.update(MatchScore(38, 5, 40))
        assertEquals(MatchScore(5, 5, 40), glitch)
        assertTrue(tracker.hasPendingConflict)

        // Нормальное следующее чтение: 7:6
        val normal = tracker.update(MatchScore(7, 6, 40))
        assertEquals(MatchScore(7, 6, 40), normal)
        assertFalse(tracker.hasPendingConflict)
    }

    @Test
    fun alternatingGlitchNeverResyncs() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(12, 10, 40))
        repeat(5) {
            tracker.update(MatchScore(2, 10, 40))
            val s = tracker.update(MatchScore(12, 10, 40))
            assertEquals(MatchScore(12, 10, 40), s)
        }
        assertEquals(0, tracker.resyncCount)
    }

    @Test
    fun resyncsWhenRightSideStuckAtOne() {
        // Сценарий со скриншотов: правая сторона застряла на 1, в игре уже 16-25
        val tracker = ScoreTracker()
        tracker.update(MatchScore(3, 1, 40))

        val r1 = tracker.update(MatchScore(4, 16, 40))
        assertEquals(MatchScore(4, 1, 40), r1)
        val r2 = tracker.update(MatchScore(5, 17, 40))
        assertEquals(MatchScore(5, 1, 40), r2)
        // Третье согласованное чтение подряд: принимаем
        val r3 = tracker.update(MatchScore(6, 18, 40))
        assertEquals(MatchScore(6, 18, 40), r3)
        assertEquals(1, tracker.resyncCount)
        assertNotNull(tracker.lastResyncInfo)
        assertFalse(tracker.hasPendingConflict)

        // Дальше обычный рост
        val r4 = tracker.update(MatchScore(7, 20, 40))
        assertEquals(MatchScore(7, 20, 40), r4)
    }

    @Test
    fun resyncsDownwardAfterFourConsistentReads() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(30, 10, 40))   // ошибочно высокое первое чтение, на деле было 3

        assertEquals(MatchScore(30, 10, 40), tracker.update(MatchScore(5, 10, 40)))
        assertEquals(MatchScore(30, 10, 40), tracker.update(MatchScore(5, 10, 40)))
        assertEquals(MatchScore(30, 10, 40), tracker.update(MatchScore(6, 10, 40)))
        // Четвёртое подряд: принимаем
        assertEquals(MatchScore(6, 10, 40), tracker.update(MatchScore(6, 10, 40)))
    }

    @Test
    fun detectsNewMatchWhenScoreResets() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(35, 38, 40))

        val s1 = tracker.update(MatchScore(1, 0, 40))
        assertEquals(MatchScore(35, 38, 40), s1)

        val s2 = tracker.update(MatchScore(1, 1, 40))
        assertEquals(MatchScore(1, 1, 40), s2)
    }

    @Test
    fun explicitResetClearsHistoryAndPendingConflict() {
        val tracker = ScoreTracker()
        tracker.update(MatchScore(35, 38, 40))
        tracker.update(MatchScore(2, 38, 40))
        tracker.reset()
        assertFalse(tracker.hasPendingConflict)

        val s = tracker.update(MatchScore(0, 0, 40))
        assertEquals(MatchScore(0, 0, 40), s)
    }
}
