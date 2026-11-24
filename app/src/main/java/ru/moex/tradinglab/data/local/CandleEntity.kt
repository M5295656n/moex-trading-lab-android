package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "candles",
    primaryKeys = ["instrumentId", "intervalMinutes", "begin"],
    foreignKeys = [
        ForeignKey(
            entity = MarketInstrumentEntity::class,
            parentColumns = ["instrumentId"],
            childColumns = ["instrumentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["instrumentId", "intervalMinutes", "begin"])]
)
data class CandleEntity(
    val instrumentId: String,
    val intervalMinutes: Int,
    val begin: String,
    val end: String?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val close: Double?,
    val value: Double?,
    val volume: Double?,
    val source: String,
    val fetchedAtEpochMillis: Long
)
