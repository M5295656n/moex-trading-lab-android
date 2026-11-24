package ru.moex.tradinglab.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketRegimeAnalyzerTest {
    @Test
    fun risingSeriesIsBullish() {
        val candles = (1..80).map { candle(it.toDouble(), 100.0) }
        val result = MarketRegimeAnalyzer.assessTrend(candles, 5, 20, 3, 0.01)
        assertEquals(TrendDirection.BULLISH, result.direction)
        assertTrue(result.fastEma!! > result.slowEma!!)
        assertTrue(result.slowEmaSlopePercent!! > 0.0)
    }

    @Test
    fun fallingSeriesIsBearish() {
        val candles = (1..80).map { candle((100 - it).toDouble(), 100.0) }
        val result = MarketRegimeAnalyzer.assessTrend(candles, 5, 20, 3, 0.01)
        assertEquals(TrendDirection.BEARISH, result.direction)
        assertTrue(result.fastEma!! < result.slowEma!!)
        assertTrue(result.slowEmaSlopePercent!! < 0.0)
    }

    @Test
    fun flatSeriesIsSideways() {
        val result = MarketRegimeAnalyzer.assessTrend(
            List(60) { candle(100.0, 10.0) }, 5, 20, 3
        )
        assertEquals(TrendDirection.SIDEWAYS, result.direction)
        assertEquals(0.0, result.slowEmaSlopePercent!!, 1e-9)
    }

    @Test
    fun insufficientHistoryDoesNotInventTrendValues() {
        val result = MarketRegimeAnalyzer.assessTrend(
            List(10) { candle(it.toDouble(), 1.0) }, 3, 8, 5
        )
        assertEquals(TrendDirection.INSUFFICIENT_DATA, result.direction)
        assertNull(result.fastEma)
        assertNull(result.slowEma)
        assertNull(result.slowEmaSlopePercent)
    }

    @Test
    fun missingCloseRestartsEmaWarmup() {
        val candles = List(60) { index ->
            candle(if (index == 30) null else index.toDouble() + 1.0, 1.0)
        }
        val result = MarketRegimeAnalyzer.assessTrend(candles, 5, 20, 5)
        assertEquals(TrendDirection.INSUFFICIENT_DATA, result.direction)
    }

    @Test
    fun volumeBaselineExcludesCurrentCandle() {
        val candles = (1..20).map { candle(it.toDouble(), it.toDouble()) } +
            candle(21.0, 100.0)
        val result = MarketRegimeAnalyzer.assessVolume(candles, 20)!!
        assertEquals(10.5, result.averagePreviousVolume, 1e-9)
        assertEquals(100.0 / 10.5, result.relativeVolume, 1e-9)
        assertTrue(result.aboveAverage)
    }

    @Test
    fun missingBaselineVolumeReturnsUnknown() {
        val candles = (1..20).map { index ->
            candle(index.toDouble(), if (index == 7) null else 10.0)
        } + candle(21.0, 20.0)
        assertNull(MarketRegimeAnalyzer.assessVolume(candles, 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidTrendPeriodsAreRejected() {
        MarketRegimeAnalyzer.assessTrend(emptyList(), 20, 20)
    }

    private fun candle(close: Double?, volume: Double?) = AnalysisCandle(
        high = close?.plus(1.0),
        low = close?.minus(1.0),
        close = close,
        volume = volume
    )
}
