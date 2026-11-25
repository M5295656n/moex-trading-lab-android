package ru.moex.tradinglab.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.moex.tradinglab.data.remote.MoexSessionPeriod
import java.time.LocalDate
import java.time.LocalDateTime

class MoexDailyHistoryCoverageVerifierTest {
    private fun session(
        date: String,
        secId: String? = "SBER",
        boardId: String? = "TQBR"
    ) = MoexSessionPeriod(
        tradeDate = LocalDate.parse(date),
        session = 1,
        boardId = boardId,
        secId = secId,
        type = "main",
        startsAt = LocalDateTime.parse("${date}T10:00:00"),
        endsAt = LocalDateTime.parse("${date}T18:50:00")
    )

    @Test fun buildsOneDailyCandlePerApplicableTradingDate() {
        assertEquals(
            listOf("2026-01-05 00:00:00", "2026-01-06 00:00:00"),
            MoexDailyHistoryCoverageVerifier.expectedDailyStarts(
                listOf(session("2026-01-05"), session("2026-01-06")),
                "SBER",
                "TQBR"
            )
        )
    }

    @Test fun ignoresScheduleRowsForOtherInstrumentOrBoard() {
        assertEquals(
            listOf("2026-01-05 00:00:00"),
            MoexDailyHistoryCoverageVerifier.expectedDailyStarts(
                listOf(
                    session("2026-01-05"),
                    session("2026-01-06", secId = "GAZP"),
                    session("2026-01-07", boardId = "TQTF")
                ),
                "SBER",
                "TQBR"
            )
        )
    }

    @Test fun acceptsGenericRowsAndDeduplicatesMultipleSessionsPerDay() {
        assertEquals(
            listOf("2026-01-05 00:00:00"),
            MoexDailyHistoryCoverageVerifier.expectedDailyStarts(
                listOf(
                    session("2026-01-05", secId = null, boardId = null),
                    session("2026-01-05", secId = "SBER", boardId = "TQBR")
                ),
                "SBER",
                "TQBR"
            )
        )
    }

    @Test fun emptyScheduleDoesNotProduceExpectedCandles() {
        assertEquals(
            emptyList<String>(),
            MoexDailyHistoryCoverageVerifier.expectedDailyStarts(emptyList(), "SBER", "TQBR")
        )
    }
}
