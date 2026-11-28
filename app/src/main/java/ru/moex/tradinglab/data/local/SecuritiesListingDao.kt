package ru.moex.tradinglab.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SecuritiesListingDao {
    @Query("SELECT * FROM securities_listing_snapshots ORDER BY fetchedAtEpochMillis DESC")
    fun observeSnapshots(): Flow<List<SecuritiesListingSnapshotEntity>>

    @Query("SELECT * FROM securities_listing_snapshots ORDER BY fetchedAtEpochMillis DESC LIMIT 1")
    suspend fun getLatestSnapshot(): SecuritiesListingSnapshotEntity?

    @Query("""
        SELECT * FROM securities_listing_entries
        WHERE snapshotId = :snapshotId
        ORDER BY secId, boardId
    """)
    fun observeEntries(snapshotId: String): Flow<List<SecuritiesListingEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: SecuritiesListingSnapshotEntity)

    @Query("DELETE FROM securities_listing_entries WHERE snapshotId = :snapshotId")
    suspend fun deleteEntries(snapshotId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<SecuritiesListingEntryEntity>)

    @Transaction
    suspend fun saveSnapshot(
        snapshot: SecuritiesListingSnapshotEntity,
        entries: List<SecuritiesListingEntryEntity>
    ) {
        require(entries.all { it.snapshotId == snapshot.snapshotId }) {
            "All SecuritiesListing entries must belong to the snapshot"
        }
        require(entries.all { it.tradeDate == snapshot.tradeDate }) {
            "All SecuritiesListing entries must match the snapshot trade date"
        }
        upsertSnapshot(snapshot)
        deleteEntries(snapshot.snapshotId)
        entries.chunked(500).forEach { insertEntries(it) }
    }
}
