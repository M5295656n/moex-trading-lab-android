package ru.moex.tradinglab.analysis

data class AnalysisCandle(
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val volume: Double?
)

enum class TrendDirection { INSUFFICIENT_DATA, BULLISH, BEARISH, SIDEWAYS }

data class TrendAssessment(
    val direction: TrendDirection,
    val fastEma: Double?,
    val slowEma: Double?,
    val slowEmaSlopePercent: Double?,
    val observations: Int
)

data class VolumeAssessment(
    val currentVolume: Double,
    val averagePreviousVolume: Double,
    val relativeVolume: Double,
    val aboveAverage: Boolean
)

/**
 * Deterministic, look-ahead-free market context calculations.
 * This class does not create entry/exit signals or trade recommendations.
 */
object MarketRegimeAnalyzer {
    fun assessTrend(
        candles: List<AnalysisCandle>,
        fastPeriod: Int = 20,
        slowPeriod: Int = 50,
        slopeLookback: Int = 5,
        minimumSlopePercent: Double = 0.15
    ): TrendAssessment {
        require(fastPeriod > 0) { "fastPeriod must be positive" }
        require(slowPeriod > fastPeriod) { "slowPeriod must be greater than fastPeriod" }
        require(slopeLookback > 0) { "slopeLookback must be positive" }
        require(minimumSlopePercent.isFinite() && minimumSlopePercent >= 0.0) {
            "minimumSlopePercent must be finite and non-negative"
        }

        val closes = candles.map { it.close?.takeIf(Double::isFinite) }
        val fast = ema(closes, fastPeriod)
        val slow = ema(closes, slowPeriod)
        val lastIndex = candles.lastIndex
        if (lastIndex < 0) return insufficient()

        val fastNow = fast[lastIndex] ?: return insufficient()
        val slowNow = slow[lastIndex] ?: return insufficient()
        val slopeIndex = lastIndex - slopeLookback
        if (slopeIndex < 0) return insufficient()
        val slowThen = slow[slopeIndex] ?: return insufficient()
        if (slowThen == 0.0) return insufficient()

        val slope = ((slowNow - slowThen) / kotlin.math.abs(slowThen)) * 100.0
        if (!slope.isFinite()) return insufficient()

        val direction = when {
            fastNow > slowNow && slope >= minimumSlopePercent -> TrendDirection.BULLISH
            fastNow < slowNow && slope <= -minimumSlopePercent -> TrendDirection.BEARISH
            else -> TrendDirection.SIDEWAYS
        }
        val observations = closes.asReversed().takeWhile { it != null }.size
        return TrendAssessment(direction, fastNow, slowNow, slope, observations)
    }

    fun assessVolume(
        candles: List<AnalysisCandle>,
        baselinePeriod: Int = 20
    ): VolumeAssessment? {
        require(baselinePeriod > 0) { "baselinePeriod must be positive" }
        if (candles.size < baselinePeriod + 1) return null

        val current = candles.last().volume?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val baseline = candles
            .drop(candles.size - baselinePeriod - 1)
            .dropLast(1)
            .map { it.volume?.takeIf { value -> value.isFinite() && value >= 0.0 } ?: return null }

        val average = baseline.average()
        if (!average.isFinite() || average <= 0.0) return null
        val relative = current / average
        if (!relative.isFinite()) return null

        return VolumeAssessment(current, average, relative, current > average)
    }

    private fun ema(values: List<Double?>, period: Int): List<Double?> {
        val result = MutableList<Double?>(values.size) { null }
        val alpha = 2.0 / (period + 1.0)
        var seedSum = 0.0
        var seedCount = 0
        var previous: Double? = null

        values.forEachIndexed { index, raw ->
            val value = raw?.takeIf(Double::isFinite)
            if (value == null) {
                seedSum = 0.0
                seedCount = 0
                previous = null
                return@forEachIndexed
            }

            val prior = previous
            if (prior != null) {
                val next = prior + alpha * (value - prior)
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
                seedCount++
                if (seedCount == period) {
                    val initial = seedSum / period
                    if (initial.isFinite()) {
                        result[index] = initial
                        previous = initial
                    } else {
                        seedSum = 0.0
                        seedCount = 0
                    }
                }
            }
        }
        return result
    }

    private fun insufficient() = TrendAssessment(
        TrendDirection.INSUFFICIENT_DATA, null, null, null, 0
    )
}
