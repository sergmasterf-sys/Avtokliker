package com.example.autoclicker.engine

/**
 * Уровень готовности к окончанию матча PUBG Mobile:
 * - OFF: матч в разгаре, кнопку «Продолжить» не ищем.
 * - SOFT (мягкий): подозрение на окончание (пропала панель счёта, таймаут или временный сбой OCR) —
 *   искать «Продолжить» ТОЛЬКО по сохранённым и seed-отпечаткам, БЕЗ калибровки по цвету (чтобы не спутать с кнопкой «присесть»).
 * - HARD (жёсткий): подтверждённое окончание (реальный высокий счёт два раза подряд или предельный таймаут) —
 *   полный поиск «Продолжить» (отпечатки + калибровка по цвету).
 */
enum class EndgameMode {
    OFF,
    SOFT,
    HARD
}

/**
 * Причина активации режима окончания матча.
 */
enum class ArmReason {
    HIGH_SCORE,        // Реальный высокий счёт (>= target - ARM_MARGIN x2) -> HARD
    MATCH_TIMEOUT,     // Матч идёт дольше MATCH_MAX_MS -> HARD
    BANNER_GONE,       // Панель счёта пропала при счёте >= target - 12 -> SOFT
    NO_SCORE_TIMEOUT,  // Счёт ни разу не прочитался за NO_SCORE_FALLBACK_MS -> SOFT
    OCR_UNAVAILABLE,   // OCR временно отключён из-за ошибок -> SOFT
    TIMER_EXPIRING     // Таймер матча <= 20 сек -> SOFT
}

/**
 * Логика «ворот конца матча»: устойчива к сбоям OCR и полностью обратима.
 * Чистый класс без Android-зависимостей, время передаётся параметром now.
 */
class EndgameGate {

    companion object {
        const val DEFAULT_TARGET = 40
        const val ARM_MARGIN = 3                     // Порог HARD: target - 3 (37 при цели 40)
        const val ARM_CONFIRM_READS = 2              // Высокий счёт должен подтвердиться 2 раза подряд
        const val BANNER_GONE_MIN_READS = 4          // (а) не менее 4 пустых чтений подряд
        const val BANNER_GONE_MIN_TIME_MS = 8_000L   // (а) не менее 8 секунд с последнего успешного чтения
        const val BANNER_GONE_SCORE_MARGIN = 12      // (б) последний известный счёт >= target - 12 (28 при цели 40)
        const val NO_SCORE_FALLBACK_MS = 4 * 60_000L // (б) либо счёт не читался > 4 минут
        const val MATCH_MAX_MS = 12 * 60_000L        // Предельная длительность матча (включает HARD)
        const val DISARM_MARGIN = 2                  // Порог отмены: max < target - ARM_MARGIN - 2 (35 при цели 40)
        const val DISARM_CONFIRM_READS = 2           // Отмена подтверждается 2 чтениями подряд
        const val RESET_SCORE_THRESHOLD = 15         // Малые значения после большого (новый матч)
    }

    var mode: EndgameMode = EndgameMode.OFF
        private set

    var armReason: ArmReason? = null
        private set

    var lastDisarmReason: String? = null
        private set

    var matchStartMs: Long = 0L
        private set

    var lastScore: MatchScore? = null
        private set

    var lastScoreTimeMs: Long = 0L
        private set

    var lastKnownTarget: Int = DEFAULT_TARGET
        private set

    var lastKnownMaxScore: Int? = null
        private set

    var scoreSeenInMatch: Boolean = false
        private set

    var emptyReadsCount: Int = 0
        private set

    var armHighReadsCount: Int = 0
        private set

    var lowScoreReadsCount: Int = 0
        private set

    val isArmed: Boolean get() = mode != EndgameMode.OFF
    val isSoft: Boolean get() = mode == EndgameMode.SOFT
    val isHard: Boolean get() = mode == EndgameMode.HARD

    /**
     * Сброс состояния при старте нового матча.
     */
    fun startMatch(now: Long, target: Int = DEFAULT_TARGET) {
        matchStartMs = now
        mode = EndgameMode.OFF
        armReason = null
        lastDisarmReason = null
        lastScore = null
        lastScoreTimeMs = 0L
        lastKnownTarget = target
        lastKnownMaxScore = null
        scoreSeenInMatch = false
        emptyReadsCount = 0
        armHighReadsCount = 0
        lowScoreReadsCount = 0
    }

    /**
     * Проверка таймаутов матча по времени.
     */
    fun onTimeTick(now: Long) {
        if (mode == EndgameMode.HARD) return

        // 1. Предельный таймаут матча (12 минут) -> безусловный HARD
        if (now - matchStartMs > MATCH_MAX_MS) {
            mode = EndgameMode.HARD
            armReason = ArmReason.MATCH_TIMEOUT
            return
        }

        // 2. Счёт ни разу не прочитался за 4 минуты -> SOFT
        if (mode == EndgameMode.OFF && !scoreSeenInMatch && (now - matchStartMs > NO_SCORE_FALLBACK_MS)) {
            mode = EndgameMode.SOFT
            armReason = ArmReason.NO_SCORE_TIMEOUT
            return
        }
    }

    /**
     * OCR недоступен (серия ошибок или отключён).
     */
    fun onOcrUnavailable(now: Long) {
        if (mode == EndgameMode.HARD) return
        if (mode == EndgameMode.OFF) {
            mode = EndgameMode.SOFT
            armReason = ArmReason.OCR_UNAVAILABLE
        }
    }

