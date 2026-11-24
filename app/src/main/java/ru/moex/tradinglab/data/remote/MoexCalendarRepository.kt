package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class CalendarMarket(val path: String) {
    SHARES("stock"),
    FUTURES("futures")
}

data class MoexTradingDay(val date: LocalDate, val isTraded: Boolean?, val reason: String?)

data class MoexSessionPeriod(
    val tradeDate: LocalDate,
    val session: Int?,
    val boardId: String?,
    val secId: String?,
    val type: String,
    val startsAt: LocalDateTime,
    val endsAt: LocalDateTime
)

/**
 * Read-only adapter for MOEX's machine-readable calendar.
 * Authorization failures are propagated; callers must preserve UNKNOWN status.
 */
class MoexCalendarRepository(private val client: MoexIssClient) {
    suspend fun loadTradingDays(
        market: CalendarMarket,
        from: LocalDate,
        till: LocalDate
    ): List<MoexTradingDay> {
        require(!till.isBefore(from)) { "till must not be before from" }
        val table = client.getTable(
            "calendars/${market.path}",
            mapOf("from" to from.toString(), "till" to till.toString()),
            tableName = "off_days"
        )
        return table.rows.mapNotNull { row ->
            val date = row.string("tradedate")?.let {
                runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
            } ?: return@mapNotNull null
            val isTraded = when (row.string("is_traded")?.toIntOrNull()) {
                0 -> false
                1 -> true
                else -> null
            }
            MoexTradingDay(date, isTraded, row.string("reason"))
        }
    }

    suspend fun loadSessionPeriods(
        market: CalendarMarket,
        from: LocalDate,
        till: LocalDate,
        secId: String? = null,
        boardId: String? = null
    ): List<MoexSessionPeriod> {
        require(!till.isBefore(from)) { "till must not be before from" }
        require(secId == null || secId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid MOEX SECID" }
        require(boardId == null || boardId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid MOEX board ID" }
        val query = buildMap {
            put("from", from.toString())
            put("till", till.toString())
            secId?.let { put("secid", it) }
            boardId?.let { put("boardid", it) }
        }
        val table = client.getTable(
            "calendars/${market.path}/session",
            query,
            tableName = "session_schedule"
        )
        return table.rows.mapNotNull { row ->
            val values = row.mapValues { (_, value) ->
                value?.takeUnless(JsonElement::isJsonNull)?.asString
            }
            MoexCalendarRowParser.parse(values, market)?.let { parsed ->
                MoexSessionPeriod(
                    tradeDate = parsed.tradeDate,
                    session = values["tradingsession"]?.toIntOrNull(),
                    boardId = values["boardid"]?.takeUnless { it == "-" || it.isBlank() },
                    secId = values["secid"]?.takeUnless { it == "-" || it.isBlank() },
                    type = parsed.type,
                    startsAt = parsed.startsAt,
                    endsAt = parsed.endsAt
                )
            }
        }.sortedBy(MoexSessionPeriod::startsAt)
    }

    private fun Map<String, JsonElement?>.string(key: String): String? =
        this[key]?.takeUnless(JsonElement::isJsonNull)?.asString
}

/** Parser shared by the network adapter and deterministic unit tests. */
internal object MoexCalendarRowParser {
    data class Parsed(
        val tradeDate: LocalDate,
        val type: String,
        val startsAt: LocalDateTime,
        val endsAt: LocalDateTime
    )

    fun parse(row: Map<String, String?>, market: CalendarMarket): Parsed? {
        val date = row["tradedate"]?.let {
            runCatching { LocalDate.parse(it.take(10)) }.getOrNull()
        } ?: return null
        val type = row["type"]?.takeIf(String::isNotBlank) ?: return null

        fun parseTime(key: String): LocalDateTime? {
            val value = row[key] ?: return null
            return runCatching {
                when {
                    value.length >= 19 -> LocalDateTime.parse(value.take(19).replace(' ', 'T'))
                    market == CalendarMarket.SHARES ->
                        LocalDateTime.of(date, LocalTime.parse(value))
                    else -> null
                }
            }.getOrNull()
        }

        val start = parseTime("time_from") ?: return null
        val end = parseTime("time_till") ?: return null
        if (!end.isAfter(start)) return null
        return Parsed(date, type, start, end)
    }
}
