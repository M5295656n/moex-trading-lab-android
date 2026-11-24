package ru.moex.tradinglab.domain.marketdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalendarAwareCandleGapAnalyzerTest {

    @Test
    fun intradayIgnoresBreaksBetweenSessionsAndFindsOnlyMissingSlots() {
        val morning = ExpectedTradingSession(
            LocalDateTime.parse("2026-11-23T10:00:00"),
            LocalDateTime.parse("2026-11-23T13:00:00")
        )
        val evening = ExpectedTradingSession(
            LocalDateTime.parse("2026-11-23T14:00:00"),
            LocalDateTime.parse("2026-11-23T18:00:00")
        )
        val observed = listOf(
            LocalDateTime.parse("2026-11-23T10:00:00"),
            LocalDateTime.parse("2026-11-23T10:10:00"),
            LocalDateTime.parse("2026-11-23T10:30:00"),
            LocalDateTime.parse("2026-11-23T14:00:00")
        )

        val result = CalendarAwareCandleGapAnalyzer.analyzeIntraday(
            sessions = listOf(morning, evening),
            observedBegins = observed,
            intervalMinutes = 10,
            calendarComplete = true
        )

        assertEquals(CalendarAwareGapResult.Status.COMPLETE, result.status)
        assertEquals(
            listOf(
                LocalDateTime.parse("2026-11-23T10:20:00"),
                LocalDateTime.parse("2026-11-23T14:10:00"),
                LocalDateTime.parse("2026-11-23T14:20:00"),
                LocalDateTime.parse("2026-11-23T14:30:00"),
                LocalDateTime.parse("2026-11-23T14:40:00"),
                LocalDateTime.parse("2026-11-23T14:50:00"),
                LocalDateTime.parse("2026-11-23T15:00:00"),
                LocalDateTime.parse("2026-11-23T15:10:00"),
                LocalDateTime.parse("2026-11-23T15:20:00"),
                LocalDateTime.parse("2026-11-23T15:30:00"),
                LocalDateTime.parse("2026-11-23T15:40:00"),
                LocalDateTime.parse("2026-11-23T15:50:00"),
                LocalDateTime.parse("2026-11-23T16:00:00"),
                LocalDateTime.parse("2026-11-23T16:10:00"),
                LocalDateTime.parse("2026-11-23T16:20:00"),
                LocalDateTime.parse("2026-11-23T16:30:00"),
                LocalDateTime.parse("2026-11-23T16:40:00"),
                LocalDateTime.parse("2026-11-23T16:50:00"),
                LocalDateTime.parse("2026-11-23T17:00:00"),
                LocalDateTime.parse("2026-11-23T17:10:00"),
                LocalDateTime.parse("2026-11-23T17:20:00"),
                LocalDateTime.parse("2026-11-23T17:30:00"),
                LocalDateTime.parse("2026-11-23T17:40:00"),
                LocalDateTime.parse("2026-11-23T17:50:00")
            ),
            result.missingBegins
        )
    }

    @Test
    fun incompleteCalendarNeverReportsFalseGaps() {
        val session = ExpectedTradingSession(
            LocalDateTime.parse("2026-11-23T10:00:00"),
            LocalDateTime.parse("2026-11-23T11:00:00")
        )
        val result = CalendarAwareCandleGapAnalyzer.analyzeIntraday(
            listOf(session), emptyList(), 10, calendarComplete = false
        )

        assertEquals(CalendarAwareGapResult.Status.UNKNOWN, result.status)
        assertTrue(result.missingBegins.isEmpty())
        assertEquals(0, result.expectedCount)
    }

    @Test
    fun dailyComparisonUsesOnlyExplicitTradingDates() {
        val traded = listOf(
            LocalDate.parse("2026-11-20"),
            LocalDate.parse("2026-11-23")
        )
        val result = CalendarAwareCandleGapAnalyzer.analyzeDaily(
            tradedDates = traded,
            observedDates = listOf(LocalDate.parse("2026-11-20")),
            calendarComplete = true
        )

        assertEquals(CalendarAwareGapResult.Status.COMPLETE, result.status)
        assertEquals(
            listOf(LocalDate.parse("2026-11-23").atStartOfDay()),
            result.missingBegins
        )
    }

    @Test
    fun invalidIntervalDoesNotInventExpectedCandles() {
        val result = CalendarAwareCandleGapAnalyzer.analyzeIntraday(
            emptyList(), emptyList(), intervalMinutes = 0, calendarComplete = true
        )
        assertEquals(CalendarAwareGapResult.Status.INVALID_INPUT, result.status)
        assertTrue(result.missingBegins.isEmpty())
    }
}
