package ru.moex.tradinglab.data.local

import com.google.gson.Gson
import ru.moex.tradinglab.data.remote.IssCandle
import ru.moex.tradinglab.data.remote.IssInstrument
import ru.moex.tradinglab.data.remote.IssHistoryResult

object MarketDataEntityMapper {
    private val gson = Gson()

    fun instrument(item: IssInstrument, fetchedAtEpochMillis: Long = System.currentTimeMillis()) =
        MarketInstrumentEntity(
            instrumentId = instrumentId(item.secId, item.market.name, item.boardId),
            secId = item.secId,
            name = item.name,
            shortName = item.shortName,
            isin = item.isin,
            boardId = item.boardId,
            lotSize = item.lotSize,
            currency = item.currency,
            market = item.market.name,
            source = item.source,
            rawFieldsJson = gson.toJson(item.rawFields),
            fetchedAtEpochMillis = fetchedAtEpochMillis
        )

    fun candle(
        item: IssCandle,
        instrumentId: String,
        intervalMinutes: Int,
        source: String,
        fetchedAtEpochMillis: Long
    ) = CandleEntity(
        instrumentId = instrumentId,
        intervalMinutes = intervalMinutes,
        begin = item.begin,
        end = item.end,
        open = item.open,
        high = item.high,
        low = item.low,
        close = item.close,
        value = item.value,
        volume = item.volume,
        source = source,
        fetchedAtEpochMillis = fetchedAtEpochMillis
    )

    fun coverage(
        result: IssHistoryResult,
        instrumentId: String,
        intervalMinutes: Int,
        requestedFrom: String?,
        requestedTill: String?,
        coverageId: String,
        gapCount: Int? = null,
        gapsJson: String? = null,
        freshnessStatus: String = "UNKNOWN"
    ) = HistoryCoverageEntity(
        coverageId = coverageId,
        instrumentId = instrumentId,
        intervalMinutes = intervalMinutes,
        source = result.source,
        endpoint = result.endpoint,
        requestedFrom = requestedFrom,
        requestedTill = requestedTill,
        firstCandleBegin = result.firstBegin,
        lastCandleBegin = result.lastBegin,
        fetchedAtEpochMillis = result.fetchedAtEpochMillis,
        pageCount = result.pageCount,
        pageSize = result.requestedPageSize,
        paginationStatus = result.completeness.name,
        skippedRows = result.skippedRows,
        gapCount = gapCount,
        gapsJson = gapsJson,
        freshnessStatus = freshnessStatus
    )

    fun instrumentId(secId: String, market: String, boardId: String?): String =
        "${market.uppercase()}:${boardId.orEmpty().uppercase()}: ${secId.uppercase()}".replace(": ", ":")
}
