package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical instrument identity. Unlike the legacy instruments table, identity
 * includes market and board so identical SECIDs on different boards do not collide.
 */
@Entity(
    tableName = "market_instruments",
    indices = [
        Index(value = ["secId", "market", "boardId"], unique = true),
        Index(value = ["market", "shortName"])
    ]
)
data class MarketInstrumentEntity(
    @PrimaryKey val instrumentId: String,
    val secId: String,
    val name: String,
    val shortName: String?,
    val isin: String?,
    val boardId: String?,
    val lotSize: Double?,
    val currency: String?,
    val market: String,
    val source: String,
    val rawFieldsJson: String?,
    val fetchedAtEpochMillis: Long
)
