package ru.moex.tradinglab.data.sync

import com.google.gson.Gson
import ru.moex.tradinglab.data.local.MarketDataDao
import ru.moex.tradinglab.data.remote.CalendarMarket
import ru.moex.tradinglab.data.remote.MoexCalendarRepository
import ru.moex.tradinglab.data.remote.MoexSessionPeriod
import java.time.LocalDate

sealed interface HistoryVerificationResult {
    data class Verified(val expectedCandles: Int, val observedCandles: Int) : HistoryVerificationResult
    data class GapsDetected(
        val missingStarts: List<String>,
        val unexpectedStarts: List<String>
    ) : HistoryVerificationResult
    data class Unknown(val reason: String) : HistoryVerificationResult
}

/**
 * Verifies daily share candles against MOEX's instrument+board session schedule.
 * Other markets and intraday intervals remain UNKNOWN until their session-type
 * and candle-boundary rules are implemented and independently tested.
 */
class MoexDailyHistoryCoverageVerifier(
    private val dao: MarketDataDao,
    private val calendar: MoexCalendarRepository
) {
    suspend fun verify(
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String
    ): HistoryVerificationResult {
        if (intervalMinutes != DAILY_INTERVAL_MINUTES) {
            return HistoryVerificationResult.Unknown("Проверка расписания пока поддерживает только дневные свечи")
        }
        val instrument = dao.getInstrument(instrumentId)
            ?: return HistoryVerificationResult.Unknown("Инструмент отсутствует в локальном каталоге")
        if (instrument.market != "SHARES") {
            return HistoryVerificationResult.Unknown("Проверка календаря пока доступна только для акций")
        }
        val boardId = instrument.boardId?.takeIf(String::isNotBlank)
            ?: return HistoryVerificationResult.Unknown("У инструмента не указан борд MOEX")
        val fromDate = parseDate(from) ?: return HistoryVerificationResult.Unknown("Некорректная дата начала")
        val tillDate = parseDate(till) ?: return HistoryVerificationResult.Unknown("Некорректная дата окончания")
        if (tillDate.isBefore(fromDate)) {
            return HistoryVerificationResult.Unknown("Дата окончания раньше даты начала")
        }

        val coverage = dao.getCoverageForRange(instrumentId, intervalMinutes, from, till)
            ?: return HistoryVerificationResult.Unknown("Нет записи покрытия для выбранного диапазона")
        val candles = dao.getCandles(instrumentId, intervalMinutes, from, till)
        val sessions = runCatching {
            calendar.loadSessionPeriods(
                market = CalendarMarket.SHARES,
                from = fromDate,
                till = tillDate,
                secId = instrument.secId,
                boardId = boardId
            )
        }.getOrElse {
            return HistoryVerificationResult.Unknown("Не удалось получить расписание MOEX: ${it.message ?: "ошибка запроса"}")
        }

        val expected = expectedDailyStarts(sessions, instrument.secId, boardId)
        if (expected.isEmpty()) {
            return HistoryVerificationResult.Unknown("MOEX не вернула применимое расписание инструмента и борда")
        }
        val report = CandleGapAnalyzer.analyze(candles.map { it.begin }, expected)
        if (!report.evaluated) {
            return HistoryVerificationResult.Unknown("Расписание не удалось проверить")
        }

        val discrepancies = buildList {
            report.missingStarts.forEach { add("MISSING:$it") }
            report.unexpectedStarts.forEach { add("UNEXPECTED:$it") }
        }
        val updated = dao.updateCoverageQuality(
            coverageId = coverage.coverageId,
            gapCount = discrepancies.size,
            gapsJson = Gson().toJson(discrepancies)
        )
        if (updated != 1) {
            return HistoryVerificationResult.Unknown("Не удалось сохранить результат проверки покрытия")
        }
        return if (discrepancies.isEmpty()) {
            HistoryVerificationResult.Verified(expected.size, candles.size)
        } else {
            HistoryVerificationResult.GapsDetected(report.missingStarts, report.unexpectedStarts)
        }
    }

    companion object {
        const val DAILY_INTERVAL_MINUTES = 24

        /** Daily ISS candle timestamps are normalized to midnight on the trading date. */
        internal fun expectedDailyStarts(
            sessions: List<MoexSessionPeriod>,
            secId: String,
            boardId: String
        ): List<String> = sessions.asSequence()
            .filter { it.secId == null || it.secId == secId }
            .filter { it.boardId == null || it.boardId == boardId }
            .map { "${it.tradeDate} 00:00:00" }
            .distinct()
            .sorted()
            .toList()

        private fun parseDate(value: String): LocalDate? =
            runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }
}
