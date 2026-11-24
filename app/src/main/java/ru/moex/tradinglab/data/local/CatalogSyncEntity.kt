package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Latest durable result of loading one MOEX market catalog. */
@Entity(tableName = "catalog_sync")
data class CatalogSyncEntity(
    @PrimaryKey val market: String,
    val source: String,
    val endpoint: String,
    val fetchedAtEpochMillis: Long,
    val instrumentCount: Int,
    val pageCount: Int,
    val pageSize: Int,
    val completeness: String,
    val skippedRows: Int
)
