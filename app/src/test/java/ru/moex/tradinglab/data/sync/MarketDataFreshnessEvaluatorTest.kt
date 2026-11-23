package ru.moex.tradinglab.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class MarketDataFreshnessEvaluatorTest {
    private val now = LocalDateTime.parse("2026-11-23 18:00:00", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    @Test fun unknownCalendarDoesNotClaimFreshOrStale() {
        val result = MarketDataFreshnessEvaluator.evaluate(null, listOf("2026-11-23 17:00:00"), 60, now)
        assertEquals(MarketDataFreshness.UNKNOWN, result.status)
    }

    @Test fun noCompletedExpectedCandleMeansMarketClosedOrPreSession() {
        val result = MarketDataFreshnessEvaluator.evaluate(
            listOf("2026-11-23 19:00:00"), emptyList(), 60, now
        )
        assertEquals(MarketDataFreshness.MARKET_CLOSED, result.status)
    }

    @Test fun latestExpectedCompletedCandlePresentMeansFresh() {
        val result = MarketDataFreshnessEvaluator.evaluate(
            listOf("2026-11-23 16:00:00", "2026-11-23 17:00:00"),
            listOf("2026-11-23 16:00:00", "2026-11-23 17:00:00"),
            60,
            now
        )
        assertEquals(MarketDataFreshness.FRESH, result.status)
    }

    @Test fun missingLatestExpectedCandleMeansStale() {
        val result = MarketDataFreshnessEvaluator.evaluate(
            listOf("2026-11-23 16:00:00", "2026-11-23 17:00:00"),
            listOf("2026-11-23 16:00:00"),
            60,
            now
        )
        assertEquals(MarketDataFreshness.STALE, result.status)
    }

    @Test fun incompleteCurrentCandleIsNotExpectedToBePresentYet() {
        val result = MarketDataFreshnessEvaluator.evaluate(
            listOf("2026-11-23 17:30:00"),
            emptyList(),
            60,
            LocalDateTime.parse("2026-11-23 18:00:00", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        )
        assertEquals(MarketDataFreshness.MARKET_CLOSED, result.status)
    }
}
