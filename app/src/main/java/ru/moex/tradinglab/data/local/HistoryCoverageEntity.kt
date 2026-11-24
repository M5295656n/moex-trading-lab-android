package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One record per instrument/interval/requested range. Pagination completion and
 * time-series continuity are tracked separately; COMPLETE never implies gap-free.
 */
@Entity(
    tableName = "history_coverage",
    indices = [Index(value = ["instrumentId", "intervalMinutes", "requestedFrom", "requestedTill"])]
)
data class HistoryCoverageEntity(
    @PrimaryKey val coverageId: String,
    val instrumentId: String,
    val intervalMinutes: Int,
    val source: String,
    val endpoint: String,
    val requestedFrom: String?,
    val requestedTill: String?,
    val firstCandleBegin: String?,
    val lastCandleBegin: String?,
    val fetchedAtEpochMillis: Long,
    val pageCount: Int,
    val pageSize: Int,
    val paginationStatus: String,
    val skippedRows: Int,
    val gapCount: Int?,
    val gapsJson: String?,
    val freshnessStatus: String
)
