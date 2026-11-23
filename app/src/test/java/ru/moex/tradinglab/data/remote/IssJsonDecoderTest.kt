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
