package ru.moex.tradinglab.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandleGapAnalyzerTest {
    @Test
    fun detectsOnlyCalendarExpectedMissingStarts() {
        val report = CandleGapAnalyzer.analyze(
            observedStarts = listOf("10:00", "10:02", "12:00"),
            expectedStarts = listOf("10:00", "10:01", "10:02")
        )
        assertTrue(report.evaluated)
        assertEquals(3, report.expectedCount)
        assertEquals(2, report.observedExpectedCount)
        assertEquals(listOf("10:01"), report.missingStarts)
        assertEquals(listOf("12:00"), report.unexpectedStarts)
        assertEquals(1, report.gapCount)
    }

    @Test
    fun unknownCalendarDoesNotClaimZeroGaps() {
        val report = CandleGapAnalyzer.analyze(
            observedStarts = listOf("10:00", "10:02"),
            expectedStarts = null
        )
        assertFalse(report.evaluated)
        assertNull(report.gapCount)
        assertEquals(emptyList<String>(), report.missingStarts)
    }

    @Test
    fun duplicateTimestampsDoNotCreateDuplicateGapEntries() {
        val report = CandleGapAnalyzer.analyze(
            observedStarts = listOf("10:00", "10:00", "10:02"),
            expectedStarts = listOf("10:00", "10:01", "10:02")
        )
        assertEquals(listOf("10:01"), report.missingStarts)
    }
}
