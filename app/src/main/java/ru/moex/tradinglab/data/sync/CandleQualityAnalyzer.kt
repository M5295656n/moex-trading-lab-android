package ru.moex.tradinglab.data.sync

import ru.moex.tradinglab.data.local.CandleEntity

/**
 * Checks candle-internal invariants only. Timestamp gaps are not inferred from
 * elapsed time because trading sessions and pauses vary by market/timeframe.
 */
data class CandleQualityReport(
    val candleCount: Int,
    val incompleteOhlcCount: Int,
    val invalidOhlcCount: Int,
    val negativeVolumeCount: Int,
    val timestampGapsEvaluated: Boolean = false
)

object CandleQualityAnalyzer {
    fun analyze(candles: List<CandleEntity>): CandleQualityReport {
        var incomplete = 0
        var invalid = 0
        var negativeVolume = 0
        candles.forEach { candle ->
            val o = candle.open
            val h = candle.high
            val l = candle.low
            val c = candle.close
            if (o == null || h == null || l == null || c == null) {
                incomplete++
            } else if (h < l || h < maxOf(o, c) || l > minOf(o, c)) {
                invalid++
            }
            if (candle.volume != null && candle.volume < 0.0) negativeVolume++
        }
        return CandleQualityReport(candles.size, incomplete, invalid, negativeVolume)
    }
}
