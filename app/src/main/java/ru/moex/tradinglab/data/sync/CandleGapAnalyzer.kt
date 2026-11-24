package ru.moex.tradinglab.data.sync

import ru.moex.tradinglab.data.local.CandleEntity

/**
 * A market-specific calendar adapter supplies only candle starts that should
 * exist for the selected market, board and timeframe. Implementations must
 * account for sessions, auctions, holidays and intraday breaks.
 */
fun interface ExpectedCandleSchedule {
    fun expectedStarts(
        market: String,
        boardId: String?,
        intervalMinutes: Int,
        fromInclusive: String,
        tillInclusive: String
    ): List<String>
}

data class CandleGapReport(
    val evaluated: Boolean,
    val expectedCount: Int?,
    val observedExpectedCount: Int?,
    val missingStarts: List<String>,
    val unexpectedStarts: List<String>
) {
    val gapCount: Int? get() = if (evaluated) missingStarts.size else null
}

object CandleGapAnalyzer {
    /** A null schedule means UNKNOWN, never "zero gaps". */
    fun analyze(observedStarts: List<String>, expectedStarts: List<String>?): CandleGapReport {
        if (expectedStarts == null) {
            return CandleGapReport(false, null, null, emptyList(), emptyList())
        }
        val expected = expectedStarts.toSortedSet()
        val observed = observedStarts.toSortedSet()
        return CandleGapReport(
            evaluated = true,
            expectedCount = expected.size,
            observedExpectedCount = expected.intersect(observed).size,
            missingStarts = (expected - observed).toList(),
            unexpectedStarts = (observed - expected).toList()
        )
    }

    fun analyze(
        candles: List<CandleEntity>,
        schedule: ExpectedCandleSchedule?,
        market: String,
        boardId: String?,
        intervalMinutes: Int,
        fromInclusive: String?,
        tillInclusive: String?
    ): CandleGapReport {
        if (schedule == null || fromInclusive.isNullOrBlank() || tillInclusive.isNullOrBlank()) {
            return analyze(candles.map { it.begin }, null)
        }
        val expected = schedule.expectedStarts(
            market, boardId, intervalMinutes, fromInclusive, tillInclusive
        )
        return analyze(candles.map { it.begin }, expected)
    }
}
