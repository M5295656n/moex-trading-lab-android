package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "securities_listing_entries",
    primaryKeys = ["snapshotId", "tradeDate", "tradeSessionDate", "boardId", "secId"],
    foreignKeys = [
        ForeignKey(
            entity = SecuritiesListingSnapshotEntity::class,
            parentColumns = ["snapshotId"],
            childColumns = ["snapshotId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["snapshotId"]),
        Index(value = ["secId", "boardId", "tradeDate"])
    ]
)
data class SecuritiesListingEntryEntity(
    val snapshotId: String,
    val tradeDate: String,
    val tradeSessionDate: String,
    val boardId: String,
    val secId: String,
    val morningSession: Boolean?,
    val eveningSession: Boolean?,
    val weekendSession: Boolean?
)
