package ru.moex.tradinglab.data.local

/** Injectable boundary for verified local history. */
fun interface BacktestHistorySource {
    suspend fun load(
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String
    ): BacktestHistoryResult
}
