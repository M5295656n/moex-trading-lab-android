package ru.moex.tradinglab.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        InstrumentEntity::class,
        MarketInstrumentEntity::class,
        CandleEntity::class,
        HistoryCoverageEntity::class,
        CatalogSyncEntity::class,
        SecuritiesListingSnapshotEntity::class,
        SecuritiesListingEntryEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class TradingLabDatabase : RoomDatabase() {
    abstract fun instrumentDao(): InstrumentDao
    abstract fun marketDataDao(): MarketDataDao
    abstract fun securitiesListingDao(): SecuritiesListingDao

    companion object {
        /**
         * Keeps the original instruments table and its rows intact. The new
         * normalized market-data tables are additive, so no destructive migration
         * or lossy conversion of legacy instrument identities is required.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS market_instruments (
                        instrumentId TEXT NOT NULL,
                        secId TEXT NOT NULL,
                        name TEXT NOT NULL,
                        shortName TEXT,
                        isin TEXT,
                        boardId TEXT,
                        lotSize REAL,
                        currency TEXT,
                        market TEXT NOT NULL,
                        source TEXT NOT NULL,
                        rawFieldsJson TEXT,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(instrumentId)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_market_instruments_secId_market_boardId ON market_instruments(secId, market, boardId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_market_instruments_market_shortName ON market_instruments(market, shortName)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS candles (
                        instrumentId TEXT NOT NULL,
                        intervalMinutes INTEGER NOT NULL,
                        begin TEXT NOT NULL,
                        end TEXT,
                        open REAL,
                        high REAL,
                        low REAL,
                        close REAL,
                        value REAL,
                        volume REAL,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(instrumentId, intervalMinutes, begin),
                        FOREIGN KEY(instrumentId) REFERENCES market_instruments(instrumentId) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_candles_instrumentId_intervalMinutes_begin ON candles(instrumentId, intervalMinutes, begin)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS history_coverage (
                        coverageId TEXT NOT NULL PRIMARY KEY,
                        instrumentId TEXT NOT NULL,
                        intervalMinutes INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        endpoint TEXT NOT NULL,
                        requestedFrom TEXT,
                        requestedTill TEXT,
                        firstCandleBegin TEXT,
                        lastCandleBegin TEXT,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        pageCount INTEGER NOT NULL,
                        pageSize INTEGER NOT NULL,
                        paginationStatus TEXT NOT NULL,
                        skippedRows INTEGER NOT NULL,
                        gapCount INTEGER,
                        gapsJson TEXT,
                        freshnessStatus TEXT NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_coverage_instrumentId_intervalMinutes_requestedFrom_requestedTill ON history_coverage(instrumentId, intervalMinutes, requestedFrom, requestedTill)")
            }
        }

        /** Adds durable per-market catalog diagnostics without modifying existing rows. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS catalog_sync (
                        market TEXT NOT NULL PRIMARY KEY,
                        source TEXT NOT NULL,
                        endpoint TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        instrumentCount INTEGER NOT NULL,
                        pageCount INTEGER NOT NULL,
                        pageSize INTEGER NOT NULL,
                        completeness TEXT NOT NULL,
                        skippedRows INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** Stores local, date-stamped snapshots of stock security/board session permissions. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS securities_listing_snapshots (
                        snapshotId TEXT NOT NULL,
                        tradeDate TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sourceUrl TEXT NOT NULL,
                        archiveMember TEXT NOT NULL,
                        sha256 TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        declaredRecordCount INTEGER NOT NULL,
                        activeRecordCount INTEGER NOT NULL,
                        storedEntryCount INTEGER NOT NULL,
                        filteredOutEntryCount INTEGER NOT NULL,
                        deletedRecordCount INTEGER NOT NULL,
                        duplicateRecordCount INTEGER NOT NULL,
                        unknownFlagCount INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(snapshotId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_securities_listing_snapshots_tradeDate_fetchedAtEpochMillis ON securities_listing_snapshots(tradeDate, fetchedAtEpochMillis)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS securities_listing_entries (
                        snapshotId TEXT NOT NULL,
                        tradeDate TEXT NOT NULL,
                        tradeSessionDate TEXT NOT NULL,
                        boardId TEXT NOT NULL,
                        secId TEXT NOT NULL,
                        morningSession INTEGER,
                        eveningSession INTEGER,
                        weekendSession INTEGER,
                        PRIMARY KEY(snapshotId, tradeDate, tradeSessionDate, boardId, secId),
                        FOREIGN KEY(snapshotId) REFERENCES securities_listing_snapshots(snapshotId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_securities_listing_entries_snapshotId ON securities_listing_entries(snapshotId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_securities_listing_entries_secId_boardId_tradeDate ON securities_listing_entries(secId, boardId, tradeDate)")
            }
        }
    }
}
