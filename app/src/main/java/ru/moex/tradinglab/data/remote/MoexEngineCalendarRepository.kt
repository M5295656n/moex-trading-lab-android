package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * MOEX engine-level operating window. This is NOT a board/security trading
 * calendar and must never be used to generate expected candle timestamps.
 */
data class MoexEngineCalendarDay(
    val date: LocalDate,
    val isEngineWorkDay: Boolean?,
    val startTime: LocalTime?,
    val stopTime: LocalTime?
)

enum class MoexEngineCalendarScope {
    ENGINE_OPERATIONAL_ONLY
}

data class MoexEngineCalendarSnapshot(
    val market: CalendarMarket,
    val days: List<MoexEngineCalendarDay>,
    val source: String,
    val endpoint: String,
    val fetchedAtEpochMillis: Long,
    val scope: MoexEngineCalendarScope = MoexEngineCalendarScope.ENGINE_OPERATIONAL_ONLY
)

/**
 * Reads public /iss/engines/{stock|futures} timetable and dailytable.
 *
 * Weekly timetable is the baseline; dailytable overrides it for exceptional
 * dates (including transferred working days). The result describes engine
 * availability only. It does not represent a specific board, instrument,
 * auction, clearing break or candle schedule.
 */
class MoexEngineCalendarRepository(
    private val client: MoexIssClient
) {
    suspend fun loadDays(
        market: CalendarMarket,
        from: LocalDate,
        till: LocalDate
    ): MoexEngineCalendarSnapshot {
        require(!till.isBefore(from)) { "till must not be before from" }

        val endpoint = "engines/${market.path}"
        val timetable = client.getTable(endpoint, tableName = "timetable")
        val dailytable = client.getTable(endpoint, tableName = "dailytable")
        val fetchedAt = maxOf(timetable.fetchedAtEpochMillis, dailytable.fetchedAtEpochMillis)

        return MoexEngineCalendarSnapshot(
            market = market,
            days = MoexEngineCalendarParser.expand(
                timetableRows = timetable.rows,
                dailyRows = dailytable.rows,
                from = from,
                till = till
            ),
            source = "MOEX ISS",
            endpoint = endpoint,
            fetchedAtEpochMillis = fetchedAt
        )
    }
}

internal object MoexEngineCalendarParser {
    fun expand(
        timetableRows: List<Map<String, JsonElement?>>,
        dailyRows: List<Map<String, JsonElement?>>,
        from: LocalDate,
        till: LocalDate
    ): List<MoexEngineCalendarDay> {
        require(!till.isBefore(from)) { "till must not be before from" }

        val weekly = timetableRows.mapNotNull { row ->
            val weekday = row.int("week_day")?.takeIf { it in 1..7 } ?: return@mapNotNull null
            DayOfWeek.of(weekday) to row.toWindow()
        }.toMap()

        val exceptions = dailyRows.mapNotNull { row ->
            val date = row.string("date")?.let {
                runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
            } ?: return@mapNotNull null
            date to row.toWindow()
        }.toMap()

        val result = mutableListOf<MoexEngineCalendarDay>()
        var date = from
        while (!date.isAfter(till)) {
            val window = exceptions[date] ?: weekly[date.dayOfWeek]
            result += MoexEngineCalendarDay(
                date = date,
                isEngineWorkDay = window?.isWorkDay,
                startTime = window?.startTime,
                stopTime = window?.stopTime
            )
            date = date.plusDays(1)
        }
        return result
    }

    private data class Window(
        val isWorkDay: Boolean?,
        val startTime: LocalTime?,
        val stopTime: LocalTime?
    )

    private fun Map<String, JsonElement?>.toWindow(): Window = Window(
        isWorkDay = when (int("is_work_day")) {
            0 -> false
            1 -> true
            else -> null
        },
        startTime = string("start_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        stopTime = string("stop_time")?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    )

    private fun Map<String, JsonElement?>.string(key: String): String? =
        this[key]?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun Map<String, JsonElement?>.int(key: String): Int? =
        string(key)?.toIntOrNull()
}
