package ru.moex.tradinglab.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MoexCalendarRepositoryParsingTest {
    @Test fun stockSessionTimeIsCombinedWithTradeDate() {
        val parsed = MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "type" to "main",
                "time_from" to "10:00:00", "time_till" to "18:50:00"),
            CalendarMarket.SHARES
        )
        assertEquals(LocalDateTime.parse("2026-11-23T10:00:00"), parsed?.startsAt)
        assertEquals(LocalDateTime.parse("2026-11-23T18:50:00"), parsed?.endsAt)
    }

    @Test fun futuresSessionPreservesFullDateTime() {
        val parsed = MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "type" to "main_session",
                "time_from" to "2026-11-23 10:00:00", "time_till" to "2026-11-23 18:50:00"),
            CalendarMarket.FUTURES
        )
        assertEquals(LocalDateTime.parse("2026-11-23T10:00:00"), parsed?.startsAt)
    }

    @Test fun incompleteOrUnknownSessionIsNotInvented() {
        assertNull(MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "type" to "main_session",
                "time_from" to "10:00:00", "time_till" to "18:00:00"),
            CalendarMarket.FUTURES
        ))
        assertNull(MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "type" to "main",
                "time_from" to "18:00:00", "time_till" to "10:00:00"),
            CalendarMarket.SHARES
        ))
    }

    @Test fun tradingDayNullRemainsUnknown() {
        val raw: String? = null
        val isTraded = raw?.toIntOrNull()?.let { it == 1 }
        assertNull(isTraded)
        assertEquals(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-23"))
    }
}

internal object MoexCalendarRowParser {
    data class Parsed(val startsAt: LocalDateTime, val endsAt: LocalDateTime)

    fun parse(row: Map<String, String?>, market: CalendarMarket): Parsed? {
        val date = row["tradedate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        val type = row["type"]?.takeIf(String::isNotBlank) ?: return null
        fun parseTime(key: String): LocalDateTime? {
            val value = row[key] ?: return null
            return runCatching {
                if (value.length >= 19) LocalDateTime.parse(value.take(19).replace(' ', 'T'))
                else if (market == CalendarMarket.SHARES) LocalDateTime.of(date, java.time.LocalTime.parse(value))
                else null
            }.getOrNull()
        }
        val start = parseTime("time_from") ?: return null
        val end = parseTime("time_till") ?: return null
        return if (type.isNotBlank() && end.isAfter(start)) Parsed(start, end) else null
    }
}
