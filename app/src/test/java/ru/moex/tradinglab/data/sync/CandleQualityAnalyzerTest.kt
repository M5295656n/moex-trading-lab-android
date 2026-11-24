package ru.moex.tradinglab.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.moex.tradinglab.data.local.CandleEntity

class CandleQualityAnalyzerTest {
    @Test
    fun reportsIncompleteAndInvalidOhlcWithoutInventingGaps() {
        val report = CandleQualityAnalyzer.analyze(listOf(
            candle("2026-11-23 10:00:00", 10.0, 12.0, 9.0, 11.0, 5.0),
            candle("2026-11-23 10:01:00", null, 12.0, 9.0, 11.0, null),
            candle("2026-11-23 10:02:00", 10.0, 8.0, 9.0, 11.0, -1.0)
        ))
        assertEquals(3, report.candleCount)
        assertEquals(1, report.incompleteOhlcCount)
        assertEquals(1, report.invalidOhlcCount)
        assertEquals(1, report.negativeVolumeCount)
        assertFalse(report.timestampGapsEvaluated)
    }

    private fun candle(begin: String, open: Double?, high: Double?, low: Double?,
        close: Double?, volume: Double?) = CandleEntity(
        instrumentId = "SHARES:TQBR:SBER", intervalMinutes = 1, begin = begin,
        end = null, open = open, high = high, low = low, close = close,
        value = null, volume = volume, source = "MOEX ISS", fetchedAtEpochMillis = 100
    )
}
