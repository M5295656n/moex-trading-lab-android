package ru.moex.tradinglab.data.local

import ru.moex.tradinglab.data.remote.IssCatalogResult
import ru.moex.tradinglab.data.remote.IssHistoryResult
import ru.moex.tradinglab.data.remote.IssInstrument

/**
 * Persistence boundary for validated MOEX ISS results. Network fetching stays in
 * MoexIssDataRepository; this class only maps and commits returned data to Room.
 */
class MarketDataStorage(
    private val dao: MarketDataDao
) {
    suspend fun saveCatalog(result: IssCatalogResult) {
        val instruments = result.instruments.map {
            MarketDataEntityMapper.instrument(it, result.fetchedAtEpochMillis)
        }
        dao.saveCatalog(
            items = instruments,
            sync = CatalogSyncEntity(
                market = result.instruments.firstOrNull()?.market?.name
                    ?: result.endpoint.toMarketKey(),
                source = result.source,
                endpoint = result.endpoint,
                fetchedAtEpochMillis = result.fetchedAtEpochMillis,
                instrumentCount = result.instruments.size,
                pageCount = result.pageCount,
                pageSize = result.requestedPageSize,
                completeness = result.completeness.name,
                skippedRows = result.skippedRows
            )
        )
    }

    suspend fun saveInstrument(
        instrument: IssInstrument,
        fetchedAtEpochMillis: Long = System.currentTimeMillis()
    ) {
        dao.upsertInstruments(listOf(MarketDataEntityMapper.instrument(instrument, fetchedAtEpochMillis)))
    }

    suspend fun latestCandleBegin(instrumentId: String, intervalMinutes: Int): String? =
        dao.getLatestCandleBegin(instrumentId, intervalMinutes)

    suspend fun saveHistory(
        instrumentId: String,
        intervalMinutes: Int,
        result: IssHistoryResult,
        requestedFrom: String? = null,
        requestedTill: String? = null,
        coverageId: String = "${instrumentId}:${intervalMinutes}:${result.fetchedAtEpochMillis}",
        gapCount: Int? = null,
        gapsJson: String? = null,
        freshnessStatus: String = "UNKNOWN"
    ) {
        require(intervalMinutes > 0) { "intervalMinutes must be positive" }
        val candles = result.candles.map {
            MarketDataEntityMapper.candle(
                item = it,
                instrumentId = instrumentId,
                intervalMinutes = intervalMinutes,
                source = result.source,
                fetchedAtEpochMillis = result.fetchedAtEpochMillis
            )
        }
        val coverage = MarketDataEntityMapper.coverage(
            result = result,
            instrumentId = instrumentId,
            intervalMinutes = intervalMinutes,
            requestedFrom = requestedFrom,
            requestedTill = requestedTill,
            coverageId = coverageId,
            gapCount = gapCount,
            gapsJson = gapsJson,
            freshnessStatus = freshnessStatus
        )
        dao.saveHistory(candles, coverage)
    }

    private fun String.toMarketKey(): String = when {
        contains("futures/markets/forts") -> "FUTURES"
        contains("markets/index") -> "INDICES"
        else -> "SHARES"
    }
}
