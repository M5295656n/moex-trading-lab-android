package ru.moex.tradinglab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CandleChartWindowTest {
    @Test
    fun defaultWindowShowsAllCandlesAndLatestRange() {
        assertEquals(CandleChartWindow(0, 25), calculateCandleChartWindow(25, 1f, 1f))
    }

    @Test
    fun zoomLimitsWindowToAtLeastTenCandles() {
        assertEquals(CandleChartWindow(15, 25), calculateCandleChartWindow(25, 10f, 1f))
    }

    @Test
    fun panFractionSelectsOlderAndNewerWindows() {
        assertEquals(CandleChartWindow(0, 10), calculateCandleChartWindow(25, 2.5f, 0f))
        assertEquals(CandleChartWindow(15, 25), calculateCandleChartWindow(25, 2.5f, 1f))
    }

    @Test
    fun invalidZoomAndPanAreSafelyNormalized() {
        assertEquals(CandleChartWindow(0, 25), calculateCandleChartWindow(25, Float.NaN, Float.NaN))
    }

    @Test
    fun emptyHistoryProducesEmptyWindow() {
        assertEquals(CandleChartWindow(0, 0), calculateCandleChartWindow(0, 4f, 0.5f))
    }
}
