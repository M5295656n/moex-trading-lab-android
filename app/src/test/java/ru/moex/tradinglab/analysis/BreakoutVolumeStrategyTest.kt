package ru.moex.tradinglab.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BreakoutVolumeStrategyTest {
    @Test
    fun confirmsCloseAbovePreviousTwentyHighsWithAtLeastOnePointFiveVolume() {
        val history = (1..20).map { candle(high = 100.0 + it, close = 100.0 + it, volume = 100.0) }
        val result = BreakoutVolumeStrategy.evaluate(
            history + candle(high = 130.0, close = 125.0, volume = 150.0)
        )

        assertEquals(BreakoutSignalStatus.CONFIRMED, result.status)
        assertEquals(120.0, result.breakoutLevel!!, 1e-9)
        assertEquals(125.0, result.close!!, 1e-9)
        assertEquals(1.5, result.relativeVolume!!, 1e-9)
    }

    @Test
    fun currentCandleHighDoesNotSetItsOwnBreakoutLevel() {
        val history = (1..20).map { candle(high = 100.0, close = 99.0, volume = 100.0) }
        val current = candle(high = 200.0, close = 101.0, volume = 200.0)
        val result = BreakoutVolumeStrategy.evaluate(history + current)

        assertEquals(BreakoutSignalStatus.CONFIRMED, result.status)
        assertEquals(100.0, result.breakoutLevel!!, 1e-9)
    }

    @Test
    fun closeAtOrBelowPriorHighDoesNotTrigger() {
        val history = (1..20).map { candle(high = 110.0, close = 100.0, volume = 100.0) }
        val result = BreakoutVolumeStrategy.evaluate(
            history + candle(high = 115.0, close = 110.0, volume = 300.0)
        )

        assertEquals(BreakoutSignalStatus.NO_BREAKOUT, result.status)
        assertNull(result.relativeVolume)
    }

    @Test
    fun priceBreakoutWithoutRequiredVolumeIsNotConfirmed() {
        val history = (1..20).map { candle(high = 110.0, close = 100.0, volume = 100.0) }
        val result = BreakoutVolumeStrategy.evaluate(
            history + candle(high = 120.0, close = 111.0, volume = 149.0)
        )

        assertEquals(BreakoutSignalStatus.BREAKOUT_WITHOUT_VOLUME_CONFIRMATION, result.status)
        assertEquals(1.49, result.relativeVolume!!, 1e-9)
    }

    @Test
    fun missingHistoricalHighReturnsInsufficientData() {
        val history = (1..20).map { index ->
            candle(high = if (index == 7) null else 110.0, close = 100.0, volume = 100.0)
        }
        val result = BreakoutVolumeStrategy.evaluate(
            history + candle(high = 120.0, close = 111.0, volume = 200.0)
        )

        assertEquals(BreakoutSignalStatus.INSUFFICIENT_DATA, result.status)
    }

    @Test
    fun missingVolumeDoesNotCreateConfirmedSignal() {
        val history = (1..20).map { index ->
            candle(high = 110.0, close = 100.0, volume = if (index == 7) null else 100.0)
        }
        val result = BreakoutVolumeStrategy.evaluate(
            history + candle(high = 120.0, close = 111.0, volume = 200.0)
        )

        assertEquals(BreakoutSignalStatus.INSUFFICIENT_DATA, result.status)
        assertNull(result.relativeVolume)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLookbackIsRejected() {
        BreakoutVolumeStrategy.evaluate(emptyList(), lookbackBars = 0)
    }

    private fun candle(high: Double?, close: Double?, volume: Double?) = AnalysisCandle(
        high = high,
        low = close?.minus(1.0),
        close = close,
        volume = volume
    )
}
