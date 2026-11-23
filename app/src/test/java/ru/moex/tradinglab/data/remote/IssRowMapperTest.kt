package ru.moex.tradinglab.data.remote

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class IssRowMapperTest {
    @Test
    fun mapsShareInstrumentWithoutLosingExchangeFields() {
        val json = JsonParser.parseString(
            """{"SECID":"SBER","SHORTNAME":"Сбербанк","SECNAME":"ПАО Сбербанк","ISIN":"RU0009029540","BOARDID":"TQBR","LOTSIZE":10,"CURRENCYID":"RUB"}"""
        ).asJsonObject
        val row = json.entrySet().associate { it.key to it.value }

        val instrument = IssRowMapper.instrument(row, MoexMarket.SHARES)

        assertNotNull(instrument)
        assertEquals("SBER", instrument?.secId)
        assertEquals("ПАО Сбербанк", instrument?.name)
        assertEquals("RU0009029540", instrument?.isin)
        assertEquals(10.0, instrument?.lotSize!!, 0.000001)
        assertEquals("RUB", instrument?.currency)
        assertEquals("10", instrument?.rawFields?.get("LOTSIZE"))
    }

    @Test
    fun skipsCatalogRowsWithoutSecurityId() {
        val row = mapOf("SHORTNAME" to JsonParser.parseString("\\"Без SECID\\""))
        assertNull(IssRowMapper.instrument(row, MoexMarket.SHARES))
    }

    @Test
    fun preservesMissingCandleValuesAsNull() {
        val json = JsonParser.parseString(
            """{"begin":"2026-11-20 10:00:00","open":100.5,"high":null,"low":99.0,"close":null,"volume":null}"""
        ).asJsonObject
        val row = json.entrySet().associate { it.key to it.value }

        val candle = IssRowMapper.candle(row)

        assertNotNull(candle)
        assertEquals(100.5, candle?.open!!, 0.000001)
        assertEquals(99.0, candle.low!!, 0.000001)
        assertNull(candle.high)
        assertNull(candle.close)
        assertNull(candle.volume)
    }

    @Test
    fun skipsCandlesWithoutBeginTimestamp() {
        val row = mapOf("open" to JsonParser.parseString("10.0"))
        assertNull(IssRowMapper.candle(row))
    }
}
