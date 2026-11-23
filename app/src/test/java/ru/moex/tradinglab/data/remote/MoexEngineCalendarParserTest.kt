package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MoexEngineCalendarParserTest {
    @Test
    fun expandsWeeklyBaselineAndAppliesDailyOverrides() {
        val weekly = listOf(
            row("week_day" to "5", "is_work_day" to "1",
                "start_time" to "06:20:00", "stop_time" to "23:59:59"),
            row("week_day" to "6", "is_work_day" to "1",
                "start_time" to "06:20:00", "stop_time" to "23:59:59"),
            row("week_day" to "7", "is_work_day" to "1",
                "start_time" to "06:20:00", "stop_time" to "23:59:59")
        )
        val exceptions = listOf(
            row("date" to "2026-11-21", "is_work_day" to "0",
                "start_time" to "10:00:00", "stop_time" to "19:00:00"),
            row("date" to "2026-11-22", "is_work_day" to "1",
                "start_time" to "06:00:00", "stop_time" to "23:59:59")
        )

        val days = MoexEngineCalendarParser.expand(
            weekly, exceptions,
            LocalDate.parse("2026-11-20"),
            LocalDate.parse("2026-11-23")
        )

        assertEquals(4, days.size)
        assertEquals(true, days[0].isEngineWorkDay)
        assertEquals(false, days[1].isEngineWorkDay)
        assertEquals(LocalTime.parse("10:00:00"), days[1].startTime)
        assertEquals(true, days[2].isEngineWorkDay)
        assertEquals(LocalTime.parse("06:00:00"), days[2].startTime)
        assertEquals(true, days[3].isEngineWorkDay)
    }

    @Test
    fun missingWeeklyDayRemainsUnknownInsteadOfAssumingWeekendClosed() {
        val days = MoexEngineCalendarParser.expand(
            timetableRows = emptyList(),
            dailyRows = emptyList(),
            from = LocalDate.parse("2026-11-23"),
            till = LocalDate.parse("2026-11-23")
        )
        assertEquals(1, days.size)
        assertNull(days.single().isEngineWorkDay)
        assertNull(days.single().startTime)
    }

    @Test
    fun nullExceptionStatusOverridesBaselineAsUnknown() {
        val weekly = listOf(row("week_day" to "1", "is_work_day" to "1"))
        val exceptions = listOf(row("date" to "2026-11-23", "is_work_day" to null))

        val day = MoexEngineCalendarParser.expand(
            weekly, exceptions,
            LocalDate.parse("2026-11-23"),
            LocalDate.parse("2026-11-23")
        ).single()

        assertNull(day.isEngineWorkDay)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsReversedDateRange() {
        MoexEngineCalendarParser.expand(
            emptyList(), emptyList(),
            LocalDate.parse("2026-11-24"),
            LocalDate.parse("2026-11-23")
        )
    }

    private fun row(vararg fields: Pair<String, String?>): Map<String, JsonElement?> =
        fields.associate { (key, value) -> key to value?.let(::JsonPrimitive) }
}
