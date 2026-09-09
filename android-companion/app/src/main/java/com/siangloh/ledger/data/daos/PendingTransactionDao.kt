package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.PendingTransaction

@Dao
interface PendingTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: PendingTransaction): Long

    @Query("SELECT * FROM pending_transactions WHERE sync_status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingTransactions(): List<PendingTransaction>

    @Query("UPDATE pending_transactions SET sync_status = 'synced' WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM pending_transactions WHERE sync_status = 'synced'")
    suspend fun deleteSynced()

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status = 'pending'")
    suspend fun getPendingCount(): Int
}
