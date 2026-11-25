package ru.moex.tradinglab.data.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

data class SecuritiesListingEntry(
    val tradeDate: LocalDate,
    val tradeSessionDate: LocalDate,
    val boardId: String,
    val secId: String,
    val morningSession: Boolean?,
    val eveningSession: Boolean?,
    val weekendSession: Boolean?
)

data class ParsedSecuritiesListingSnapshot(
    val tradeDate: LocalDate,
    val entries: List<SecuritiesListingEntry>,
    val archiveMember: String,
    val declaredRecordCount: Int,
    val activeRecordCount: Int,
    val deletedRecordCount: Int,
    val duplicateRecordCount: Int,
    val unknownFlagCount: Int
)

/**
 * Defensive parser for the public MOEX SecuritiesListing ZIP.
 *
 * Business keys and session flags are ASCII. Russian labels are deliberately
 * not decoded here: the DBF code-page marker is zero and labels are not needed
 * for the snapshot identity or session-permission history.
 */
object SecuritiesListingDbfParser {
    private const val MAX_ARCHIVE_BYTES = 20 * 1024 * 1024
    private const val MAX_DBF_BYTES = 100L * 1024 * 1024
    private const val MAX_RECORDS = 500_000
    private val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun parseArchive(bytes: ByteArray): ParsedSecuritiesListingSnapshot {
        require(bytes.size in 4..MAX_ARCHIVE_BYTES) { "SecuritiesListing ZIP size is outside the allowed range" }
        require(bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            "SecuritiesListing response is not a ZIP archive"
        }

        var parsed: ParsedSecuritiesListingSnapshot? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/').endsWith(".dbf", ignoreCase = true)) {
                    if (parsed != null) {
                        throw IllegalArgumentException("SecuritiesListing ZIP contains more than one DBF file")
                    }
                    parsed = parseDbf(zip, entry.name.substringAfterLast('/'))
                }
                zip.closeEntry()
            }
        }
        return parsed ?: throw IllegalArgumentException("SecuritiesListing ZIP does not contain a DBF file")
    }

    private fun parseDbf(input: InputStream, archiveMember: String): ParsedSecuritiesListingSnapshot {
        val fixedHeader = input.readExactly(32)
        val declaredRecords = fixedHeader.uint32(4)
        val headerLength = fixedHeader.uint16(8)
        val recordLength = fixedHeader.uint16(10)

        require(declaredRecords in 1..MAX_RECORDS) { "DBF record count is outside the allowed range" }
        require(headerLength in 33..8192) { "Invalid DBF header length" }
        require(recordLength in 2..4096) { "Invalid DBF record length" }

        val fullHeader = fixedHeader + input.readExactly(headerLength - fixedHeader.size)
        val fields = linkedMapOf<String, Field>()
        var descriptorOffset = 32
        var rowOffset = 1
        var foundTerminator = false

        while (descriptorOffset < headerLength) {
            if (fullHeader[descriptorOffset] == 0x0D.toByte()) {
                foundTerminator = true
                break
            }
            require(descriptorOffset + 32 <= headerLength) { "Truncated DBF field descriptor" }
            val descriptor = fullHeader.copyOfRange(descriptorOffset, descriptorOffset + 32)
            val name = descriptor.copyOfRange(0, 11)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .let { String(it, StandardCharsets.US_ASCII) }
                .trim()
            require(name.isNotBlank()) { "DBF contains a blank field name" }
            val type = descriptor[11].toInt().toChar()
            val length = descriptor[16].toInt() and 0xFF
            require(length > 0) { "DBF field '$name' has invalid width" }
            require(!fields.containsKey(name)) { "DBF contains duplicate field '$name'" }
            fields[name] = Field(type, length, rowOffset)
            rowOffset += length
            descriptorOffset += 32
        }

        require(foundTerminator) { "DBF field descriptor terminator is missing" }
        require(rowOffset == recordLength) { "DBF record length does not match field descriptors" }

        val required = mapOf(
            "TRADEDATE" to ('D' to 8),
            "TRDSESDATE" to ('D' to 8),
            "BOARDID" to ('C' to null),
            "SECURITYID" to ('C' to null),
            "MORNINGSES" to ('C' to 1),
            "EVENINGSES" to ('C' to 1),
            "WEEKENDSES" to ('C' to 1)
        )
        required.forEach { (name, expected) ->
            val field = fields[name] ?: throw IllegalArgumentException("Required DBF field '$name' is missing")
            require(field.type == expected.first) { "DBF field '$name' has an unexpected type" }
            expected.second?.let { width ->
                require(field.length == width) { "DBF field '$name' has an unexpected width" }
            }
        }

        val expectedBytes = headerLength.toLong() + declaredRecords.toLong() * recordLength.toLong()
        require(expectedBytes <= MAX_DBF_BYTES) { "DBF uncompressed size exceeds the safety limit" }

        val tradeDates = linkedSetOf<LocalDate>()
        val entriesByKey = linkedMapOf<EntryKey, SecuritiesListingEntry>()
        var activeCount = 0
        var deletedCount = 0
        var duplicateCount = 0
        var unknownFlagCount = 0

        repeat(declaredRecords) {
            val record = input.readExactly(recordLength)
            when (record[0].toInt().toChar()) {
                '*' -> {
                    deletedCount++
                    return@repeat
                }
                ' ' -> Unit
                else -> throw IllegalArgumentException("DBF contains an invalid record deletion marker")
            }

            activeCount++
            fun fieldValue(name: String): String {
                val field = fields.getValue(name)
                return record.copyOfRange(field.offset, field.offset + field.length)
                    .let { String(it, StandardCharsets.US_ASCII) }
                    .trim()
            }
            fun parseDate(name: String): LocalDate {
                val value = fieldValue(name)
                require(value.matches(Regex("\\d{8}"))) { "DBF field '$name' contains an invalid date" }
                return try { LocalDate.parse(value, dateFormatter) } catch (error: Exception) {
                    throw IllegalArgumentException("DBF field '$name' contains an invalid date", error)
                }
            }
            fun parseFlag(name: String): Boolean? = when (fieldValue(name)) {
                "Y" -> true
                "N" -> false
                else -> null
            }

            val tradeDate = parseDate("TRADEDATE")
            val tradeSessionDate = parseDate("TRDSESDATE")
            val boardId = fieldValue("BOARDID")
            val secId = fieldValue("SECURITYID")
            require(boardId.isNotBlank() && boardId.all { it.code in 0x21..0x7E }) {
                "DBF contains an invalid BOARDID"
            }
            require(secId.isNotBlank() && secId.all { it.code in 0x21..0x7E }) {
                "DBF contains an invalid SECURITYID"
            }

            val morning = parseFlag("MORNINGSES")
            val evening = parseFlag("EVENINGSES")
            val weekend = parseFlag("WEEKENDSES")
            if (morning == null) unknownFlagCount++
            if (evening == null) unknownFlagCount++
            if (weekend == null) unknownFlagCount++

            tradeDates += tradeDate
            val value = SecuritiesListingEntry(
                tradeDate, tradeSessionDate, boardId, secId, morning, evening, weekend
            )
            val key = EntryKey(tradeDate, tradeSessionDate, boardId, secId)
            val previous = entriesByKey.putIfAbsent(key, value)
            if (previous != null) {
                require(previous == value) { "Conflicting duplicate SecuritiesListing key: $key" }
                duplicateCount++
            }
        }

        require(tradeDates.size == 1) { "DBF contains more than one TRADEDATE" }
        require(activeCount + deletedCount == declaredRecords) { "DBF record count is inconsistent" }

        return ParsedSecuritiesListingSnapshot(
            tradeDate = tradeDates.single(),
            entries = entriesByKey.values.toList(),
            archiveMember = archiveMember,
            declaredRecordCount = declaredRecords,
            activeRecordCount = activeCount,
            deletedRecordCount = deletedCount,
            duplicateRecordCount = duplicateCount,
            unknownFlagCount = unknownFlagCount
        )
    }

    private data class Field(val type: Char, val length: Int, val offset: Int)
    private data class EntryKey(
        val tradeDate: LocalDate,
        val tradeSessionDate: LocalDate,
        val boardId: String,
        val secId: String
    )

    private fun ByteArray.uint16(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.uint32(offset: Int): Int {
        val value = (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
        require(value <= Int.MAX_VALUE) { "DBF record count is too large" }
        return value.toInt()
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        require(size >= 0) { "Invalid DBF read size" }
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(result, offset, size - offset)
            if (count < 0) throw IllegalArgumentException("DBF file is truncated")
            if (count == 0) continue
            offset += count
        }
        return result
    }
}
