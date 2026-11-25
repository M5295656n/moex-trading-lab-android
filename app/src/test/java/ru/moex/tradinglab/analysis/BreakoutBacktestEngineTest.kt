package ru.moex.tradinglab.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakoutBacktestEngineTest {
    @Test
    fun rejectsUnsortedOrDuplicateTimestamps() {
        val candles = listOf(candle("2026-01-02", 100.0), candle("2026-01-01", 101.0))
        try {
            BreakoutBacktestEngine.run(candles)
            throw AssertionError("Expected chronology validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("increasing"))
        }
    }

    @Test
    fun rejectsMissingOhlcInsteadOfReplacingItWithZero() {
        val candles = listOf(candle("2026-01-01", 100.0), candle("2026-01-02", 101.0, open = null))
        try {
            BreakoutBacktestEngine.run(candles)
            throw AssertionError("Expected OHLC validation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("missing OHLC"))
        }
    }

    @Test
    fun returnsZeroTradeMetricsWhenNoBreakoutOccurs() {
        val candles = (0 until 40).map { index ->
            candle("2026-01-%02d".format(index + 1), 100.0 + index * 0.01, volume = 100.0)
        }
        val result = BreakoutBacktestEngine.run(
            candles,
            BacktestConfig(atrPeriod = 3, breakoutLookbackBars = 20, volumeBaselineBars = 20)
        )
        assertEquals(0, result.metrics.tradeCount)
        assertEquals(1_000.0, result.metrics.finalEquity, 1e-9)
        assertEquals(0.0, result.metrics.winRatePercent, 1e-9)
    }

    @Test
    fun positionSizingDoesNotCountEntrySlippageTwice() {
        val quantity = BreakoutBacktestEngine.calculateQuantity(
            cash = 1_000.0,
            entryPrice = 100.0,
            stopDistance = 9.91,
            config = BacktestConfig(
                commissionRatePerSide = 0.0,
                slippageBasisPointsPerSide = 5.0
            )
        )

        assertEquals(1, quantity)
    }

    @Test
    fun calculatesMetricsFromClosedTrades() {
        val trades = listOf(
            BacktestTrade("a", "b", 100.0, 110.0, 1, 10.0, 1.0, 9.0, BacktestExitReason.TAKE_PROFIT),
            BacktestTrade("c", "d", 100.0, 90.0, 1, -10.0, 1.0, -11.0, BacktestExitReason.STOP_LOSS)
        )
        val metrics = BreakoutBacktestEngine.calculateMetrics(1_000.0, 998.0, trades, 5.0)
        assertEquals(50.0, metrics.winRatePercent, 1e-9)
        assertEquals(9.0, metrics.averageWin, 1e-9)
        assertEquals(-11.0, metrics.averageLoss, 1e-9)
        assertEquals(-1.0, metrics.expectancy, 1e-9)
        assertEquals(9.0 / 11.0, metrics.profitFactor!!, 1e-9)
        assertEquals(5.0, metrics.maxDrawdownPercent, 1e-9)
    }

    @Test
    fun atrRecoversWhenSeedPeriodHasZeroTrueRange() {
        val candles = listOf(
            BacktestCandle("2026-01-01", 100.0, 100.0, 100.0, 100.0, 100.0),
            BacktestCandle("2026-01-02", 100.0, 100.0, 100.0, 100.0, 100.0),
            BacktestCandle("2026-01-03", 100.0, 100.0, 100.0, 100.0, 100.0),
            BacktestCandle("2026-01-04", 100.0, 101.0, 100.0, 100.0, 100.0)
        )

        val atr = BreakoutBacktestEngine.calculateAtr(candles, period = 3)

        assertEquals(0.0, atr[2]!!, 1e-9)
        assertEquals(1.0 / 3.0, atr[3]!!, 1e-9)
    }

    private fun candle(
        timestamp: String,
        close: Double,
        volume: Double? = 100.0,
        open: Double? = close
    ) = BacktestCandle(
        timestamp = timestamp,
        open = open,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = volume
    )
}
