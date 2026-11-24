package ru.moex.tradinglab.ui

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure, chronological indicator calculations.
 *
 * Inputs are ordered oldest-to-newest. Missing/non-finite observations break
 * the calculation segment; they are never silently replaced with zero.
 * Outputs align with inputs and remain null until a complete warm-up exists.
 */
internal data class MacdValues(
    val line: List<Double?>,
    val signal: List<Double?>,
    val histogram: List<Double?>
)

internal data class BollingerValues(
    val middle: List<Double?>,
    val upper: List<Double?>,
    val lower: List<Double?>
)

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

    /** Wilder RSI. The first value appears after [period] price changes. */
    fun rsi(closes: List<Double?>, period: Int = 14): List<Double?> {
        require(period > 0) { "period must be positive" }
        val result = MutableList<Double?>(closes.size) { null }
        var previousClose: Double? = null
        var gainSum = 0.0
        var lossSum = 0.0
        var seedCount = 0
        var averageGain: Double? = null
        var averageLoss: Double? = null

        closes.forEachIndexed { index, raw ->
            val close = raw?.takeIf { it.isFinite() }
            if (close == null) {
                previousClose = null
                gainSum = 0.0
                lossSum = 0.0
                seedCount = 0
                averageGain = null
                averageLoss = null
                return@forEachIndexed
            }

            val previous = previousClose
            previousClose = close
            if (previous == null) return@forEachIndexed

            val change = close - previous
            if (!change.isFinite()) {
                previousClose = null
                gainSum = 0.0
                lossSum = 0.0
                seedCount = 0
                averageGain = null
                averageLoss = null
                return@forEachIndexed
            }
            val gain = max(change, 0.0)
            val loss = max(-change, 0.0)

            val oldGain = averageGain
            val oldLoss = averageLoss
            if (oldGain == null || oldLoss == null) {
                gainSum += gain
                lossSum += loss
                seedCount++
                if (seedCount == period) {
                    averageGain = gainSum / period
                    averageLoss = lossSum / period
                    result[index] = rsiValue(averageGain!!, averageLoss!!)
                }
            } else {
                averageGain = (oldGain * (period - 1) + gain) / period
                averageLoss = (oldLoss * (period - 1) + loss) / period
                result[index] = rsiValue(averageGain!!, averageLoss!!)
            }
        }
        return result
    }

    /** MACD using EMA(12), EMA(26), then EMA(9) of the MACD line by default. */
    fun macd(
        closes: List<Double?>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9
    ): MacdValues {
        require(fastPeriod > 0 && slowPeriod > fastPeriod && signalPeriod > 0) {
            "MACD periods must satisfy 0 < fast < slow and signal > 0"
        }
        val fast = ema(closes, fastPeriod)
        val slow = ema(closes, slowPeriod)
        val line = fast.indices.map { index ->
            val fastValue = fast[index]
            val slowValue = slow[index]
            if (fastValue == null || slowValue == null) null
            else (fastValue - slowValue).takeIf(Double::isFinite)
        }
        val signal = ema(line, signalPeriod)
        val histogram = line.indices.map { index ->
            val lineValue = line[index]
            val signalValue = signal[index]
            if (lineValue == null || signalValue == null) null
            else (lineValue - signalValue).takeIf(Double::isFinite)
        }
        return MacdValues(line, signal, histogram)
    }

    /** Bollinger Bands use population standard deviation over the rolling window. */
    fun bollingerBands(
        closes: List<Double?>,
        period: Int = 20,
        standardDeviations: Double = 2.0
    ): BollingerValues {
        require(period > 0) { "period must be positive" }
        require(standardDeviations.isFinite() && standardDeviations >= 0.0) {
            "standardDeviations must be finite and non-negative"
        }
        val middle = sma(closes, period)
        val upper = MutableList<Double?>(closes.size) { null }
        val lower = MutableList<Double?>(closes.size) { null }
        val window = ArrayDeque<Double>()

        closes.forEachIndexed { index, raw ->
            val value = raw?.takeIf { it.isFinite() }
            if (value == null) {
                window.clear()
                return@forEachIndexed
            }
            window.addLast(value)
            if (window.size > period) window.removeFirst()
            if (window.size == period) {
                val mean = middle[index] ?: return@forEachIndexed
                val variance = (window.sumOf { it * it } / period - mean * mean).coerceAtLeast(0.0)
                val deviation = sqrt(variance) * standardDeviations
                val high = mean + deviation
                val low = mean - deviation
                upper[index] = high.takeIf(Double::isFinite)
                lower[index] = low.takeIf(Double::isFinite)
            }
        }
        return BollingerValues(middle, upper, lower)
    }

    /**
     * Wilder ATR over aligned high/low/close arrays. The first true range in a
     * continuous segment is high-low; later ranges also include prior-close gaps.
     */
    fun atr(
        highs: List<Double?>,
        lows: List<Double?>,
        closes: List<Double?>,
        period: Int = 14
    ): List<Double?> {
        require(highs.size == lows.size && lows.size == closes.size) {
            "highs, lows and closes must have equal sizes"
        }
        require(period > 0) { "period must be positive" }
        val result = MutableList<Double?>(closes.size) { null }
        var previousClose: Double? = null
        var seedSum = 0.0
        var seedCount = 0
        var previousAtr: Double? = null

        closes.indices.forEach { index ->
            val high = highs[index]?.takeIf { it.isFinite() }
            val low = lows[index]?.takeIf { it.isFinite() }
            val close = closes[index]?.takeIf { it.isFinite() }
            if (high == null || low == null || close == null || high < low || close !in low..high) {
                previousClose = null
                seedSum = 0.0
                seedCount = 0
                previousAtr = null
                return@forEach
            }

            val priorClose = previousClose
            val trueRange = if (priorClose == null) {
                high - low
            } else {
                max(high - low, max(kotlin.math.abs(high - priorClose), kotlin.math.abs(low - priorClose)))
            }
            previousClose = close
            if (!trueRange.isFinite()) {
                seedSum = 0.0
                seedCount = 0
                previousAtr = null
                return@forEach
            }

            val priorAtr = previousAtr
            if (priorAtr == null) {
                seedSum += trueRange
                seedCount++
                if (seedCount == period) {
                    val firstAtr = seedSum / period
                    if (firstAtr.isFinite()) {
                        result[index] = firstAtr
                        previousAtr = firstAtr
                    }
                }
            } else {
                val nextAtr = (priorAtr * (period - 1) + trueRange) / period
                if (nextAtr.isFinite()) {
                    result[index] = nextAtr
                    previousAtr = nextAtr
                } else {
                    previousClose = null
                    seedSum = 0.0
                    seedCount = 0
                    previousAtr = null
                }
            }
        }
        return result
    }

    private fun rsiValue(averageGain: Double, averageLoss: Double): Double? {
        if (!averageGain.isFinite() || !averageLoss.isFinite()) return null
        if (averageGain == 0.0 && averageLoss == 0.0) return 50.0
        if (averageLoss == 0.0) return 100.0
        if (averageGain == 0.0) return 0.0
        val relativeStrength = averageGain / averageLoss
        val value = 100.0 - 100.0 / (1.0 + relativeStrength)
        return value.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
    }
}
