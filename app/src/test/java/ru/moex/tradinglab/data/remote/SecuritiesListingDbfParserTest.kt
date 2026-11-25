package ru.moex.tradinglab.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SecuritiesListingDbfParserTest {
    @Test
    fun parsesFieldsByNameAndKeepsSessionFlagsSeparate() {
        val parsed = SecuritiesListingDbfParser.parseArchive(archive(listOf(
            row("SBER", "TQBR", "Y", "Y", "Y"),
            row("GAZP", "TQBR", "N", "Y", "N")
        )))
        assertEquals(2, parsed.declaredRecordCount)
        assertEquals(2, parsed.activeRecordCount)
        assertEquals("SL251126.dbf", parsed.archiveMember)
        assertEquals("SBER", parsed.entries[0].secId)
        assertTrue(parsed.entries[0].morningSession == true)
        assertTrue(parsed.entries[0].eveningSession == true)
        assertTrue(parsed.entries[0].weekendSession == true)
        assertFalse(parsed.entries[1].morningSession!!)
        assertTrue(parsed.entries[1].eveningSession!!)
        assertFalse(parsed.entries[1].weekendSession!!)
    }

    @Test
    fun ignoresUnknownFieldsAndDoesNotDependOnColumnOrder() {
        val parsed = SecuritiesListingDbfParser.parseArchive(archive(listOf(
            row("LKOH", "TQBR", "Y", "N", "Y")
        ), reorder = true))
        assertEquals("LKOH", parsed.entries.single().secId)
    }

    @Test
    fun preservesUnknownFlagAsNullInsteadOfTreatingItAsNo() {
        val parsed = SecuritiesListingDbfParser.parseArchive(archive(listOf(
            row("SBER", "TQBR", "", "Y", "N")
        )))
        assertNull(parsed.entries.single().morningSession)
        assertEquals(1, parsed.unknownFlagCount)
    }

    @Test
    fun countsIdenticalDuplicateKeysButDoesNotDuplicateEntry() {
        val item = row("SBER", "TQBR", "Y", "Y", "Y")
        val parsed = SecuritiesListingDbfParser.parseArchive(archive(listOf(item, item)))
        assertEquals(2, parsed.activeRecordCount)
        assertEquals(1, parsed.duplicateRecordCount)
        assertEquals(1, parsed.entries.size)
    }

    @Test
    fun rejectsConflictingDuplicateKeys() {
        expectFailure {
            SecuritiesListingDbfParser.parseArchive(archive(listOf(
                row("SBER", "TQBR", "Y", "Y", "Y"),
                row("SBER", "TQBR", "N", "Y", "Y")
            )))
        }
    }

    @Test
    fun rejectsMissingRequiredField() {
        expectFailure {
            SecuritiesListingDbfParser.parseArchive(archive(listOf(row("SBER", "TQBR", "Y", "Y", "Y")), omitWeekend = true))
        }
    }

    @Test
    fun rejectsNonZipResponse() {
        expectFailure { SecuritiesListingDbfParser.parseArchive("not a zip".toByteArray()) }
    }

    @Test
    fun rejectsTruncatedDbfInsideZip() {
        val bytes = archive(listOf(row("SBER", "TQBR", "Y", "Y", "Y"))).dropLast(12).toByteArray()
        expectFailure { SecuritiesListingDbfParser.parseArchive(bytes) }
    }

    private data class TestRow(
        val tradeDate: String,
        val tradeSessionDate: String,
        val boardId: String,
        val secId: String,
        val morning: String,
        val evening: String,
        val weekend: String
    )

    private fun row(secId: String, boardId: String, morning: String, evening: String, weekend: String) =
        TestRow("20261125", "20261125", boardId, secId, morning, evening, weekend)

    private fun archive(rows: List<TestRow>, reorder: Boolean = false, omitWeekend: Boolean = false): ByteArray {
        val fields = buildList {
            add(FieldDef("TRADEDATE", 'D', 8))
            add(FieldDef("TRDSESDATE", 'D', 8))
            add(FieldDef("BOARDID", 'C', 4))
            add(FieldDef("SECURITYID", 'C', 12))
            if (!omitWeekend) add(FieldDef("WEEKENDSES", 'C', 1))
            add(FieldDef("MORNINGSES", 'C', 1))
            add(FieldDef("EVENINGSES", 'C', 1))
            add(FieldDef("EXTRA_FIELD", 'C', 2))
        }.let { if (reorder) it.reversed() else it }

        val recordLength = 1 + fields.sumOf(FieldDef::length)
        val headerLength = 32 + fields.size * 32 + 1
        val dbf = ByteArrayOutputStream()
        val header = ByteArray(32)
        header[0] = 0x03
        putInt(header, 4, rows.size)
        putShort(header, 8, headerLength)
        putShort(header, 10, recordLength)
        dbf.write(header)
        fields.forEach { field ->
            val descriptor = ByteArray(32)
            val name = field.name.toByteArray(StandardCharsets.US_ASCII)
            name.copyInto(descriptor, 0, 0, minOf(name.size, 11))
            descriptor[11] = field.type.code.toByte()
            descriptor[16] = field.length.toByte()
            dbf.write(descriptor)
        }
        dbf.write(0x0D)
        rows.forEach { item ->
            dbf.write(0x20)
            fields.forEach { field ->
                val value = when (field.name) {
                    "TRADEDATE" -> item.tradeDate
                    "TRDSESDATE" -> item.tradeSessionDate
                    "BOARDID" -> item.boardId
                    "SECURITYID" -> item.secId
                    "MORNINGSES" -> item.morning
                    "EVENINGSES" -> item.evening
                    "WEEKENDSES" -> item.weekend
                    else -> "X"
                }
                dbf.write(value.padEnd(field.length).take(field.length).toByteArray(StandardCharsets.US_ASCII))
            }
        }
        dbf.write(0x1A)

        val zipBytes = ByteArrayOutputStream()
        ZipOutputStream(zipBytes).use { zip ->
            zip.putNextEntry(ZipEntry("SL251126.dbf"))
            zip.write(dbf.toByteArray())
            zip.closeEntry()
        }
        return zipBytes.toByteArray()
    }

    private data class FieldDef(val name: String, val type: Char, val length: Int)

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> target[offset + index] = (value ushr (8 * index)).toByte() }
    }

    private fun putShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected parser to reject invalid archive")
        } catch (_: Exception) {
            // expected
        }
    }
}
