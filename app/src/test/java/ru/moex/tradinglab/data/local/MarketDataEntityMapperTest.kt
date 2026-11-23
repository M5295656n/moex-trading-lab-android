package ru.moex.tradinglab.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.moex.tradinglab.data.remote.HistoryCompleteness
import ru.moex.tradinglab.data.remote.IssCandle
import ru.moex.tradinglab.data.remote.IssHistoryResult
import ru.moex.tradinglab.data.remote.IssInstrument
import ru.moex.tradinglab.data.remote.MoexMarket

class MarketDataEntityMapperTest {
    @Test
    fun instrumentIdentityIncludesMarketAndBoard() {
        val shares = MarketDataEntityMapper.instrument(sampleInstrument(MoexMarket.SHARES, "TQBR"))
        val futures = MarketDataEntityMapper.instrument(sampleInstrument(MoexMarket.FUTURES, "RFUD"))
        assertEquals("SHARES:TQBR:SBER", shares.instrumentId)
        assertEquals("FUTURES:RFUD:SBER", futures.instrumentId)
    }

    @Test
    fun candleMapperPreservesMissingMarketValuesAsNull() {
        val candle = MarketDataEntityMapper.candle(
            item = IssCandle("2026-11-20 10:00:00", null, null, null, null, 250.0, null, null),
            instrumentId = "SHARES:TQBR:SBER",
            intervalMinutes = 1,
            source = "MOEX ISS",
            fetchedAtEpochMillis = 100
        )
        assertNull(candle.open)
        assertNull(candle.high)
        assertNull(candle.low)
        assertNull(candle.value)
        assertNull(candle.volume)
        assertEquals(250.0, candle.close!!, 0.0)
    }

    @Test
    fun coverageDoesNotConfusePaginationWithGapFreeHistory() {
        val result = IssHistoryResult(
            candles = listOf(IssCandle("2026-11-20 10:00:00", null, 1.0, 2.0, 1.0, 2.0, null, 10.0)),
            source = "MOEX ISS",
            endpoint = "engines/stock/markets/shares/securities/SBER/candles",
            fetchedAtEpochMillis = 100,
            pageCount = 1,
            requestedPageSize = 500,
            completeness = HistoryCompleteness.COMPLETE,
            skippedRows = 0,
            pageStarts = listOf(0)
        )
        val coverage = MarketDataEntityMapper.coverage(
            result, "SHARES:TQBR:SBER", 1, null, null, "coverage-1"
        )
        assertEquals("COMPLETE", coverage.paginationStatus)
        assertNull(coverage.gapCount)
        assertNull(coverage.gapsJson)
    }

    private fun sampleInstrument(market: MoexMarket, board: String) = IssInstrument(
        secId = "SBER",
        name = "Сбербанк",
        shortName = "Сбер",
        isin = null,
        boardId = board,
        lotSize = 10.0,
        currency = "RUB",
        market = market,
        rawFields = emptyMap()
    )
}
