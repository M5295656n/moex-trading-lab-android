package ru.moex.tradinglab.ui

/**
 * Pure, chronological indicator calculations.
 *
 * Input values must be ordered oldest-to-newest. A missing or non-finite value
 * breaks the current calculation window; it is never silently replaced with zero.
 * Results are aligned to the input and remain null until enough observations exist.
 */
internal object CandleIndicators {
    fun sma(values: List<Double?>, period: Int): List<Double?> {
        require(period > 0) { "period must be positive" }
        val result = MutableList<Double?>(values.size) { null }
        val window = ArrayDeque<Double>()
        var sum = 0.0

        values.forEachIndexed { index, raw ->
            val value = raw?.takeIf { it.isFinite() }
            if (value == null) {
                window.clear()
                sum = 0.0
                return@forEachIndexed
            }

            window.addLast(value)
            sum += value
            if (window.size > period) sum -= window.removeFirst()
            if (window.size == period) {
                val average = sum / period
                result[index] = average.takeIf { it.isFinite() }
            }
        }
        return result
    }

    fun ema(values: List<Double?>, period: Int): List<Double?> {
        require(period > 0) { "period must be positive" }
        val result = MutableList<Double?>(values.size) { null }
        val alpha = 2.0 / (period + 1.0)
        var seedSum = 0.0
        var seedCount = 0
        var previous: Double? = null

        values.forEachIndexed { index, raw ->
            val value = raw?.takeIf { it.isFinite() }
            if (value == null) {
                seedSum = 0.0
                seedCount = 0
                previous = null
                return@forEachIndexed
            }

            val prior = previous
            if (prior != null) {
                val next = alpha * value + (1.0 - alpha) * prior
                if (next.isFinite()) {
                    result[index] = next
                    previous = next
                } else {
                    seedSum = 0.0
                    seedCount = 0
                    previous = null
                }
            } else {
                seedSum += value
                seedCount += 1
                if (seedCount == period) {
                    val seed = seedSum / period
                    if (seed.isFinite()) {
                        result[index] = seed
                        previous = seed
                    }
                    seedSum = 0.0
                    seedCount = 0
                }
            }
        }
        return result
    }
}
