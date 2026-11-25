package ru.moex.tradinglab.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoexPublicFuturesCatalogRepositoryTest {
    @Test
    fun mapsOnlyFieldsActuallyPublishedByPublicCatalog() = runBlocking {
        val client = PublicCatalogFake(
            rows = listOf(
                """{"SECID":"SiZ6","SHORTNAME":"Si-12.26","SECNAME":"Фьючерсный контракт Si","MINSTEP":1,"DECIMALS":0,"PREVSETTLEPRICE":81234,"LASTTRADEDATE":"2026-12-17","LASTDELDATE":"2026-12-18"}"""
            )
        )

        val result = MoexPublicFuturesCatalogRepository(client).load("SiZ6")

        assertTrue(result is PublicFuturesMetadataResult.Ready)
        val metadata = (result as PublicFuturesMetadataResult.Ready).metadata
        assertEquals("SiZ6", metadata.secId)
        assertEquals("Si-12.26", metadata.shortName)
        assertEquals(1.0, metadata.minStep!!, 0.0000001)
        assertEquals(0, metadata.decimals)
        assertEquals(81234.0, metadata.previousSettlementPrice!!, 0.0000001)
        assertEquals("2026-12-17", metadata.lastTradeDate.toString())
        assertEquals("2026-12-18", metadata.lastDeliveryDate.toString())
        assertTrue(metadata.source.contains("public"))
        assertEquals(MoexPublicFuturesCatalogRepository.ENDPOINT, client.calls.single().endpoint)
        assertEquals("securities", client.calls.single().tableName)
    }

    @Test
    fun doesNotInventTickValueOrMarginWhenPublicCatalogOmitsThem() = runBlocking {
        val client = PublicCatalogFake(
            rows = listOf("""{"SECID":"SiZ6","MINSTEP":1}""")
        )

        val result = MoexPublicFuturesCatalogRepository(client).load("SiZ6")

        assertTrue(result is PublicFuturesMetadataResult.Ready)
        val metadata = (result as PublicFuturesMetadataResult.Ready).metadata
        assertEquals(1.0, metadata.minStep!!, 0.0000001)
        // Public catalog model intentionally has no tick-value or margin fields.
        assertNull(metadata.lastTradeDate)
        assertNull(metadata.lastDeliveryDate)
    }

    @Test
    fun rejectsDifferentDuplicateRowsForSameContract() = runBlocking {
        val client = PublicCatalogFake(
            rows = listOf(
                """{"SECID":"SiZ6","MINSTEP":1,"LASTTRADEDATE":"2026-12-17"}""",
                """{"SECID":"SiZ6","MINSTEP":2,"LASTTRADEDATE":"2026-12-17"}"""
            )
        )

        val result = MoexPublicFuturesCatalogRepository(client).load("SiZ6", pageSize = 2)

        assertTrue(result is PublicFuturesMetadataResult.Rejected)
        assertTrue((result as PublicFuturesMetadataResult.Rejected).reason.contains("несколько разных"))
    }

    @Test
    fun rejectsIncompleteCatalogPagination() = runBlocking {
        val client = PublicCatalogFake(
            rows = listOf("""{"SECID":"SiZ6","MINSTEP":1}""")
        )

        val result = MoexPublicFuturesCatalogRepository(client).load(
            secId = "SiZ6",
            pageSize = 1,
            maxPages = 1
        )

        assertTrue(result is PublicFuturesMetadataResult.Rejected)
        assertTrue((result as PublicFuturesMetadataResult.Rejected).reason.contains("не полностью"))
    }

    @Test
    fun rejectsMissingContract() = runBlocking {
        val result = MoexPublicFuturesCatalogRepository(
            PublicCatalogFake(rows = listOf("""{"SECID":"RIH7","MINSTEP":1}"""))
        ).load("SiZ6")

        assertTrue(result is PublicFuturesMetadataResult.Rejected)
        assertTrue((result as PublicFuturesMetadataResult.Rejected).reason.contains("не найден"))
    }

    private class PublicCatalogFake(
        private val rows: List<String>
    ) : MoexIssTableClient {
        data class Call(val endpoint: String, val query: Map<String, String>, val tableName: String)
        val calls = mutableListOf<Call>()

        override suspend fun getTable(
            endpoint: String,
            query: Map<String, String>,
            tableName: String
        ): IssTable {
            calls += Call(endpoint, query, tableName)
            val start = query["start"]!!.toInt()
            val limit = query["limit"]!!.toInt()
            val page = rows.drop(start).take(limit).map { json ->
                JsonParser.parseString(json).asJsonObject.entrySet().associate { it.key to it.value }
            }
            return IssTable(
                endpoint = endpoint,
                columns = page.flatMap { it.keys }.distinct(),
                rows = page,
                fetchedAtEpochMillis = 1_795_600_000_000L
            )
        }
    }
}
