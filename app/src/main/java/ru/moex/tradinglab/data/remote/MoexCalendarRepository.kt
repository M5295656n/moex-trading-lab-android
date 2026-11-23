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
            mapOf("from" to from.toString(), "till" to till.toString())
        )
        return table.rows.mapNotNull { row ->
            val date = row.date("tradedate") ?: return@mapNotNull null
            val flag = row.string("is_traded")?.toIntOrNull()?.let { it == 1 }
            MoexTradingDay(date, flag, row.string("reason"))
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
        val table = client.getTable("calendars/${market.path}/session", query)
        return table.rows.mapNotNull { it.toSessionPeriod(market) }
            .sortedBy(MoexSessionPeriod::startsAt)
    }

    private fun Map<String, JsonElement?>.toSessionPeriod(market: CalendarMarket): MoexSessionPeriod? {
        val tradeDate = date("tradedate") ?: return null
        val type = string("type")?.takeIf(String::isNotBlank) ?: return null
        val startsAt = dateTime("time_from", market) ?: return null
        val endsAt = dateTime("time_till", market) ?: return null
        if (!endsAt.isAfter(startsAt)) return null
        return MoexSessionPeriod(
            tradeDate = tradeDate,
            session = string("tradingsession")?.toIntOrNull(),
            boardId = string("boardid")?.takeUnless { it == "-" || it.isBlank() },
            secId = string("secid")?.takeUnless { it == "-" || it.isBlank() },
            type = type,
            startsAt = startsAt,
            endsAt = endsAt
        )
    }

    private fun Map<String, JsonElement?>.string(key: String): String? =
        this[key]?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun Map<String, JsonElement?>.date(key: String): LocalDate? =
        string(key)?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

    private fun Map<String, JsonElement?>.dateTime(key: String, market: CalendarMarket): LocalDateTime? {
        val value = string(key) ?: return null
        return runCatching {
            when {
                value.length >= 19 -> LocalDateTime.parse(value.take(19).replace(' ', 'T'))
                market == CalendarMarket.SHARES -> {
                    val date = date("tradedate") ?: return null
                    LocalDateTime.of(date, LocalTime.parse(value))
                }
                else -> null
            }
        }.getOrNull()
    }
}
