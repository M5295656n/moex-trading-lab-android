package ru.moex.tradinglab.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IssJsonDecoderTest {
    @Test
    fun decodesColumnsRowsAndNullValues() {
        val json = """
            {
              "iss": {
                "securities": {
                  "columns": ["SECID", "SHORTNAME", "ISIN"],
                  "data": [
                    ["SBER", "Сбербанк", "RU0009029540"],
                    ["TEST", "Тест", null]
                  ]
                }
              }
            }
        """.trimIndent()

        val table = IssJsonDecoder.decode(json, "engines/stock/markets/shares/securities")

        assertEquals(listOf("SECID", "SHORTNAME", "ISIN"), table.columns)
        assertEquals(2, table.rows.size)
        assertEquals("SBER", table.rows[0]["SECID"]?.asString)
        assertEquals("Сбербанк", table.rows[0]["SHORTNAME"]?.asString)
        assertNull(table.rows[1]["ISIN"])
    }

    @Test
    fun decodesRealExtendedCatalogEnvelopeWithObjectRows() {
        val json = """
            [
              {"charsetinfo":{"name":"utf-8"}},
              {
                "securities":[
                  {"SECID":"SBER","SHORTNAME":"Сбербанк","ISIN":"RU0009029540"},
                  {"SECID":"TEST","SHORTNAME":"Тест","ISIN":null}
                ],
                "marketdata":[]
              }
            ]
        """.trimIndent()

        val table = IssJsonDecoder.decode(
            json,
            "engines/stock/markets/shares/securities",
            fetchedAtEpochMillis = 123L
        )

        assertEquals(listOf("SECID", "SHORTNAME", "ISIN"), table.columns)
        assertEquals(2, table.rows.size)
        assertEquals("SBER", table.rows[0]["SECID"]?.asString)
        assertEquals("Сбербанк", table.rows[0]["SHORTNAME"]?.asString)
        assertNull(table.rows[1]["ISIN"])
        assertEquals(123L, table.fetchedAtEpochMillis)
    }

    @Test
    fun decodesEmptyExtendedCandlesTable() {
        val json = """
            [
              {"charsetinfo":{"name":"utf-8"}},
              {"candles":[]}
            ]
        """.trimIndent()

        val table = IssJsonDecoder.decode(
            json,
            "engines/stock/markets/shares/securities/SBER/candles"
        )

        assertEquals(emptyList<String>(), table.columns)
        assertEquals(0, table.rows.size)
    }

    @Test
    fun decodesExtendedCandleRowsAndKeepsNumericValues() {
        val json = """
            [
              {"charsetinfo":{"name":"utf-8"}},
              {
                "candles":[
                  {"open":299.25,"close":295.53,"high":299.62,"low":294.41,
                   "value":6856319297.31,"volume":23139857,
                   "begin":"2026-11-16 00:00:00","end":"2026-11-16 23:59:51"}
                ]
              }
            ]
        """.trimIndent()

        val table = IssJsonDecoder.decode(
            json,
            "engines/stock/markets/shares/securities/SBER/candles"
        )

        assertEquals(1, table.rows.size)
        assertEquals(299.25, table.rows[0]["open"]?.asDouble!!, 0.000001)
        assertEquals(23139857, table.rows[0]["volume"]?.asInt)
        assertEquals("2026-11-16 00:00:00", table.rows[0]["begin"]?.asString)
    }

    @Test
    fun rejectsMissingTable() {
        val json = """{"iss":{"marketdata":{"columns":[],"data":[]}}}"""
        assertThrows(MoexIssException.InvalidResponse::class.java) {
            IssJsonDecoder.decode(json, "engines/stock/markets/shares/securities")
        }
    }

    @Test
    fun rejectsMismatchedRowWidth() {
        val json = """
            {"iss":{"securities":{"columns":["SECID","SHORTNAME"],"data":[["SBER"]]}}}
        """.trimIndent()
        assertThrows(MoexIssException.InvalidResponse::class.java) {
            IssJsonDecoder.decode(json, "engines/stock/markets/shares/securities")
        }
    }
}
