package ru.moex.tradinglab.data.remote

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class MoexCalendarRepositoryPaginationTest {
    private class FakeClient(
        private val pageProvider: (Int) -> List<Map<String, JsonElement?>>
    ) : MoexIssTableClient {
        val requestedStarts = mutableListOf<Int>()

        override suspend fun getTable(
            endpoint: String,
            query: Map<String, String>,
            tableName: String
        ): IssTable {
            assertEquals("calendars/stock/session", endpoint)
            assertEquals("session_schedule", tableName)
            val start = query.getValue("start").toInt()
            assertEquals("100", query.getValue("limit"))
            requestedStarts += start
            val rows = pageProvider(start)
            return IssTable(endpoint, rows.flatMap { it.keys }.distinct(), rows, 1L)
        }
    }

    private fun sessionRow(dayOffset: Int): Map<String, JsonElement?> {
        val date = LocalDate.of(2026, 1, 1).plusDays(dayOffset.toLong()).toString()
        return mapOf(
            "tradedate" to JsonPrimitive(date),
            "tradingsession" to JsonPrimitive("1"),
            "boardid" to JsonPrimitive("TQBR"),
            "secid" to JsonPrimitive("SBER"),
            "type" to JsonPrimitive("main"),
            "time_from" to JsonPrimitive("10:00:00"),
            "time_till" to JsonPrimitive("18:50:00")
        )
    }

    @Test fun readsEveryPageAndConfirmsCompletionAfterShortPage() = runBlocking {
        val all = (0 until 101).map(::sessionRow)
        val client = FakeClient { start -> all.drop(start).take(100) }
        val result = MoexCalendarRepository(client).loadSessionPeriods(
            CalendarMarket.SHARES,
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-31"),
            secId = "SBER",
            boardId = "TQBR"
        )

        assertEquals(101, result.size)
        assertEquals(listOf(0, 100, 101), client.requestedStarts)
    }

    @Test fun exactPageBoundaryRequiresAnEmptyConfirmationPage() = runBlocking {
        val all = (0 until 100).map(::sessionRow)
        val client = FakeClient { start -> all.drop(start).take(100) }
        val result = MoexCalendarRepository(client).loadSessionPeriods(
            CalendarMarket.SHARES,
            LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-12-31")
        )

        assertEquals(100, result.size)
        assertEquals(listOf(0, 100), client.requestedStarts)
    }

    @Test fun repeatedPageFailsInsteadOfClaimingCompleteSchedule() = runBlocking {
        val page = (0 until 100).map(::sessionRow)
        val client = FakeClient { page }
        assertThrows(MoexIssException.InvalidResponse::class.java) {
            runBlocking {
                MoexCalendarRepository(client).loadSessionPeriods(
                    CalendarMarket.SHARES,
                    LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-12-31")
                )
            }
        }
    }

    @Test fun malformedScheduleRowFailsClosed() = runBlocking {
        val malformed = mapOf(
            "tradedate" to JsonPrimitive("2026-01-01"),
            "type" to JsonPrimitive("main")
        )
        val client = FakeClient { listOf(malformed) }
        assertThrows(MoexIssException.InvalidResponse::class.java) {
            runBlocking {
                MoexCalendarRepository(client).loadSessionPeriods(
                    CalendarMarket.SHARES,
                    LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-01-02")
                )
            }
        }
    }
}
