package ru.moex.tradinglab.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun missingAndNonFiniteValuesBreakTheWindowWithoutBecomingZero() {
        val values = listOf(1.0, 2.0, null, 4.0, Double.NaN, 6.0, 8.0, 10.0)
        val sma = CandleIndicators.sma(values, 2)
        assertEquals(1.5, sma[1]!!, 1e-9)
        assertNull(sma[2])
        assertNull(sma[3])
        assertNull(sma[4])
        assertNull(sma[5])
        assertEquals(7.0, sma[6]!!, 1e-9)
        assertEquals(9.0, sma[7]!!, 1e-9)
    }

    @Test
    fun appendingFutureValuesDoesNotChangeEarlierResults() {
        val history = listOf(10.0, 12.0, 11.0, 14.0, 13.0)
        val extended = history + listOf(1000.0, 2000.0)
        val earlierSma = CandleIndicators.sma(history, 3)
        val extendedSma = CandleIndicators.sma(extended, 3).take(history.size)
        val earlierEma = CandleIndicators.ema(history, 3)
        val extendedEma = CandleIndicators.ema(extended, 3).take(history.size)
        assertEquals(earlierSma, extendedSma)
        assertEquals(earlierEma, extendedEma)
    }

    @Test(expected = IllegalArgumentException::class)
    fun smaRejectsNonPositivePeriod() {
        CandleIndicators.sma(listOf(1.0), 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emaRejectsNonPositivePeriod() {
        CandleIndicators.ema(listOf(1.0), -1)
    }
}
