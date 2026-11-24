package ru.moex.tradinglab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleIndicatorsTest {
    @Test
    fun smaUsesOnlyTheCurrentAndPreviousPeriodValues() {
        val result = CandleIndicators.sma(listOf(1.0, 2.0, 3.0, 4.0, 100.0), 3)
        assertNull(result[0])
        assertNull(result[1])
        assertEquals(2.0, result[2]!!, 1e-9)
        assertEquals(3.0, result[3]!!, 1e-9)
        assertEquals(35.6666666667, result[4]!!, 1e-8)
    }

    @Test
    fun emaStartsWithSimpleAverageThenUsesRecursiveSmoothing() {
        val result = CandleIndicators.ema(listOf(1.0, 2.0, 3.0, 4.0), 3)
        assertNull(result[0])
        assertNull(result[1])
        assertEquals(2.0, result[2]!!, 1e-9)
        assertEquals(3.0, result[3]!!, 1e-9)
    }

    @Test
    fun rsiUsesWilderSmoothingAndAlignsTheFirstValueAfterPeriodChanges() {
        val result = CandleIndicators.rsi(listOf(10.0, 11.0, 10.0, 11.0, 10.0), period = 2)
        assertNull(result[0])
        assertNull(result[1])
        assertEquals(50.0, result[2]!!, 1e-9)
        assertEquals(75.0, result[3]!!, 1e-9)
        assertEquals(37.5, result[4]!!, 1e-9)
    }

    @Test
    fun rsiHandlesFlatRisingAndFallingSeries() {
        assertEquals(50.0, CandleIndicators.rsi(listOf(5.0, 5.0, 5.0), 2)[2]!!, 1e-9)
        assertEquals(100.0, CandleIndicators.rsi(listOf(1.0, 2.0, 3.0), 2)[2]!!, 1e-9)
        assertEquals(0.0, CandleIndicators.rsi(listOf(3.0, 2.0, 1.0), 2)[2]!!, 1e-9)
    }

    @Test
    fun macdWaitsForSlowEmaAndThenForSignalWarmup() {
        val result = CandleIndicators.macd(
            closes = (1..10).map { it.toDouble() },
            fastPeriod = 3,
            slowPeriod = 5,
            signalPeriod = 2
        )
        assertNull(result.line[3])
        assertTrue(result.line[4] != null)
        assertNull(result.signal[4])
        assertTrue(result.signal[5] != null)
        assertEquals(
            result.line[5]!! - result.signal[5]!!,
            result.histogram[5]!!,
            1e-9
        )
    }

    @Test
    fun bollingerBandsUsePopulationDeviation() {
        val result = CandleIndicators.bollingerBands(
            closes = listOf(1.0, 2.0, 3.0),
            period = 3,
            standardDeviations = 2.0
        )
        assertNull(result.middle[1])
        assertEquals(2.0, result.middle[2]!!, 1e-9)
        assertEquals(2.0 + 2.0 * kotlin.math.sqrt(2.0 / 3.0), result.upper[2]!!, 1e-9)
        assertEquals(2.0 - 2.0 * kotlin.math.sqrt(2.0 / 3.0), result.lower[2]!!, 1e-9)
    }

    @Test
    fun atrUsesTrueRangeAndWilderSmoothing() {
        val result = CandleIndicators.atr(
            highs = listOf(11.0, 12.0, 11.0, 13.0),
            lows = listOf(9.0, 10.0, 9.0, 11.0),
            closes = listOf(10.0, 11.0, 10.0, 12.0),
            period = 2
        )
        assertNull(result[0])
        assertEquals(2.0, result[1]!!, 1e-9)
        assertEquals(2.0, result[2]!!, 1e-9)
        assertEquals(2.5, result[3]!!, 1e-9)
    }

    @Test
    fun atrIncludesGapsFromPreviousClose() {
        val result = CandleIndicators.atr(
            highs = listOf(10.0, 14.0),
            lows = listOf(9.0, 12.0),
            closes = listOf(9.5, 13.0),
            period = 1
        )
        assertEquals(1.0, result[0]!!, 1e-9)
        assertEquals(4.5, result[1]!!, 1e-9)
    }

    @Test
    fun missingAndNonFiniteValuesBreakCalculationSegments() {
        val values = listOf(1.0, 2.0, null, 4.0, Double.NaN, 6.0, 8.0, 10.0)
        val sma = CandleIndicators.sma(values, 2)
        assertEquals(1.5, sma[1]!!, 1e-9)
        assertNull(sma[2])
        assertNull(sma[3])
        assertNull(sma[4])
        assertNull(sma[5])
        assertEquals(7.0, sma[6]!!, 1e-9)
        assertEquals(9.0, sma[7]!!, 1e-9)

        val rsi = CandleIndicators.rsi(listOf(1.0, 2.0, 3.0, null, 4.0, 5.0, 6.0), 2)
        assertEquals(100.0, rsi[2]!!, 1e-9)
        assertNull(rsi[3])
        assertNull(rsi[4])
        assertNull(rsi[5])
        assertEquals(100.0, rsi[6]!!, 1e-9)
    }

    @Test
    fun appendingFutureValuesDoesNotChangeEarlierResults() {
        val history = listOf(10.0, 12.0, 11.0, 14.0, 13.0, 15.0, 14.0)
        val extended = history + listOf(1000.0, 2000.0)
        assertEquals(
            CandleIndicators.sma(history, 3),
            CandleIndicators.sma(extended, 3).take(history.size)
        )
        assertEquals(
            CandleIndicators.ema(history, 3),
            CandleIndicators.ema(extended, 3).take(history.size)
        )
        assertEquals(
            CandleIndicators.rsi(history, 3),
            CandleIndicators.rsi(extended, 3).take(history.size)
        )
        val macd = CandleIndicators.macd(history, 2, 4, 2)
        val extendedMacd = CandleIndicators.macd(extended, 2, 4, 2)
        assertEquals(macd.line, extendedMacd.line.take(history.size))
        assertEquals(macd.signal, extendedMacd.signal.take(history.size))
        assertEquals(macd.histogram, extendedMacd.histogram.take(history.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun smaRejectsNonPositivePeriod() {
        CandleIndicators.sma(listOf(1.0), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emaRejectsNonPositivePeriod() {
        CandleIndicators.ema(listOf(1.0), -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun macdRejectsInvalidPeriodOrder() {
        CandleIndicators.macd(listOf(1.0, 2.0), fastPeriod = 5, slowPeriod = 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun atrRejectsMismatchedInputLengths() {
        CandleIndicators.atr(listOf(1.0), emptyList(), listOf(1.0), 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun bollingerBandsRejectNegativeDeviation() {
        CandleIndicators.bollingerBands(listOf(1.0, 2.0), 2, -1.0)
    }
}
