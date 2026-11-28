package ru.moex.tradinglab.data.sync

import ru.moex.tradinglab.data.local.MarketDataDao
import ru.moex.tradinglab.data.local.SecuritiesListingDao
import ru.moex.tradinglab.data.local.SecuritiesListingEntryEntity
import ru.moex.tradinglab.data.local.SecuritiesListingSnapshotEntity
import ru.moex.tradinglab.data.remote.SecuritiesListingArchiveSource
import ru.moex.tradinglab.data.remote.SecuritiesListingDbfParser

sealed interface SecuritiesListingSyncResult {
    data class Saved(
        val tradeDate: String,
        val storedEntries: Int,
        val filteredOutEntries: Int,
        val declaredRecords: Int,
        val status: String
    ) : SecuritiesListingSyncResult

    data object NoShareCatalog : SecuritiesListingSyncResult
}

/**
 * Stores only stock SECID/BOARDID pairs already present in the local MOEX catalog.
 * The source is a daily permission snapshot, never a candle schedule.
 */
class SecuritiesListingSync(
    private val archiveSource: SecuritiesListingArchiveSource,
    private val marketDataDao: MarketDataDao,
    private val securitiesListingDao: SecuritiesListingDao
) {
    suspend fun syncLatestSnapshot(): SecuritiesListingSyncResult {
        val localKeys = marketDataDao.getShareInstrumentBoardKeys()
            .map { it.secId to it.boardId }
            .toSet()
        if (localKeys.isEmpty()) return SecuritiesListingSyncResult.NoShareCatalog

        val archive = archiveSource.download()
        val parsed = SecuritiesListingDbfParser.parseArchive(archive.bytes)
        val selected = parsed.entries.filter { (it.secId to it.boardId) in localKeys }
        val status = when {
            selected.isEmpty() -> "NO_LOCAL_MATCHES"
            parsed.unknownFlagCount > 0 -> "PARTIAL_FLAGS"
            parsed.duplicateRecordCount > 0 -> "COMPLETE_WITH_DUPLICATES"
            else -> "COMPLETE"
        }
        val snapshot = SecuritiesListingSnapshotEntity(
            snapshotId = archive.sha256,
            tradeDate = parsed.tradeDate.toString(),
            source = "MOEX ISS",
            sourceUrl = archive.sourceUrl,
            archiveMember = parsed.archiveMember,
            sha256 = archive.sha256,
            fetchedAtEpochMillis = archive.fetchedAtEpochMillis,
            declaredRecordCount = parsed.declaredRecordCount,
            activeRecordCount = parsed.activeRecordCount,
            storedEntryCount = selected.size,
            filteredOutEntryCount = parsed.entries.size - selected.size,
            deletedRecordCount = parsed.deletedRecordCount,
            duplicateRecordCount = parsed.duplicateRecordCount,
            unknownFlagCount = parsed.unknownFlagCount,
            status = status
        )
        val entries = selected.map { item ->
            SecuritiesListingEntryEntity(
                snapshotId = archive.sha256,
                tradeDate = item.tradeDate.toString(),
                tradeSessionDate = item.tradeSessionDate.toString(),
                boardId = item.boardId,
                secId = item.secId,
                morningSession = item.morningSession,
                eveningSession = item.eveningSession,
                weekendSession = item.weekendSession
            )
        }
        securitiesListingDao.saveSnapshot(snapshot, entries)
        return SecuritiesListingSyncResult.Saved(
            tradeDate = snapshot.tradeDate,
            storedEntries = snapshot.storedEntryCount,
            filteredOutEntries = snapshot.filteredOutEntryCount,
            declaredRecords = snapshot.declaredRecordCount,
            status = snapshot.status
        )
    }
}
