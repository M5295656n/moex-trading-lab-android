package ru.moex.tradinglab.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TradingLabDatabaseMigrationTest {
    private lateinit var context: Context
    private lateinit var database: TradingLabDatabase
    private val databaseName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getDatabasePath(databaseName).parentFile?.mkdirs()
        context.deleteDatabase(databaseName)
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        legacy.execSQL("""
            CREATE TABLE IF NOT EXISTS instruments (
                secId TEXT NOT NULL PRIMARY KEY,
                shortName TEXT NOT NULL,
                instrumentType TEXT NOT NULL,
                engine TEXT NOT NULL,
                market TEXT NOT NULL,
                source TEXT NOT NULL,
                updatedAtEpochMillis INTEGER NOT NULL
            )
        """.trimIndent())
        legacy.execSQL("""
            INSERT INTO instruments VALUES ('SBER', 'Сбербанк', 'SHARES', 'stock', 'shares', 'MOEX ISS', 123)
        """.trimIndent())
        legacy.version = 1
        legacy.close()

        database = Room.databaseBuilder(context, TradingLabDatabase::class.java, databaseName)
            .addMigrations(
                TradingLabDatabase.MIGRATION_1_2,
                TradingLabDatabase.MIGRATION_2_3,
                TradingLabDatabase.MIGRATION_3_4
            )
            .build()
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrationPreservesLegacyRowsAndPersistsCatalogDiagnostics() = runBlocking {
        val legacyRows = database.openHelper.readableDatabase.query("SELECT * FROM instruments")
        assertEquals(1, legacyRows.count)
        legacyRows.moveToFirst()
        assertEquals("SBER", legacyRows.getString(0))
        legacyRows.close()

        val instrument = MarketInstrumentEntity(
            instrumentId = "SHARES:TQBR:SBER",
            secId = "SBER",
            name = "Сбербанк",
            shortName = "Сбер",
            isin = null,
            boardId = "TQBR",
            lotSize = 10.0,
            currency = "RUB",
            market = "SHARES",
            source = "MOEX ISS",
            rawFieldsJson = null,
            fetchedAtEpochMillis = 456
        )
        database.marketDataDao().saveCatalog(
            items = listOf(instrument),
            sync = CatalogSyncEntity(
                market = "SHARES",
                source = "MOEX ISS",
                endpoint = "engines/stock/markets/shares/securities",
                fetchedAtEpochMillis = 789,
                instrumentCount = 1,
                pageCount = 1,
                pageSize = 500,
                completeness = "COMPLETE",
                skippedRows = 0
            )
        )
        val catalogRows = database.openHelper.readableDatabase.query(
            "SELECT market, instrumentCount, completeness FROM catalog_sync"
        )
        assertEquals(1, catalogRows.count)
        catalogRows.moveToFirst()
        assertEquals("SHARES", catalogRows.getString(0))
        assertEquals(1, catalogRows.getInt(1))
        assertEquals("COMPLETE", catalogRows.getString(2))
        catalogRows.close()

        database.securitiesListingDao().saveSnapshot(
            snapshot = SecuritiesListingSnapshotEntity(
                snapshotId = "snapshot-sha",
                tradeDate = "2026-11-25",
                source = "MOEX ISS",
                sourceUrl = "https://iss.moex.com/file/stock_boardsecid/SL.ZIP",
                archiveMember = "SL251126.dbf",
                sha256 = "snapshot-sha",
                fetchedAtEpochMillis = 999,
                declaredRecordCount = 1,
                activeRecordCount = 1,
                storedEntryCount = 1,
                filteredOutEntryCount = 0,
                deletedRecordCount = 0,
                duplicateRecordCount = 0,
                unknownFlagCount = 0,
                status = "COMPLETE"
            ),
            entries = listOf(
                SecuritiesListingEntryEntity(
                    snapshotId = "snapshot-sha",
                    tradeDate = "2026-11-25",
                    tradeSessionDate = "2026-11-25",
                    boardId = "TQBR",
                    secId = "SBER",
                    morningSession = true,
                    eveningSession = true,
                    weekendSession = false
                )
            )
        )
        val snapshotRows = database.openHelper.readableDatabase.query(
            "SELECT storedEntryCount, status FROM securities_listing_snapshots WHERE snapshotId = 'snapshot-sha'"
        )
        assertEquals(1, snapshotRows.count)
        snapshotRows.moveToFirst()
        assertEquals(1, snapshotRows.getInt(0))
        assertEquals("COMPLETE", snapshotRows.getString(1))
        snapshotRows.close()

        val listingRows = database.openHelper.readableDatabase.query(
            "SELECT boardId, secId, morningSession, eveningSession, weekendSession FROM securities_listing_entries"
        )
        assertEquals(1, listingRows.count)
        listingRows.moveToFirst()
        assertEquals("TQBR", listingRows.getString(0))
        assertEquals("SBER", listingRows.getString(1))
        assertEquals(1, listingRows.getInt(2))
        assertEquals(1, listingRows.getInt(3))
        assertEquals(0, listingRows.getInt(4))
        listingRows.close()

        assertEquals(4, database.openHelper.readableDatabase.version)
    }
}
