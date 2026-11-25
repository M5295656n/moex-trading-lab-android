package ru.moex.tradinglab.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexIssDataRepositoryTest {
    @Test
    fun loadsAllPagesUntilEmptyPageAndSortsCandles() = runBlocking {
        val client = FakeIssClient(
            pageSize = 2,
            pages = listOf(
                listOf(candle("2026-01-02 10:01:00"), candle("2026-01-02 10:00:00")),
                listOf(candle("2026-01-02 10:02:00"))
            )
        )

        val result = MoexIssDataRepository(client).loadCandles(
            endpoint = "engines/stock/markets/shares/securities/SBER/candles",
            pageSize = 2
        )

        assertEquals(listOf(0, 2, 3), client.requestedStarts)
        assertEquals(
            listOf("2026-01-02 10:00:00", "2026-01-02 10:01:00", "2026-01-02 10:02:00"),
            result.candles.map { it.begin }
        )
        assertEquals(3, result.pageCount)
        assertEquals(HistoryCompleteness.COMPLETE, result.completeness)
        assertTrue(result.isComplete)
    }

    @Test
    fun catalogUsesActualRowCountWhenIssCapsRequestedPageSize() = runBlocking {
        val instruments = (0 until 250).map { index ->
            """{"SECID":"SEC$index","BOARDID":"TQBR","SHORTNAME":"Share $index"}"""
        }
        val client = FakeIssClient(
            pageSize = 100,
            pages = instruments.chunked(100)
        )

        val result = MoexIssDataRepository(client).loadCatalog(
            market = MoexMarket.SHARES,
            pageSize = 500
        )

        assertEquals(listOf(0, 100, 200, 250), client.requestedStarts)
        assertEquals(250, result.instruments.size)
        assertEquals(4, result.pageCount)
        assertEquals(HistoryCompleteness.COMPLETE, result.completeness)
    }

    @Test
    fun actualRowCountDrivesNextOffsetWhenIssCapsRequestedPageSize() = runBlocking {
        val serverPageSize = 100
        val allCandles = (0 until 250).map { index ->
            candle("2026-01-${(index + 1).toString().padStart(3, '0')} 00:00:00")
        }
        val pages = allCandles.chunked(serverPageSize)
        val client = FakeIssClient(pageSize = serverPageSize, pages = pages)

        val result = MoexIssDataRepository(client).loadCandles(
            endpoint = "candles",
            pageSize = 500
        )

        assertEquals(listOf(0, 100, 200, 250), client.requestedStarts)
        assertEquals(250, result.candles.size)
        assertEquals(4, result.pageCount)
        assertEquals(HistoryCompleteness.COMPLETE, result.completeness)
    }

    @Test
    fun repeatedTimestampIsUpdatedByLaterPageWithoutCreatingDuplicate() = runBlocking {
        val client = FakeIssClient(
            pageSize = 2,
            pages = listOf(
                listOf(candle("10:00", close = 10.0), candle("10:01", close = 11.0)),
                listOf(candle("10:01", close = 12.0), candle("10:02", close = 13.0)),
                emptyList()
            )
        )

        val result = MoexIssDataRepository(client).loadCandles("candles", pageSize = 2)

        assertEquals(3, result.candles.size)
        assertEquals(12.0, result.candles.first { it.begin == "10:01" }.close!!, 0.000001)
        assertEquals(HistoryCompleteness.COMPLETE, result.completeness)
    }

    @Test
    fun repeatedFullPageIsReportedAsPaginationStalled() = runBlocking {
        val repeatedPage = listOf(candle("10:00"), candle("10:01"))
        val client = FakeIssClient(pageSize = 2, pages = listOf(repeatedPage, repeatedPage))

        val result = MoexIssDataRepository(client).loadCandles("candles", pageSize = 2)

        assertEquals(listOf(0, 2), client.requestedStarts)
        assertEquals(2, result.candles.size)
        assertEquals(HistoryCompleteness.PAGINATION_STALLED, result.completeness)
        assertFalse(result.isComplete)
    }

    @Test
    fun maxPageLimitIsNotReportedAsComplete() = runBlocking {
        val client = FakeIssClient(
            pageSize = 2,
            pages = listOf(
                listOf(candle("10:00"), candle("10:01")),
                listOf(candle("10:02"), candle("10:03"))
            )
        )

        val result = MoexIssDataRepository(client).loadCandles(
            endpoint = "candles",
            pageSize = 2,
            maxPages = 2
        )

        assertEquals(2, result.pageCount)
        assertEquals(HistoryCompleteness.PAGE_LIMIT_REACHED, result.completeness)
        assertFalse(result.isComplete)
    }

    @Test
    fun rowsWithoutBeginAreCountedAsSkippedAndNullPricesStayNull() = runBlocking {
        val client = FakeIssClient(
            pageSize = 2,
            pages = listOf(listOf(candle("10:00", close = null), """{"open":10.0}"""))
        )

        val result = MoexIssDataRepository(client).loadCandles("candles", pageSize = 2)

        assertEquals(1, result.candles.size)
        assertEquals(null, result.candles.single().close)
        assertEquals(1, result.skippedRows)
        assertEquals(listOf(0, 2), client.requestedStarts)
        assertEquals(2, result.pageCount)
        assertEquals(HistoryCompleteness.COMPLETE, result.completeness)
    }

    private fun candle(begin: String, close: Double? = 10.5): String {
        val closeJson = close?.toString() ?: "null"
        return """{"begin":"$begin","open":10.0,"high":11.0,"low":9.0,"close":$closeJson,"volume":null}"""
    }

    private class FakeIssClient(
        private val pageSize: Int,
        private val pages: List<List<String>>
    ) : MoexIssTableClient {
        val requestedStarts = mutableListOf<Int>()

        override suspend fun getTable(
            endpoint: String,
            query: Map<String, String>,
            tableName: String
        ): IssTable {
            val start = query["start"]?.toInt() ?: 0
            requestedStarts += start
            val pageIndex = pages.indices.firstOrNull { index ->
                pages.take(index).sumOf { it.size } == start
            }
            val rows = pageIndex?.let { pages[it] }.orEmpty().map { json ->
                JsonParser.parseString(json).asJsonObject.entrySet().associate { it.key to it.value }
            }
            return IssTable(
                endpoint = endpoint,
                columns = rows.flatMap { it.keys }.distinct(),
                rows = rows,
                fetchedAtEpochMillis = 1_800_000_000_000L + start
            )
        }
    }
}
