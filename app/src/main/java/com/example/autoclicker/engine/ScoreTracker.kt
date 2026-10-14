package com.example.autoclicker.engine

/**
 * Временной фильтр счёта (ScoreTracker) для режима TDM (Team Deathmatch) PUBG Mobile:
 *
 * 1. Монотонность: в TDM счёт команды не может уменьшаться (только расти от 0 до 40).
 * 2. Устойчивость к потере одной из цифр: если одна сторона прочитана как null (например, «?:5»),
 *    восстанавливает последнее известное значение для этой стороны («3:5» или «6:5»).
 * 3. Подавление выбросов (glitch rejection): если при текущем счёте 5 приходит одиночный скачок
 *    (например, 38 или 80), он отбрасывается как артефакт OCR.
 * 4. Детекция нового матча: если счёт существенно падает (например, 0:0, 1:1) два кадра подряд
 *    или при явном вызове [reset], трекер сбрасывает историю.
 *
 * Чистый Kotlin класс без Android зависимостей — полностью тестируем юнит-тестами.
 */
class ScoreTracker(
    val defaultTarget: Int = 40,
    val maxPlausibleJump: Int = 6,
    val newMatchConfirmReads: Int = 2
) {
    var lastLeft: Int? = null
        private set
    var lastRight: Int? = null
        private set
    var target: Int = defaultTarget
        private set

    private var lowScoreCandidateCount = 0

    fun reset() {
        lastLeft = null
        lastRight = null
        lowScoreCandidateCount = 0
    }

    /**
     * Обрабатывает «сырой» [raw] счёт от [ScoreReader].
     * Возвращает стабилизированный [MatchScore], либо null если счёт ещё ни разу не удалось определить.
     */
    fun update(raw: MatchScore?): MatchScore? {
        if (raw == null) {
            return if (lastLeft != null || lastRight != null) {
                MatchScore(lastLeft, lastRight, target)
            } else null
        }

        if (raw.target != null && raw.target in 10..200) {
            target = raw.target
        }

        val curL = lastLeft
        val curR = lastRight
        val curMax = maxOf(curL ?: -1, curR ?: -1)

        val rawMax = raw.max

        // Проверка нового матча: счёт упал до малых значений (<= 5) при прошлом >= 10
        if (curMax >= 10 && rawMax in 0..5 && (raw.left != null || raw.right != null)) {
            lowScoreCandidateCount++
            if (lowScoreCandidateCount >= newMatchConfirmReads) {
                lastLeft = raw.left
                lastRight = raw.right
                lowScoreCandidateCount = 0
                return MatchScore(lastLeft, lastRight, target)
            }
            // Ждём подтверждения нового матча на следующем кадре, пока отдаём текущий
            return MatchScore(lastLeft, lastRight, target)
        } else {
            lowScoreCandidateCount = 0
        }

        val nextL = filterSide(raw.left, curL)
        val nextR = filterSide(raw.right, curR)

        lastLeft = nextL
        lastRight = nextR

        return if (lastLeft != null || lastRight != null) {
            MatchScore(lastLeft, lastRight, target, raw.timerSeconds)
        } else null
    }

    private fun filterSide(rawSide: Int?, currentSide: Int?): Int? {
        if (rawSide == null) {
            // Восстановление потерянной цифры
            return currentSide
        }

        if (currentSide == null) {
            // Первое чтение этой стороны: правдоподобно ли число для матча до target?
            return if (rawSide in 0..target + 5) rawSide else null
        }

        // В TDM счёт не может уменьшаться
        if (rawSide < currentSide) {
            // Ложное уменьшение из-за OCR артефакта
            return currentSide
        }

        // Проверка на резкий скачок (glitch rejection)
        val jump = rawSide - currentSide
        if (jump > maxPlausibleJump) {
            // Например, с 5 до 38 за один шаг
            return currentSide
        }

        return rawSide
    }
}
