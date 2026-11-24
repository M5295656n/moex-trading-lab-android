package ru.moex.tradinglab.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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

    @Test fun incompleteFuturesTimeIsRejected() {
        assertNull(MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "type" to "main_session",
                "time_from" to "10:00:00", "time_till" to "18:00:00"),
            CalendarMarket.FUTURES
        ))
    }

    @Test fun invalidSessionRangeIsRejected() {
        assertNull(MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "type" to "main",
                "time_from" to "18:00:00", "time_till" to "10:00:00"),
            CalendarMarket.SHARES
        ))
    }

    @Test fun missingTypeIsRejected() {
        assertNull(MoexCalendarRowParser.parse(
            mapOf("tradedate" to "2026-11-23", "time_from" to "10:00:00",
                "time_till" to "18:00:00"),
            CalendarMarket.SHARES
        ))
    }
}
