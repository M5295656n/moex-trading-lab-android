package ru.moex.tradinglab.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MoexMarketClockTest {
    @Test
    fun usesMoscowDateWhenUtcAndMoscowAreOnDifferentCalendarDays() {
        val instant = Instant.parse("2026-11-25T21:30:00Z").toEpochMilli()

        assertEquals(LocalDate.parse("2026-11-26"), MoexMarketClock.today(instant))
    }

    @Test
    fun keepsPreviousDateBeforeMoscowMidnight() {
        val instant = Instant.parse("2026-11-25T20:30:00Z").toEpochMilli()

        assertEquals(LocalDate.parse("2026-11-25"), MoexMarketClock.today(instant))
    }
}
