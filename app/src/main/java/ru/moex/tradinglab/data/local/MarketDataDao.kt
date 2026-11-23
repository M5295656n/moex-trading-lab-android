package ru.moex.tradinglab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketDataDao {
    @Query("SELECT * FROM market_instruments ORDER BY shortName COLLATE NOCASE")
    fun observeInstruments(): Flow<List<MarketInstrumentEntity>>

    @Query("SELECT * FROM market_instruments WHERE instrumentId = :instrumentId LIMIT 1")
    suspend fun getInstrument(instrumentId: String): MarketInstrumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstruments(items: List<MarketInstrumentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandles(items: List<CandleEntity>)

    @Query("""
        SELECT * FROM candles
        WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes
        ORDER BY begin
    """)
    fun observeCandles(instrumentId: String, intervalMinutes: Int): Flow<List<CandleEntity>>

    @Query("""
        SELECT * FROM candles
        WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes
          AND begin >= :from AND begin <= :till
        ORDER BY begin
    """)
    suspend fun getCandles(
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String
    ): List<CandleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoverage(coverage: HistoryCoverageEntity)

    @Query("""
        SELECT * FROM history_coverage
        WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes
        ORDER BY fetchedAtEpochMillis DESC
    """)
    fun observeCoverage(
        instrumentId: String,
        intervalMinutes: Int
    ): Flow<List<HistoryCoverageEntity>>

    @Query("DELETE FROM candles WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes")
    suspend fun deleteCandles(instrumentId: String, intervalMinutes: Int)

    @Transaction
    suspend fun saveHistory(
        candles: List<CandleEntity>,
        coverage: HistoryCoverageEntity
    ) {
        require(candles.all { it.instrumentId == coverage.instrumentId }) {
            "All candles must belong to the coverage instrument"
        }
        require(candles.all { it.intervalMinutes == coverage.intervalMinutes }) {
            "All candles must use the coverage interval"
        }
        if (candles.isNotEmpty()) upsertCandles(candles)
        upsertCoverage(coverage)
    }
}
