package ru.moex.tradinglab.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "instruments")
data class InstrumentEntity(
    @PrimaryKey
    val secId: String,
    val shortName: String,
    val instrumentType: String,
    val engine: String,
    val market: String,
    val source: String = "MOEX ISS",
    val updatedAtEpochMillis: Long
)
