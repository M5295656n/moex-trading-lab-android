package ru.moex.tradinglab.domain.marketdata

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Calendar-aware expected-candle comparison.
 *
 * This component deliberately does not infer trading days from weekdays or
 * engine operating hours. Callers must supply a verified, instrument/board
 * scoped calendar and mark it complete for the requested range.
 */
data class ExpectedTradingSession(
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime
)

data class CalendarAwareGapResult(
    val status: Status,
    val expectedCount: Int,
    val observedCount: Int,
    val missingBegins: List<LocalDateTime>,
    val unexpectedBegins: List<LocalDateTime>
) {
    enum class Status { COMPLETE, UNKNOWN, INVALID_INPUT }
}

object CalendarAwareCandleGapAnalyzer {

    /**
     * Compare intraday candles against session slots. Each session is treated
     * as a half-open interval [startsAt, endsAt); no candle is expected at the
     * closing boundary. A partial final interval is retained when the session
     * length is not divisible by the selected timeframe.
     */
    fun analyzeIntraday(
        sessions: List<ExpectedTradingSession>,
        observedBegins: Collection<LocalDateTime>,
        intervalMinutes: Int,
        calendarComplete: Boolean
    ): CalendarAwareGapResult {
        if (intervalMinutes <= 0 || sessions.any { !it.endsAt.isAfter(it.startsAt) }) {
            return invalid(observedBegins)
        }
        if (!calendarComplete) return unknown(observedBegins)

        val expected = buildSet {
            sessions.forEach { session ->
                var cursor = session.startsAt
                while (cursor.isBefore(session.endsAt)) {
                    add(cursor)
                    cursor = cursor.plusMinutes(intervalMinutes.toLong())
                }
            }
        }
        return compare(expected, observedBegins)
    }

    /**
     * Compare daily candles only against explicitly supplied traded dates.
     * This avoids assuming that weekdays are trading days or that all
     * securities have identical listing/trading histories.
     */
    fun analyzeDaily(
        tradedDates: Collection<LocalDate>,
        observedDates: Collection<LocalDate>,
        calendarComplete: Boolean
    ): CalendarAwareGapResult {
        if (!calendarComplete) {
            return CalendarAwareGapResult(
                status = CalendarAwareGapResult.Status.UNKNOWN,
                expectedCount = 0,
                observedCount = observedDates.toSet().size,
                missingBegins = emptyList(),
                unexpectedBegins = emptyList()
            )
        }
        val expected = tradedDates.toSortedSet().map { it.atStartOfDay() }.toSet()
        val observed = observedDates.toSet().map { it.atStartOfDay() }.toSet()
        return compare(expected, observed)
    }

    private fun compare(
        expected: Set<LocalDateTime>,
        observedBegins: Collection<LocalDateTime>
    ): CalendarAwareGapResult {
        val observed = observedBegins.toSet()
        return CalendarAwareGapResult(
            status = CalendarAwareGapResult.Status.COMPLETE,
            expectedCount = expected.size,
            observedCount = observed.size,
            missingBegins = (expected - observed).sorted(),
            unexpectedBegins = (observed - expected).sorted()
        )
    }

    private fun invalid(observed: Collection<LocalDateTime>) =
        CalendarAwareGapResult(
            status = CalendarAwareGapResult.Status.INVALID_INPUT,
            expectedCount = 0,
            observedCount = observed.toSet().size,
            missingBegins = emptyList(),
            unexpectedBegins = emptyList()
        )

    private fun unknown(observed: Collection<LocalDateTime>) =
        CalendarAwareGapResult(
            status = CalendarAwareGapResult.Status.UNKNOWN,
            expectedCount = 0,
            observedCount = observed.toSet().size,
            missingBegins = emptyList(),
            unexpectedBegins = emptyList()
        )
}
