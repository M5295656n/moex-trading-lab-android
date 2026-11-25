package ru.moex.tradinglab.data.remote

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MoexFuturesSpecificationRepositoryTest {
    private val asOf = LocalDate.of(2026, 11, 25)
    private val docDate = "2026-11-25 10:30:00"

    @Test
    fun mapsOfficialMoexFieldsAndUsesConservativeMargin() {
        val result = FuturesSpecificationMapper.map(
            secId = "SiZ6",
            asOfDate = asOf,
            securitiesRows = listOf(
                row(
                    "TICKER" to "SiZ6",
                    "SHORT_TICKER" to "Si-12.26",
                    "MIN_STEP" to "1",
                    "MIN_STEP_PRICE" to "1",
                    "END_DATE" to "2026-12-17",
                    "EXP_DATE" to "2026-12-18",
                    "DOC_DATE" to docDate,
                    "EFFECTIVE_DATE" to "2026-11-25"
                )
            ),
            paramsRows = listOf(
                row(
                    "CONTRACT" to "SiZ6",
                    "TICK" to "1",
                    "TICK_PRICE" to "1",
                    "DEPOSIT" to "15000",
                    "DOC_DATE" to docDate,
                    "EFFECTIVE_DATE" to "2026-11-25"
                )
            ),
            risksRows = listOf(
                row(
                    "TICKER" to "SiZ6",
                    "MARGIN" to "15200",
                    "EXP_DATE" to "2026-12-18",
                    "DOC_DATE" to docDate,
                    "EFFECTIVE_DATE" to "2026-11-25"
                )
            ),
            receivedAtEpochMillis = 1_795_600_000_000L
        )

        assertTrue(result is FuturesSpecificationLoadResult.Ready)
        val loaded = (result as FuturesSpecificationLoadResult.Ready).value
        assertEquals(1.0, loaded.specification.tickSize, 0.0000001)
        assertEquals(1.0, loaded.specification.tickValue, 0.0000001)
        assertEquals(15_200.0, loaded.specification.initialMarginPerContract, 0.0000001)
        assertEquals("2026-12-17", loaded.specification.expirationDate)
        assertEquals("max(DEPOSIT, MARGIN)", loaded.marginSource)
        assertEquals(3, loaded.sourceDocumentTimes.size)
    }

    @Test
    fun rejectsConflictingTickValuesInsteadOfChoosingOne() {
        val result = FuturesSpecificationMapper.map(
            secId = "SiZ6",
            asOfDate = asOf,
            securitiesRows = listOf(securityRow()),
            paramsRows = listOf(paramsRow(tick = "0.5")),
            risksRows = listOf(riskRow())
        )
        assertTrue(result is FuturesSpecificationLoadResult.Rejected)
        assertTrue((result as FuturesSpecificationLoadResult.Rejected).reason.contains("шаг цены"))
    }

    @Test
    fun rejectsMissingSourceDocumentTimestamp() {
        val result = FuturesSpecificationMapper.map(
            secId = "SiZ6",
            asOfDate = asOf,
            securitiesRows = listOf(securityRow()),
            paramsRows = listOf(paramsRow().toMutableMap().apply { remove("DOC_DATE") }),
            risksRows = listOf(riskRow())
        )
        assertTrue(result is FuturesSpecificationLoadResult.Rejected)
        assertTrue((result as FuturesSpecificationLoadResult.Rejected).reason.contains("DOC_DATE"))
    }

    @Test
    fun rejectsFutureEffectiveDate() {
        val result = FuturesSpecificationMapper.map(
            secId = "SiZ6",
            asOfDate = asOf,
            securitiesRows = listOf(securityRow()),
            paramsRows = listOf(paramsRow().toMutableMap().apply {
                this["EFFECTIVE_DATE"] = "2026-11-26"
            }),
            risksRows = listOf(riskRow())
        )
        assertTrue(result is FuturesSpecificationLoadResult.Rejected)
        assertTrue((result as FuturesSpecificationLoadResult.Rejected).reason.contains("вступают в силу"))
    }

    @Test
    fun rejectsAmbiguousContractJoin() {
        val result = FuturesSpecificationMapper.map(
            secId = "SiZ6",
            asOfDate = asOf,
            securitiesRows = listOf(securityRow()),
            paramsRows = listOf(paramsRow(), paramsRow().toMutableMap().apply {
                this["TICK_PRICE"] = "2"
            }),
            risksRows = listOf(riskRow())
        )
        assertTrue(result is FuturesSpecificationLoadResult.Rejected)
        assertTrue((result as FuturesSpecificationLoadResult.Rejected).reason.contains("однозначной"))
    }

    @Test
    fun rejectsExpiredContractUsingLastTradingDate() {
        val result = FuturesSpecificationMapper.map(
            secId = "SiZ6",
            asOfDate = LocalDate.of(2026, 12, 18),
            securitiesRows = listOf(securityRow().toMutableMap().apply {
                this["END_DATE"] = "2026-12-17"
            }),
            paramsRows = listOf(paramsRow()),
            risksRows = listOf(riskRow())
        )
        assertTrue(result is FuturesSpecificationLoadResult.Rejected)
        assertTrue((result as FuturesSpecificationLoadResult.Rejected).reason.contains("завершил торги"))
    }

    @Test
    fun repositoryRequestsAllThreeTablesForSelectedDate() = runBlocking {
        val client = FuturesTablesFake()
        val result = MoexFuturesSpecificationRepository(client).load("SiZ6", asOf)

        assertTrue(result is FuturesSpecificationLoadResult.Ready)
        assertEquals(
            listOf(
                MoexFuturesSpecificationRepository.SECURITIES_ENDPOINT,
                MoexFuturesSpecificationRepository.PARAMS_ENDPOINT,
                MoexFuturesSpecificationRepository.RISKS_ENDPOINT
            ),
            client.calls.map { it.first }
        )
        assertTrue(client.calls.all { it.second["date"] == asOf.toString() })
        assertTrue(client.calls.all { it.second["limit"] == "1000" })
    }

    @Test
    fun repositoryRejectsIncompletePagination() = runBlocking {
        val client = FuturesTablesFake()
        val result = MoexFuturesSpecificationRepository(client).load(
            secId = "SiZ6",
            asOfDate = asOf,
            pageSize = 1,
            maxPages = 1
        )
        assertTrue(result is FuturesSpecificationLoadResult.Rejected)
        assertTrue((result as FuturesSpecificationLoadResult.Rejected).reason.contains("не полностью"))
    }

    private fun securityRow(): Map<String, String?> = row(
        "TICKER" to "SiZ6",
        "SHORT_TICKER" to "Si-12.26",
        "MIN_STEP" to "1",
        "MIN_STEP_PRICE" to "1",
        "END_DATE" to "2026-12-17",
        "EXP_DATE" to "2026-12-18",
        "DOC_DATE" to docDate,
        "EFFECTIVE_DATE" to "2026-11-25"
    )

    private fun paramsRow(tick: String = "1"): Map<String, String?> = row(
        "CONTRACT" to "SiZ6",
        "TICK" to tick,
        "TICK_PRICE" to "1",
        "DEPOSIT" to "15000",
        "DOC_DATE" to docDate,
        "EFFECTIVE_DATE" to "2026-11-25"
    )

    private fun riskRow(): Map<String, String?> = row(
        "TICKER" to "SiZ6",
        "MARGIN" to "15200",
        "EXP_DATE" to "2026-12-18",
        "DOC_DATE" to docDate,
        "EFFECTIVE_DATE" to "2026-11-25"
    )

    private fun row(vararg values: Pair<String, String?>): Map<String, String?> = values.toMap()

    private class FuturesTablesFake : MoexIssTableClient {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()

        override suspend fun getTable(
            endpoint: String,
            query: Map<String, String>,
            tableName: String
        ): IssTable {
            calls += endpoint to query
            val values = when (tableName) {
                "securities" -> listOf(
                    """{"TICKER":"SiZ6","SHORT_TICKER":"Si-12.26","MIN_STEP":1,"MIN_STEP_PRICE":1,"END_DATE":"2026-12-17","EXP_DATE":"2026-12-18","DOC_DATE":"$docDate","EFFECTIVE_DATE":"2026-11-25"}"""
                )
                "params" -> listOf(
                    """{"CONTRACT":"SiZ6","TICK":1,"TICK_PRICE":1,"DEPOSIT":15000,"DOC_DATE":"$docDate","EFFECTIVE_DATE":"2026-11-25"}"""
                )
                "risks" -> listOf(
                    """{"TICKER":"SiZ6","MARGIN":15200,"EXP_DATE":"2026-12-18","DOC_DATE":"$docDate","EFFECTIVE_DATE":"2026-11-25"}"""
                )
                else -> emptyList()
            }
            val rows = values.map { json ->
                JsonParser.parseString(json).asJsonObject.entrySet().associate { it.key to it.value }
            }
            return IssTable(
                endpoint = endpoint,
                columns = rows.flatMap { it.keys }.distinct(),
                rows = rows,
                fetchedAtEpochMillis = 1_795_600_000_000L
            )
        }
    }
}
