package ru.moex.tradinglab.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalHistoryPlannerTest {
    @Test
    fun resumesFromLastStoredCandleInclusively() {
        assertEquals("2026-11-23 18:40:00",
            IncrementalHistoryPlanner.nextFrom("2026-11-23 18:40:00", "2025-01-01"))
    }

    @Test
    fun usesInitialRangeWhenLocalHistoryIsEmpty() {
        assertEquals("2025-01-01", IncrementalHistoryPlanner.nextFrom(null, "2025-01-01"))
    }

    @Test
    fun leavesRangeUnboundedWhenNeitherBoundaryExists() {
        assertEquals(null, IncrementalHistoryPlanner.nextFrom(null, null))
    }

    @Test
    fun ignoresBlankStoredBoundaryAndUsesInitialRange() {
        assertEquals("2025-01-01", IncrementalHistoryPlanner.nextFrom("  ", "2025-01-01"))
    }
}
