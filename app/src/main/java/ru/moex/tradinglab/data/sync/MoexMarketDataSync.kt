package ru.moex.tradinglab.data.sync

import ru.moex.tradinglab.data.local.MarketDataEntityMapper
import ru.moex.tradinglab.data.local.MarketDataStorage
import ru.moex.tradinglab.data.remote.IssCatalogResult
import ru.moex.tradinglab.data.remote.IssHistoryResult
import ru.moex.tradinglab.data.remote.IssInstrument
import ru.moex.tradinglab.data.remote.MoexIssDataRepository
import ru.moex.tradinglab.data.remote.MoexMarket

/**
 * Coordinates read-only MOEX ISS requests and durable Room persistence.
 * No broker APIs or order-submission capabilities are exposed here.
 */
class MoexMarketDataSync(
    private val remote: MoexIssDataRepository,
    private val storage: MarketDataStorage
) {
    suspend fun syncCatalog(
        market: MoexMarket,
        pageSize: Int = MoexIssDataRepository.DEFAULT_PAGE_SIZE,
        maxPages: Int = MoexIssDataRepository.DEFAULT_MAX_PAGES
    ): IssCatalogResult {
        val result = remote.loadCatalog(market, pageSize, maxPages)
        storage.saveCatalog(result)
        return result
    }

    suspend fun syncInstrumentHistory(
        instrument: IssInstrument,
        intervalMinutes: Int,
        from: String? = null,
        till: String? = null,
        pageSize: Int = MoexIssDataRepository.DEFAULT_PAGE_SIZE,
        maxPages: Int = MoexIssDataRepository.DEFAULT_MAX_PAGES
    ): IssHistoryResult {
        require(intervalMinutes in SUPPORTED_INTERVALS) { "Unsupported MOEX candle interval: " + intervalMinutes }
        require(from == null || from.isNotBlank()) { "from must not be blank" }
        require(till == null || till.isNotBlank()) { "till must not be blank" }

        val instrumentId = MarketDataEntityMapper.instrumentId(
            instrument.secId, instrument.market.name, instrument.boardId
        )
        storage.saveInstrument(instrument)

        val query = buildMap {
            put("interval", intervalMinutes.toString())
            from?.let { put("from", it) }
            till?.let { put("till", it) }
        }
        val result = remote.loadCandles(
            endpoint = instrument.market.candlesEndpoint(instrument.secId),
            pageSize = pageSize,
            maxPages = maxPages,
            query = query
        )
        storage.saveHistory(
            instrumentId = instrumentId,
            intervalMinutes = intervalMinutes,
            result = result,
            requestedFrom = from,
            requestedTill = till
        )
        return result
    }

    /**
     * Refreshes an existing series from its last stored candle, inclusive.
     * Re-fetching that boundary candle lets ISS correct a still-forming candle;
     * Room's composite key replaces it without duplicating history. For an empty
     * local series, initialFrom is used (null means ISS default history range).
     */
    suspend fun syncInstrumentHistoryIncrementally(
        instrument: IssInstrument,
        intervalMinutes: Int,
        initialFrom: String? = null,
        till: String? = null,
        pageSize: Int = MoexIssDataRepository.DEFAULT_PAGE_SIZE,
        maxPages: Int = MoexIssDataRepository.DEFAULT_MAX_PAGES
    ): IssHistoryResult {
        require(intervalMinutes in SUPPORTED_INTERVALS) { "Unsupported MOEX candle interval: " + intervalMinutes }
        val instrumentId = MarketDataEntityMapper.instrumentId(
            instrument.secId, instrument.market.name, instrument.boardId
        )
        val lastStoredBegin = storage.latestCandleBegin(instrumentId, intervalMinutes)
        val from = IncrementalHistoryPlanner.nextFrom(lastStoredBegin, initialFrom)
        return syncInstrumentHistory(
            instrument = instrument,
            intervalMinutes = intervalMinutes,
            from = from,
            till = till,
            pageSize = pageSize,
            maxPages = maxPages
        )
    }

    companion object {
        /** MOEX ISS candle interval values are expressed in minutes; 24 denotes daily candles. */
        val SUPPORTED_INTERVALS = setOf(1, 10, 60, 24)
    }
}

/** Pure boundary selection, intentionally inclusive to refresh the last stored candle. */
object IncrementalHistoryPlanner {
    fun nextFrom(lastStoredBegin: String?, initialFrom: String?): String? =
        lastStoredBegin?.takeIf(String::isNotBlank) ?: initialFrom?.takeIf(String::isNotBlank)
}
