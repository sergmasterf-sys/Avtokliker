package com.example.autoclicker

import com.example.autoclicker.engine.ArmReason
import com.example.autoclicker.engine.EndgameGate
import com.example.autoclicker.engine.EndgameMode
import com.example.autoclicker.engine.MatchScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Юнит-тесты для EndgameGate (без Android).
 */
class EndgameGateTest {

    private lateinit var gate: EndgameGate
    private val t0 = 100_000L

    @Before
    fun setUp() {
        gate = EndgameGate()
        gate.startMatch(t0, 40)
    }

    @Test
    fun testNormalMatchProgressionToHard() {
        // Обычный матч: счёт растёт 0 -> 35 -> 38 -> HARD
        assertEquals(EndgameMode.OFF, gate.mode)

        gate.onScore(MatchScore(0, 0, 40), t0 + 1_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        gate.onScore(MatchScore(35, 30, 40), t0 + 2_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // Первое чтение высокого счёта (38 >= 40 - 3 = 37): требуется 2 чтения для подтверждения
        gate.onScore(MatchScore(38, 30, 40), t0 + 3_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // Второе чтение высокого счёта: переходим в HARD
        gate.onScore(MatchScore(38, 32, 40), t0 + 4_000L)
        assertEquals(EndgameMode.HARD, gate.mode)
        assertEquals(ArmReason.HIGH_SCORE, gate.armReason)
        assertTrue(gate.isHard)
    }

    @Test
    fun testOcrGlitchAtEarlyScoreDoesNotArm() {
        // Сбой OCR на 7:7 (то null, то одна цифра, потом 7:7, панель видна):
        // подтверждаем, что режим конца матча не включается
        gate.onScore(MatchScore(7, 7, 40), t0 + 1_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // Пустой кадр (null)
        gate.onEmptyRead(t0 + 2_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // Неполный счёт (одна цифра ?:5)
        gate.onScore(MatchScore(null, 5, 40), t0 + 3_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // Серия пустых чтений: счёт 7 < 28 (цель - 12), поэтому условие пропажи панели не выполняется
        gate.onEmptyRead(t0 + 4_000L)
        gate.onEmptyRead(t0 + 5_000L)
        gate.onEmptyRead(t0 + 6_000L)
        gate.onEmptyRead(t0 + 7_000L)
        gate.onEmptyRead(t0 + 15_000L) // прошло > 8 секунд, но счёт низкий
        assertEquals(EndgameMode.OFF, gate.mode)

        // Панель снова прочитана: 7:7
        gate.onScore(MatchScore(7, 7, 40), t0 + 16_000L)
        assertEquals(EndgameMode.OFF, gate.mode)
        assertFalse(gate.isArmed)
    }

    @Test
    fun testBannerGoneAtEndArmsSoft() {
        // Пропажа панели в конце матча при счёте 30:28 -> включается SOFT
        gate.onScore(MatchScore(30, 28, 40), t0 + 60_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // 3 пустых чтения: ещё недостаточно (нужно >= 4)
        gate.onEmptyRead(t0 + 62_000L)
        gate.onEmptyRead(t0 + 64_000L)
        gate.onEmptyRead(t0 + 66_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // 4 пустых чтения, но прошло только 7 секунд (нужно >= 8)
        gate.onEmptyRead(t0 + 67_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // 5 пустых чтений и прошло 9 секунд с последнего успешного чтения (30 >= 40 - 12)
        gate.onEmptyRead(t0 + 69_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)
        assertEquals(ArmReason.BANNER_GONE, gate.armReason)
        assertTrue(gate.isSoft)
    }

    @Test
    fun testFalsePositiveDisarmsSoftBackToOff() {
        // Ложное срабатывание (пропала панель, включился SOFT, потом снова прочитан счёт 12:10) ->
        // выключается в OFF (disarm)
        gate.onScore(MatchScore(30, 28, 40), t0 + 60_000L)
        gate.onEmptyRead(t0 + 62_000L)
        gate.onEmptyRead(t0 + 64_000L)
        gate.onEmptyRead(t0 + 66_000L)
        gate.onEmptyRead(t0 + 67_000L)
        gate.onEmptyRead(t0 + 69_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)

        // Первое чтение счета 12:10 (< 35): нужно 2 чтения подряд для отмены
        gate.onScore(MatchScore(12, 10, 40), t0 + 72_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)

        // Второе чтение счета 12:10: режим SOFT сбрасывается в OFF
        gate.onScore(MatchScore(12, 10, 40), t0 + 73_000L)
        assertEquals(EndgameMode.OFF, gate.mode)
        assertFalse(gate.isArmed)
        assertNotNull(gate.lastDisarmReason)
    }

    @Test
    fun testLongMatchWithoutScoreArmsSoftByTimeout() {
        // Долгий матч без счёта -> SOFT по таймауту (> 4 минут)
        gate.onTimeTick(t0 + 3 * 60_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        gate.onEmptyRead(t0 + 4 * 60_000L + 1_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)
        assertEquals(ArmReason.NO_SCORE_TIMEOUT, gate.armReason)
    }

    @Test
    fun testHardArmsOnMatchMaxTimeout() {
        // Матч длится дольше MATCH_MAX_MS (12 минут) -> безусловный HARD
        gate.onTimeTick(t0 + 12 * 60_000L + 1_000L)
        assertEquals(EndgameMode.HARD, gate.mode)
        assertEquals(ArmReason.MATCH_TIMEOUT, gate.armReason)
    }

    @Test
    fun testHardResetsWhenScoreDropsOnNewMatch() {
        // HARD был включён по реальному высокому счёту
        gate.onScore(MatchScore(38, 36, 40), t0 + 10_000L)
        gate.onScore(MatchScore(39, 36, 40), t0 + 11_000L)
        assertEquals(EndgameMode.HARD, gate.mode)
        assertEquals(ArmReason.HIGH_SCORE, gate.armReason)

        // Колебание в районе 34 не сбрасывает HARD
        gate.onScore(MatchScore(34, 30, 40), t0 + 12_000L)
        gate.onScore(MatchScore(34, 30, 40), t0 + 13_000L)
        assertEquals(EndgameMode.HARD, gate.mode)

        // Новый матч: малые значения (<= 15) два раза подряд
        gate.onScore(MatchScore(2, 1, 40), t0 + 14_000L)
        assertEquals(EndgameMode.HARD, gate.mode) // только 1 чтение

        gate.onScore(MatchScore(3, 1, 40), t0 + 15_000L)
        assertEquals(EndgameMode.OFF, gate.mode) // сброс подтверждён
        assertNotNull(gate.lastDisarmReason)
    }

    @Test
    fun testOcrUnavailableArmsSoftAndDisarmsOnRecovery() {
        // OCR стал недоступен -> SOFT
        gate.onOcrUnavailable(t0 + 5_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)
        assertEquals(ArmReason.OCR_UNAVAILABLE, gate.armReason)

        // OCR восстановился, читает низкий счёт 10:10 два раза подряд -> возврат в OFF
        gate.onScore(MatchScore(10, 10, 40), t0 + 40_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)
        gate.onScore(MatchScore(11, 10, 40), t0 + 41_000L)
        assertEquals(EndgameMode.OFF, gate.mode)
    }

    @Test
    fun testTimerExpiringArmsSoft() {
        // Таймер > 20 сек не включает SOFT
        gate.onTimer(45, t0 + 10_000L)
        assertEquals(EndgameMode.OFF, gate.mode)

        // Таймер <= 20 сек включает SOFT режим
        gate.onTimer(18, t0 + 20_000L)
        assertEquals(EndgameMode.SOFT, gate.mode)
        assertEquals(ArmReason.TIMER_EXPIRING, gate.armReason)
        assertEquals("таймер матча <= 20 сек", gate.getArmReasonText(t0 + 20_000L))

        // Восстановление/новый матч с низким счётом сбрасывает в OFF
        gate.onScore(MatchScore(5, 5, 40), t0 + 21_000L)
        gate.onScore(MatchScore(6, 5, 40), t0 + 22_000L)
        assertEquals(EndgameMode.OFF, gate.mode)
    }
}
