package ru.moex.tradinglab.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.moex.tradinglab.data.local.CandleEntity
import ru.moex.tradinglab.data.local.HistoryCoverageEntity

class BacktestHistoryGateTest {
    private val instrumentId = "SHARES:TQBR:SBER"
    private val from = "2026-01-01 00:00:00"
    private val till = "2026-01-02 00:00:00"

    private fun coverage(
        paginationStatus: String = "COMPLETE",
        skippedRows: Int = 0,
        gapCount: Int? = 0,
        gapsJson: String? = "[]"
    ) = HistoryCoverageEntity(
        coverageId = "coverage-1",
        instrumentId = instrumentId,
        intervalMinutes = 24,
        source = "MOEX ISS",
        endpoint = "engines/stock/markets/shares/securities/SBER/candles",
        requestedFrom = from,
        requestedTill = till,
        firstCandleBegin = from,
        lastCandleBegin = till,
        fetchedAtEpochMillis = 1L,
        pageCount = 1,
        pageSize = 500,
        paginationStatus = paginationStatus,
        skippedRows = skippedRows,
        gapCount = gapCount,
        gapsJson = gapsJson,
        freshnessStatus = "UNKNOWN"
    )

    private fun candles() = listOf(
        CandleEntity(instrumentId, 24, from, null, 100.0, 102.0, 99.0, 101.0, null, 1000.0, "MOEX ISS", 1L),
        CandleEntity(instrumentId, 24, till, null, 101.0, 103.0, 100.0, 102.0, null, 1200.0, "MOEX ISS", 1L)
    )

    private fun reason(
        coverage: HistoryCoverageEntity? = coverage(),
        candles: List<CandleEntity> = candles()
    ) = BacktestHistoryGate.rejectionReason(
        coverage, candles, instrumentId, 24, from, till
    )

    @Test fun acceptsCompleteGapFreeMoexHistory() {
        assertNull(reason())
    }

    @Test fun rejectsMissingCoverage() {
        assertEquals("Нет записи покрытия истории для выбранного диапазона", reason(coverage = null))
    }

    @Test fun rejectsIncompletePagination() {
        assertEquals("Загрузка истории не завершена: PAGE_LIMIT_REACHED", reason(coverage = coverage(paginationStatus = "PAGE_LIMIT_REACHED")))
    }

    @Test fun rejectsSkippedRows() {
        assertEquals("При загрузке были пропущены строки: 1", reason(coverage = coverage(skippedRows = 1)))
    }

    @Test fun rejectsUnknownGapStatus() {
        assertEquals(
            "Непрерывность истории не проверена; сначала требуется анализ пропусков свечей",
            reason(coverage = coverage(gapCount = null, gapsJson = null))
        )
    }

    @Test fun rejectsDetectedGaps() {
        assertEquals("В выбранной истории обнаружены пропуски свечей", reason(coverage = coverage(gapCount = 1, gapsJson = "[\"2026-01-02 00:00:00\"]")))
    }

    @Test fun rejectsMissingOhlcWithoutZeroFill() {
        val broken = candles().toMutableList().also { it[0] = it[0].copy(close = null) }
        assertEquals(
            "У свечи №1 отсутствует OHLC; данные не будут заменены нулями",
            reason(candles = broken)
        )
    }

    @Test fun rejectsUnsortedTimestamps() {
        val unsorted = candles().reversed()
        assertEquals("Свечи не расположены в строгом хронологическом порядке", reason(candles = unsorted))
    }


    @Test fun acceptsDateOnlyCoverageForFullDayTimestampRange() {
        val dateOnlyCoverage = coverage().copy(
            requestedFrom = "2026-01-01",
            requestedTill = "2026-01-02"
        )
        assertNull(
            BacktestHistoryGate.rejectionReason(
                dateOnlyCoverage,
                candles(),
                instrumentId,
                24,
                "2026-01-01 00:00:00",
                "2026-01-02 23:59:59"
            )
        )
    }

    @Test fun rejectsDateOnlyCoverageThatEndsBeforeRequestedDay() {
        val tooShort = coverage().copy(
            requestedFrom = "2026-01-01",
            requestedTill = "2026-01-01"
        )
        val reason = BacktestHistoryGate.rejectionReason(
            tooShort,
            candles(),
            instrumentId,
            24,
            "2026-01-01 00:00:00",
            "2026-01-02 23:59:59"
        )
        assertEquals("Запись покрытия не охватывает весь выбранный диапазон", reason)
    }

    @Test fun rejectsCoverageForDifferentSource() {
        assertEquals(
            "Источник истории не подтверждён как MOEX ISS",
            reason(coverage = coverage().copy(source = "OTHER"))
        )
    }
}