    /**
     * Чтение таймера матча (формат mm:ss под «Цель»).
     * Если осталось не больше 20 секунд, включается SOFT режим.
     */
    fun onTimer(remainingSec: Int, now: Long) {
        if (mode == EndgameMode.HARD) return
        if (remainingSec in 0..20) {
            if (mode == EndgameMode.OFF) {
                mode = EndgameMode.SOFT
                armReason = ArmReason.TIMER_EXPIRING
            }
        }
    }

    /**
     * Пустое чтение (панель счёта не обнаружена, parseScoreTokens вернул null).
     */
    fun onEmptyRead(now: Long) {
        emptyReadsCount++
        armHighReadsCount = 0
        lowScoreReadsCount = 0

        // Проверяем временные ограничения
        onTimeTick(now)
        if (mode == EndgameMode.HARD) return

        // Требование 2: «Панель счёта пропала» больше не включает HARD. Максимум SOFT, и только если одновременно:
        // (а) подряд не меньше 4 пустых чтений И прошло не меньше 8 секунд с последнего успешного чтения;
        // (б) последний известный счёт был >= (цель - 12) ИЛИ счёт ни разу не читался за матч дольше NO_SCORE_FALLBACK_MS.
        if (mode == EndgameMode.OFF) {
            val timeSinceLastSuccess = if (lastScoreTimeMs > 0L) now - lastScoreTimeMs else (now - matchStartMs)
            val condA = emptyReadsCount >= BANNER_GONE_MIN_READS && timeSinceLastSuccess >= BANNER_GONE_MIN_TIME_MS

            val condB = (lastKnownMaxScore != null && lastKnownMaxScore!! >= (lastKnownTarget - BANNER_GONE_SCORE_MARGIN)) ||
                (!scoreSeenInMatch && (now - matchStartMs > NO_SCORE_FALLBACK_MS))

            if (condA && condB) {
                mode = EndgameMode.SOFT
                armReason = if (scoreSeenInMatch) ArmReason.BANNER_GONE else ArmReason.NO_SCORE_TIMEOUT
            }
        }
    }

    /**
     * Успешное чтение счёта.
     */
    fun onScore(score: MatchScore, now: Long) {
        lastScore = score
        scoreSeenInMatch = true
        lastScoreTimeMs = now
        emptyReadsCount = 0

        score.target?.let { lastKnownTarget = it }
        val maxScore = score.max
        if (maxScore >= 0) {
            lastKnownMaxScore = maxScore
        }

        val need = lastKnownTarget - ARM_MARGIN // 37 при цели 40
        val lowThreshold = lastKnownTarget - ARM_MARGIN - DISARM_MARGIN // 35 при цели 40

        // Проверка высокого счёта для HARD:
        if (maxScore >= need) {
            armHighReadsCount++
            lowScoreReadsCount = 0
            if (armHighReadsCount >= ARM_CONFIRM_READS) {
                mode = EndgameMode.HARD
                armReason = ArmReason.HIGH_SCORE
            }
        } else {
            armHighReadsCount = 0

            // Требование 3: Обратимость.
            // Если счёт успешно прочитан и max < цель - ARM_MARGIN - 2 два раза подряд,
            // а причина включения была НЕ «реальный высокий счёт», режим выключается (disarm).
            // HARD, включённый по реальному высокому счёту, сбрасывается только если счёт упал (новый матч: малые значения <= 15).
            if (mode != EndgameMode.OFF) {
                if (armReason != ArmReason.HIGH_SCORE) {
                    if (maxScore in 0 until lowThreshold) {
                        lowScoreReadsCount++
                        if (lowScoreReadsCount >= DISARM_CONFIRM_READS) {
                            mode = EndgameMode.OFF
                            lastDisarmReason = "прочитан счёт $score (порог < $lowThreshold)"
                            armReason = null
                            lowScoreReadsCount = 0
                        }
                    } else {
                        lowScoreReadsCount = 0
                    }
                } else {
                    if (maxScore in 0..RESET_SCORE_THRESHOLD) {
                        lowScoreReadsCount++
                        if (lowScoreReadsCount >= DISARM_CONFIRM_READS) {
                            mode = EndgameMode.OFF
                            lastDisarmReason = "счёт упал до $score (новый матч)"
                            armReason = null
                            lowScoreReadsCount = 0
                        }
                    } else {
                        lowScoreReadsCount = 0
                    }
                }
            } else {
                lowScoreReadsCount = 0
            }
        }

        onTimeTick(now)
    }

    /**
     * Человекочитаемое описание причины включения для журнала.
     */
    fun getArmReasonText(now: Long = System.currentTimeMillis()): String {
        return when (armReason) {
            ArmReason.HIGH_SCORE -> "счёт $lastScore, порог ${lastKnownTarget - ARM_MARGIN}"
            ArmReason.MATCH_TIMEOUT -> "матч идёт дольше ${MATCH_MAX_MS / 60_000} мин"
            ArmReason.BANNER_GONE -> "панель счёта пропала, последний счёт $lastScore"
            ArmReason.NO_SCORE_TIMEOUT -> "панель счёта не видна уже ${(now - matchStartMs) / 1000}с"
            ArmReason.OCR_UNAVAILABLE -> "OCR недоступен"
            ArmReason.TIMER_EXPIRING -> "таймер матча <= 20 сек"
            null -> "не включён"
        }
    }
}
