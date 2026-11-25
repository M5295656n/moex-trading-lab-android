package ru.moex.tradinglab.data.local

import ru.moex.tradinglab.analysis.BacktestCandle
import ru.moex.tradinglab.analysis.BacktestHistoryGate

sealed interface BacktestHistoryResult {
    data class Ready(
        val instrument: MarketInstrumentEntity,
        val intervalMinutes: Int,
        val from: String,
        val till: String,
        val candles: List<BacktestCandle>,
        val source: String,
        val fetchedAtEpochMillis: Long,
        val freshnessStatus: String,
        val coverageId: String
    ) : BacktestHistoryResult

    data class Rejected(val reason: String) : BacktestHistoryResult
}

/**
 * Read-only bridge from existing Room history into the backtest engine.
 * A single completed coverage record must certify the whole requested range.
 */
class BacktestHistoryRepository(
    private val dao: MarketDataDao
) : BacktestHistorySource {
    override suspend fun load(
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String
    ): BacktestHistoryResult {
        require(instrumentId.isNotBlank()) { "instrumentId must not be blank" }
        require(intervalMinutes > 0) { "intervalMinutes must be positive" }
        require(from.isNotBlank() && till.isNotBlank() && from <= till) {
            "Invalid backtest date range"
        }

        val instrument = dao.getInstrument(instrumentId)
            ?: return BacktestHistoryResult.Rejected("Инструмент отсутствует в локальном каталоге MOEX")
        val coverage = dao.getCoverageForRange(instrumentId, intervalMinutes, from, till)
        val stored = dao.getCandles(instrumentId, intervalMinutes, from, till)

        val rejection = BacktestHistoryGate.rejectionReason(
            coverage = coverage,
            candles = stored,
            instrumentId = instrumentId,
            intervalMinutes = intervalMinutes,
            from = from,
            till = till
        )
        if (rejection != null) return BacktestHistoryResult.Rejected(rejection)

        val verifiedCoverage = requireNotNull(coverage)
        return BacktestHistoryResult.Ready(
            instrument = instrument,
            intervalMinutes = intervalMinutes,
            from = from,
            till = till,
            candles = stored.map {
                BacktestCandle(
                    timestamp = it.begin,
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume
                )
            },
            source = verifiedCoverage.source,
            fetchedAtEpochMillis = verifiedCoverage.fetchedAtEpochMillis,
            freshnessStatus = verifiedCoverage.freshnessStatus,
            coverageId = verifiedCoverage.coverageId
        )
    }
}
