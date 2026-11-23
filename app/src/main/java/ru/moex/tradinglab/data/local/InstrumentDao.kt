package ru.moex.tradinglab.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface InstrumentDao {
    @Query("SELECT * FROM instruments ORDER BY shortName COLLATE NOCASE")
    fun observeAll(): Flow<List<InstrumentEntity>>

    @Upsert
    suspend fun upsertAll(instruments: List<InstrumentEntity>)

    @Query("DELETE FROM instruments WHERE secId = :secId")
    suspend fun deleteBySecId(secId: String)
}
