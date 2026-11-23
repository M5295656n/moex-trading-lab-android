package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import java.util.LinkedHashMap

enum class MoexMarket(val catalogEndpoint: String) {
    SHARES("engines/stock/markets/shares/securities"),
    FUTURES("engines/futures/markets/forts/securities"),
    INDICES("engines/stock/markets/index/securities")
}

data class IssInstrument(
    val secId: String,
    val name: String,
    val shortName: String?,
    val isin: String?,
    val boardId: String?,
    val lotSize: Double?,
    val currency: String?,
    val market: MoexMarket,
    val source: String = "MOEX ISS",
    val rawFields: Map<String, String?>
)

data class IssCandle(
    val begin: String,
    val end: String?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val value: Double?,
    val volume: Double?
)

enum class HistoryCompleteness {
    COMPLETE,
    PAGE_LIMIT_REACHED,
    PAGINATION_STALLED
}

data class IssHistoryResult(
    val candles: List<IssCandle>,
    val source: String,
    val endpoint: String,
    val fetchedAtEpochMillis: Long,
    val pageCount: Int,
    val requestedPageSize: Int,
    val completeness: HistoryCompleteness,
    val skippedRows: Int,
    val pageStarts: List<Int>
) {
    val isComplete: Boolean get() = completeness == HistoryCompleteness.COMPLETE
    val firstBegin: String? get() = candles.firstOrNull()?.begin
    val lastBegin: String? get() = candles.lastOrNull()?.begin
}

/**
 * Read-only catalog and candle-history layer over the low-level ISS client.
 * It never substitutes missing market values with zero.
 */
class MoexIssDataRepository(
    private val client: MoexIssClient
) {
    suspend fun loadCatalog(market: MoexMarket): List<IssInstrument> {
        val table = client.getTable(market.catalogEndpoint)
        return table.rows.mapNotNull { row -> IssRowMapper.instrument(row, market) }
    }

    /**
     * Loads pages from start=0 until ISS returns a short/empty page, or until
     * maxPages is reached. A page with no new candle timestamps is reported
     * as stalled rather than silently marked complete.
     */
    suspend fun loadCandles(
        endpoint: String,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        maxPages: Int = DEFAULT_MAX_PAGES
    ): IssHistoryResult {
        require(endpoint.isNotBlank()) { "ISS candles endpoint must not be blank" }
        require(pageSize in 1..MAX_PAGE_SIZE) { "pageSize must be between 1 and $MAX_PAGE_SIZE" }
        require(maxPages > 0) { "maxPages must be positive" }

        val candlesByBegin = LinkedHashMap<String, IssCandle>()
        val starts = mutableListOf<Int>()
        var skippedRows = 0
        var pages = 0
        var completeness = HistoryCompleteness.PAGE_LIMIT_REACHED
        var fetchedAt = System.currentTimeMillis()

        while (pages < maxPages) {
            val start = pages * pageSize
            starts += start
            val table = client.getTable(endpoint, mapOf("start" to start.toString(), "limit" to pageSize.toString()))
            fetchedAt = table.fetchedAtEpochMillis
            pages++

            var addedOnPage = 0
            table.rows.forEach { row ->
                val candle = IssRowMapper.candle(row)
                if (candle == null) {
                    skippedRows++
                } else if (candlesByBegin.putIfAbsent(candle.begin, candle) == null) {
                    addedOnPage++
                }
            }

            if (table.rows.size < pageSize) {
                completeness = HistoryCompleteness.COMPLETE
                break
            }
            if (addedOnPage == 0) {
                completeness = HistoryCompleteness.PAGINATION_STALLED
                break
            }
        }

        val sortedCandles = candlesByBegin.values.sortedBy(IssCandle::begin)
        return IssHistoryResult(
            candles = sortedCandles,
            source = "MOEX ISS",
            endpoint = endpoint,
            fetchedAtEpochMillis = fetchedAt,
            pageCount = pages,
            requestedPageSize = pageSize,
            completeness = completeness,
            skippedRows = skippedRows,
            pageStarts = starts.toList()
        )
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 500
        const val DEFAULT_MAX_PAGES = 200
        const val MAX_PAGE_SIZE = 5000
    }
}

internal object IssRowMapper {
    fun instrument(row: Map<String, JsonElement?>, market: MoexMarket): IssInstrument? {
        val secId = row.string("SECID")?.takeIf(String::isNotBlank) ?: return null
        val shortName = row.string("SHORTNAME")
        val fullName = row.string("SECNAME") ?: row.string("NAME") ?: shortName ?: secId
        val raw = row.mapValues { (_, value) -> value?.takeUnless(JsonElement::isJsonNull)?.asString }
        return IssInstrument(
            secId = secId,
            name = fullName,
            shortName = shortName,
            isin = row.string("ISIN"),
            boardId = row.string("BOARDID"),
            lotSize = row.number("LOTSIZE"),
            currency = row.string("CURRENCYID") ?: row.string("SETTLECURRENCY"),
            market = market,
            rawFields = raw
        )
    }

    fun candle(row: Map<String, JsonElement?>): IssCandle? {
        val begin = row.string("begin")?.takeIf(String::isNotBlank) ?: return null
        return IssCandle(
            begin = begin,
            end = row.string("end"),
            open = row.number("open"),
            high = row.number("high"),
            low = row.number("low"),
            close = row.number("close"),
            value = row.number("value"),
            volume = row.number("volume")
        )
    }

    private fun Map<String, JsonElement?>.string(key: String): String? =
        this[key]?.takeUnless(JsonElement::isJsonNull)?.asString

    private fun Map<String, JsonElement?>.number(key: String): Double? =
        string(key)?.toDoubleOrNull()
}
