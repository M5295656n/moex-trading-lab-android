package ru.moex.tradinglab.analysis

import ru.moex.tradinglab.data.local.BacktestHistoryResult
import ru.moex.tradinglab.data.local.BacktestHistorySource

sealed interface BacktestRunResult {
    data class Completed(
        val history: BacktestHistoryResult.Ready,
        val result: BacktestResult
    ) : BacktestRunResult

    data class Rejected(val reason: String) : BacktestRunResult
}

/**
 * Orchestrates the verified Room-history bridge and the existing simulator.
 * The simulator is never called when the history gate rejects its input.
 */
class BacktestRunner(
    private val historySource: BacktestHistorySource
) {
    suspend fun runBreakout(
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String,
        config: BacktestConfig = BacktestConfig()
    ): BacktestRunResult {
        return when (
            val history = historySource.load(instrumentId, intervalMinutes, from, till)
        ) {
            is BacktestHistoryResult.Rejected -> BacktestRunResult.Rejected(history.reason)
            is BacktestHistoryResult.Ready -> BacktestRunResult.Completed(
                history = history,
                result = BreakoutBacktestEngine.run(history.candles, config)
            )
        }
    }
}
