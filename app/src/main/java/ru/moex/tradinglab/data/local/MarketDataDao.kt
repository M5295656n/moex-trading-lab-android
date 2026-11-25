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

    @Query("SELECT * FROM catalog_sync ORDER BY market")
    fun observeCatalogSync(): Flow<List<CatalogSyncEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCatalogSync(item: CatalogSyncEntity)

    @Transaction
    suspend fun saveCatalog(items: List<MarketInstrumentEntity>, sync: CatalogSyncEntity) {
        upsertInstruments(items)
        upsertCatalogSync(sync)
    }

    @Query("SELECT * FROM market_instruments WHERE instrumentId = :instrumentId LIMIT 1")
    suspend fun getInstrument(instrumentId: String): MarketInstrumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInstrumentsIfAbsent(items: List<MarketInstrumentEntity>)

    @Query("""
        UPDATE market_instruments SET
            secId = :secId,
            name = :name,
            shortName = :shortName,
            isin = :isin,
            boardId = :boardId,
            lotSize = :lotSize,
            currency = :currency,
            market = :market,
            source = :source,
            rawFieldsJson = :rawFieldsJson,
            fetchedAtEpochMillis = :fetchedAtEpochMillis
        WHERE instrumentId = :instrumentId
    """)
    suspend fun updateInstrument(
        instrumentId: String,
        secId: String,
        name: String,
        shortName: String?,
        isin: String?,
        boardId: String?,
        lotSize: Double?,
        currency: String?,
        market: String,
        source: String,
        rawFieldsJson: String?,
        fetchedAtEpochMillis: Long
    )

    @Transaction
    suspend fun upsertInstruments(items: List<MarketInstrumentEntity>) {
        if (items.isEmpty()) return
        insertInstrumentsIfAbsent(items)
        items.forEach { item ->
            updateInstrument(
                instrumentId = item.instrumentId,
                secId = item.secId,
                name = item.name,
                shortName = item.shortName,
                isin = item.isin,
                boardId = item.boardId,
                lotSize = item.lotSize,
                currency = item.currency,
                market = item.market,
                source = item.source,
                rawFieldsJson = item.rawFieldsJson,
                fetchedAtEpochMillis = item.fetchedAtEpochMillis
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandles(items: List<CandleEntity>)

    @Query("""
        SELECT * FROM candles
        WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes
        ORDER BY begin
    """)
    fun observeCandles(instrumentId: String, intervalMinutes: Int): Flow<List<CandleEntity>>

    @Query("""
        SELECT MAX(begin) FROM candles
        WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes
    """)
    suspend fun getLatestCandleBegin(instrumentId: String, intervalMinutes: Int): String?

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

    @Query("""
        SELECT * FROM history_coverage
        WHERE instrumentId = :instrumentId AND intervalMinutes = :intervalMinutes
          AND requestedFrom IS NOT NULL AND requestedTill IS NOT NULL
          AND requestedFrom <= :from AND requestedTill >= :till
        ORDER BY fetchedAtEpochMillis DESC
        LIMIT 1
    """)
    suspend fun getCoverageForRange(
        instrumentId: String,
        intervalMinutes: Int,
        from: String,
        till: String
    ): HistoryCoverageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCoverage(coverage: HistoryCoverageEntity)

    @Query("""
        UPDATE history_coverage
        SET gapCount = :gapCount, gapsJson = :gapsJson
        WHERE coverageId = :coverageId
    """)
    suspend fun updateCoverageQuality(
        coverageId: String,
        gapCount: Int,
        gapsJson: String
    ): Int

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
