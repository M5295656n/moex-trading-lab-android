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
 *
 * Calendar rows are paginated explicitly. A single response is never assumed
 * to represent the complete schedule.
 */
class MoexCalendarRepository(private val client: MoexIssTableClient) {
    suspend fun loadTradingDays(
        market: CalendarMarket,
        from: LocalDate,
        till: LocalDate
    ): List<MoexTradingDay> {
        require(!till.isBefore(from)) { "till must not be before from" }
        val rows = loadAllRows(
            endpoint = "calendars/${market.path}",
            query = mapOf("from" to from.toString(), "till" to till.toString()),
            tableName = "off_days"
        )
        return rows.mapNotNull { row ->
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
        val rows = loadAllRows(
            endpoint = "calendars/${market.path}/session",
            query = query,
            tableName = "session_schedule"
        )
        return rows.map { row ->
            val values = row.mapValues { (_, value) ->
                value?.takeUnless(JsonElement::isJsonNull)?.asString
            }
            val parsed = MoexCalendarRowParser.parse(values, market)
                ?: throw MoexIssException.InvalidResponse(
                    "Invalid row in MOEX session_schedule; completeness cannot be confirmed"
                )
            MoexSessionPeriod(
                tradeDate = parsed.tradeDate,
                session = values["tradingsession"]?.toIntOrNull(),
                boardId = values["boardid"]?.takeUnless { it == "-" || it.isBlank() },
                secId = values["secid"]?.takeUnless { it == "-" || it.isBlank() },
                type = parsed.type,
                startsAt = parsed.startsAt,
                endsAt = parsed.endsAt
            )
        }.sortedBy(MoexSessionPeriod::startsAt)
    }

    /**
     * Uses the ISS start/limit paging contract. A repeated page or exhausted
     * safety bound is an error, never a silently truncated successful result.
     */
    private suspend fun loadAllRows(
        endpoint: String,
        query: Map<String, String>,
        tableName: String
    ): List<Map<String, JsonElement?>> {
        val allRows = mutableListOf<Map<String, JsonElement?>>()
        val seenPages = mutableSetOf<String>()
        var start = 0

        repeat(MAX_PAGES) {
            val page = client.getTable(
                endpoint = endpoint,
                query = query + mapOf(
                    "start" to start.toString(),
                    "limit" to PAGE_SIZE.toString()
                ),
                tableName = tableName
            ).rows

            if (page.isEmpty()) return allRows
            if (page.size > PAGE_SIZE) {
                throw MoexIssException.InvalidResponse(
                    "MOEX calendar ignored the requested page limit"
                )
            }

            val fingerprint = page.map { row ->\n                row.toSortedMap().mapValues { (_, value) -> value?.toString() }\n            }.toString()
            if (!seenPages.add(fingerprint)) {
                throw MoexIssException.InvalidResponse(
                    "MOEX calendar returned a repeated page; completeness is unknown"
                )
            }

            allRows += page
            start += page.size
            if (page.size < PAGE_SIZE) return allRows
        }

        throw MoexIssException.InvalidResponse(
            "MOEX calendar pagination exceeded $MAX_PAGES pages; completeness is unknown"
        )
    }

    private fun Map<String, JsonElement?>.string(key: String): String? =
        this[key]?.takeUnless(JsonElement::isJsonNull)?.asString

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 1_000
    }
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
