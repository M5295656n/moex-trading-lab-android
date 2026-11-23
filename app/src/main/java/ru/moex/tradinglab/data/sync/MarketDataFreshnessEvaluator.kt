package ru.moex.tradinglab.data.sync

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Freshness is evaluated against calendar-provided candle starts, never against
 * a fixed wall-clock age. The calendar must already exclude non-trading periods.
 */
enum class MarketDataFreshness {
    FRESH,
    STALE,
    MARKET_CLOSED,
    UNKNOWN
}

data class FreshnessReport(
    val status: MarketDataFreshness,
    val latestExpectedCompletedStart: String?,
    val latestObservedStart: String?,
    val evaluatedAt: String
)

object MarketDataFreshnessEvaluator {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * expectedStarts must cover the relevant recent trading sessions and contain
     * only candle starts for this instrument/board/timeframe.
     *
     * A candle is considered complete only after its interval has elapsed.
     * For daily candles, callers should provide the actual daily close time by
     * using a 1440-minute interval and schedule starts at the session open.
     */
    fun evaluate(
        expectedStarts: List<String>?,
        observedStarts: List<String>,
        intervalMinutes: Int,
        now: LocalDateTime
    ): FreshnessReport {
        require(intervalMinutes > 0) { "intervalMinutes must be positive" }
        val nowText = now.format(formatter)
        if (expectedStarts == null || expectedStarts.isEmpty()) {
            return FreshnessReport(MarketDataFreshness.UNKNOWN, null, observedStarts.maxOrNull(), nowText)
        }

        val parsedExpected = expectedStarts.distinct().mapNotNull { value ->
            runCatching { value to LocalDateTime.parse(value, formatter) }.getOrNull()
        }.sortedBy { it.second }
        if (parsedExpected.isEmpty()) {
            return FreshnessReport(MarketDataFreshness.UNKNOWN, null, observedStarts.maxOrNull(), nowText)
        }

        val latestCompleted = parsedExpected.lastOrNull { (_, start) ->
            !start.plus(Duration.ofMinutes(intervalMinutes.toLong())).isAfter(now)
        }
        val latestObserved = observedStarts.maxOrNull()

        if (latestCompleted == null) {
            return FreshnessReport(
                MarketDataFreshness.MARKET_CLOSED,
                null,
                latestObserved,
                nowText
            )
        }

        val observedDateTime = latestObserved?.let {
            runCatching { LocalDateTime.parse(it, formatter) }.getOrNull()
        }
        val expectedDateTime = latestCompleted.second
        val status = if (observedDateTime != null && !observedDateTime.isBefore(expectedDateTime)) {
            MarketDataFreshness.FRESH
        } else {
            MarketDataFreshness.STALE
        }
        return FreshnessReport(status, latestCompleted.first, latestObserved, nowText)
    }
}
