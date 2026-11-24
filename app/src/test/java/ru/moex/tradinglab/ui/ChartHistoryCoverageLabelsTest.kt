package ru.moex.tradinglab.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartHistoryCoverageLabelsTest {
    @Test
    fun paginationStatusesRemainDistinct() {
        assertEquals("получена завершающая страница", chartPaginationLabel("COMPLETE"))
        assertEquals(
            "достигнут лимит страниц — история может быть неполной",
            chartPaginationLabel("PAGE_LIMIT_REACHED")
        )
        assertEquals("пагинация остановилась", chartPaginationLabel("PAGINATION_STALLED"))
    }

    @Test
    fun unknownGapFreshnessIsNotReportedAsZeroOrFresh() {
        assertEquals("неизвестна", chartFreshnessLabel("UNKNOWN"))
    }

    @Test
    fun freshnessStatusesHaveReadableLabels() {
        assertEquals("актуальные", chartFreshnessLabel("FRESH"))
        assertEquals("устаревшие", chartFreshnessLabel("STALE"))
        assertEquals("рынок закрыт", chartFreshnessLabel("MARKET_CLOSED"))
        assertEquals("ожидание закрытия свечи", chartFreshnessLabel("WAITING_FOR_CANDLE_CLOSE"))
    }
}
