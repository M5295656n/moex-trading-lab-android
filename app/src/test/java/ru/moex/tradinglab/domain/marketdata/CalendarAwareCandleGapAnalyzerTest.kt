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
        assertEquals(38, result.missingBegins.size)
        assertTrue(result.missingBegins.contains(LocalDateTime.parse("2026-11-23T10:20:00")))
        assertTrue(result.missingBegins.contains(LocalDateTime.parse("2026-11-23T12:50:00")))
        assertTrue(result.missingBegins.contains(LocalDateTime.parse("2026-11-23T14:10:00")))
        assertTrue(result.missingBegins.contains(LocalDateTime.parse("2026-11-23T17:50:00")))
        assertTrue(result.missingBegins.none { it.toLocalTime().toString() == "13:00" })
        assertTrue(result.missingBegins.none { it.toLocalTime().toString() == "13:50" })
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
