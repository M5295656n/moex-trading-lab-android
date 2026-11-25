package ru.moex.tradinglab.analysis

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.moex.tradinglab.data.local.BacktestHistoryResult
import ru.moex.tradinglab.data.local.BacktestHistorySource
import ru.moex.tradinglab.data.local.MarketInstrumentEntity

class BacktestRunnerTest {
    @Test fun doesNotRunSimulatorWhenHistoryGateRejects() = runBlocking {
        val runner = BacktestRunner(
            BacktestHistorySource { _, _, _, _ ->
                BacktestHistoryResult.Rejected("История неполная")
            }
        )
        val result = runner.runBreakout("SHARES:TQBR:SBER", 24, "2026-01-01", "2026-02-01")
        assertEquals(BacktestRunResult.Rejected("История неполная"), result)
    }

    @Test fun passesOnlyVerifiedHistoryIntoSimulator() = runBlocking {
        val instrument = MarketInstrumentEntity(
            instrumentId = "SHARES:TQBR:SBER",
            secId = "SBER",
            name = "Сбербанк",
            shortName = "Сбер",
            isin = null,
            boardId = "TQBR",
            lotSize = 10.0,
            currency = "RUB",
            market = "SHARES",
            source = "MOEX ISS",
            rawFieldsJson = null,
            fetchedAtEpochMillis = 1L
        )
        val candles = (0..60).map { index ->
            val price = 100.0 + index * 0.1
            BacktestCandle(
                timestamp = "2026-01-${(index + 1).toString().padStart(2, '0')} 00:00:00",
                open = price,
                high = price + 1.0,
                low = price - 1.0,
                close = price + 0.2,
                volume = 1000.0
            )
        }
        val ready = BacktestHistoryResult.Ready(
            instrument = instrument,
            intervalMinutes = 24,
            from = "2026-01-01",
            till = "2026-03-02",
            candles = candles,
            source = "MOEX ISS",
            fetchedAtEpochMillis = 1L,
            freshnessStatus = "UNKNOWN",
            coverageId = "coverage-1"
        )
        val runner = BacktestRunner(BacktestHistorySource { _, _, _, _ -> ready })
        val result = runner.runBreakout(
            "SHARES:TQBR:SBER", 24, "2026-01-01", "2026-03-02",
            BacktestConfig(initialCapital = 100_000.0, lotSize = 1)
        )
        assertTrue(result is BacktestRunResult.Completed)
        assertEquals(61, (result as BacktestRunResult.Completed).history.candles.size)
    }
}
