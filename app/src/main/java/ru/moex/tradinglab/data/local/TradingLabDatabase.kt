package ru.moex.tradinglab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [InstrumentEntity::class],
    version = 1,
    exportSchema = true
)
abstract class TradingLabDatabase : RoomDatabase() {
    abstract fun instrumentDao(): InstrumentDao
}
