package com.example.autoclicker.engine

/**
 * Временной фильтр счёта (ScoreTracker) для режима TDM (Team Deathmatch) PUBG Mobile:
 *
 * 1. Монотонность: в TDM счёт команды не может уменьшаться (только расти от 0 до 40).
 * 2. Устойчивость к потере одной из цифр: если одна сторона прочитана как null (например, «?:5»),
 *    восстанавливает последнее известное значение для этой стороны.
 * 3. Подавление выбросов (glitch rejection): одиночный скачок (например, 5 -> 38) или одиночное
 *    уменьшение отбрасывается как артефакт OCR.
 * 4. САМОСИНХРОНИЗАЦИЯ (новое): если «странное» значение (скачок больше [maxPlausibleJump] или уменьшение)
 *    читается несколько кадров подряд (значения идут по цепочке без уменьшения), оно принимается как
 *    настоящее. Так трекер не может «залипнуть» навсегда на неверном значении (например 25:1 при
 *    реальном 26:25) и больше не требует перетягивания ползунка.
 * 5. Детекция нового матча: если счёт существенно падает (например, 0:0, 1:1) два кадра подряд
 *    или при явном вызове [reset], трекер сбрасывает историю.
 *
 * Чистый Kotlin класс без Android зависимостей — полностью тестируем юнит-тестами.
 */
class ScoreTracker(
    val defaultTarget: Int = 40,
    val maxPlausibleJump: Int = 6,
    val newMatchConfirmReads: Int = 2,
    /** Сколько подряд согласованных чтений нужно, чтобы принять большой скачок ВВЕРХ. */
    val resyncUpReads: Int = 3,
    /** Сколько подряд согласованных чтений нужно, чтобы принять УМЕНЬШЕНИЕ значения. */
    val resyncDownReads: Int = 4
) {
    var lastLeft: Int? = null
        private set
    var lastRight: Int? = null
        private set
    var target: Int = defaultTarget
        private set

    /** Сколько раз трекер сам исправил «залипшее» значение (для логов). */
    var resyncCount: Int = 0
        private set

    /** Текст последней пересинхронизации, например «правая 1 -> 18». null, если не было. */
    var lastResyncInfo: String? = null
        private set

    private class SideState {
        var candidate: Int? = null
        var reads: Int = 0
        fun clear() {
            candidate = null
            reads = 0
        }
    }

    private val leftState = SideState()
    private val rightState = SideState()

    private var lowScoreCandidateCount = 0

    /**
     * true, если прямо сейчас есть расхождение: прочитанное значение не совпадает с хранимым
     * и ждёт подтверждения. Пока true, вызывающий код может читать счёт чаще.
     */
    val hasPendingConflict: Boolean
        get() = leftState.reads > 0 || rightState.reads > 0

    fun reset() {
        lastLeft = null
        lastRight = null
        lowScoreCandidateCount = 0
        leftState.clear()
        rightState.clear()
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
                leftState.clear()
                rightState.clear()
                return MatchScore(lastLeft, lastRight, target)
            }
            // Ждём подтверждения нового матча на следующем кадре, пока отдаём текущий
            return MatchScore(lastLeft, lastRight, target)
        } else {
            lowScoreCandidateCount = 0
        }

        val nextL = filterSide(raw.left, curL, leftState, "левая")
        val nextR = filterSide(raw.right, curR, rightState, "правая")

        lastLeft = nextL
        lastRight = nextR

        return if (lastLeft != null || lastRight != null) {
            MatchScore(lastLeft, lastRight, target, raw.timerSeconds)
        } else null
    }

    private fun filterSide(rawSide: Int?, currentSide: Int?, st: SideState, name: String): Int? {
        if (rawSide == null) {
            // Восстановление потерянной цифры; цепочку кандидата не трогаем
            return currentSide
        }

        if (currentSide == null) {
            // Первое чтение этой стороны: правдоподобно ли число для матча до target?
            st.clear()
            return if (rawSide in 0..target + 5) rawSide else null
        }

        // Совпало с хранимым значением: всё в порядке, сомнений нет
        if (rawSide == currentSide) {
            st.clear()
            return currentSide
        }

        // Нормальный рост (не больше maxPlausibleJump за раз)
        if (rawSide > currentSide && rawSide - currentSide <= maxPlausibleJump) {
            st.clear()
            return rawSide
        }

        // Уменьшение или большой скачок вверх: подозрительно. Принимаем только если
        // одно и то же (или растущее по цепочке) значение читается несколько кадров подряд.
        val needReads = if (rawSide < currentSide) resyncDownReads else resyncUpReads
        val prev = st.candidate
        if (prev != null && rawSide >= prev && rawSide - prev <= maxPlausibleJump) {
            st.reads++
        } else {
            st.reads = 1
        }
        st.candidate = rawSide

        if (st.reads >= needReads && rawSide in 0..target + 5) {
            resyncCount++
            lastResyncInfo = "$name $currentSide -> $rawSide"
            st.clear()
            return rawSide
        }
        return currentSide
    }
}
