package ru.moex.tradinglab.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class MoexMarketEndpointTest {
    @Test
    fun buildsMarketSpecificCandleEndpoints() {
        assertEquals("engines/stock/markets/shares/securities/SBER/candles", MoexMarket.SHARES.candlesEndpoint("SBER"))
        assertEquals("engines/futures/markets/forts/securities/SiZ6/candles", MoexMarket.FUTURES.candlesEndpoint("SiZ6"))
        assertEquals("engines/stock/markets/index/securities/IMOEX/candles", MoexMarket.INDICES.candlesEndpoint("IMOEX"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsafeSecidPathSegments() {
        MoexMarket.SHARES.candlesEndpoint("../SBER")
    }
}