package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "securities_listing_snapshots",
    indices = [Index(value = ["tradeDate", "fetchedAtEpochMillis"])]
)
data class SecuritiesListingSnapshotEntity(
    @PrimaryKey val snapshotId: String,
    val tradeDate: String,
    val source: String,
    val sourceUrl: String,
    val archiveMember: String,
    val sha256: String,
    val fetchedAtEpochMillis: Long,
    val declaredRecordCount: Int,
    val activeRecordCount: Int,
    val storedEntryCount: Int,
    val filteredOutEntryCount: Int,
    val deletedRecordCount: Int,
    val duplicateRecordCount: Int,
    val unknownFlagCount: Int,
    val status: String
)
