package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.PendingTransaction

@Dao
interface PendingTransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: PendingTransaction): Long

    @Query("SELECT * FROM pending_transactions WHERE sync_status IN ('pending', 'failed', 'local_only') ORDER BY created_at ASC")
    suspend fun getAllLocalTransactions(): List<PendingTransaction>

    @Query("SELECT * FROM pending_transactions WHERE sync_status IN ('pending', 'failed') ORDER BY created_at ASC")
    suspend fun getSyncableTransactions(): List<PendingTransaction>

    @Query("SELECT * FROM pending_transactions WHERE sync_status = 'pending' ORDER BY created_at ASC")
    suspend fun getPendingTransactions(): List<PendingTransaction>

    @Query("UPDATE pending_transactions SET sync_status = 'synced', sync_error = null WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("UPDATE pending_transactions SET sync_status = 'failed', sync_error = :error WHERE id = :id")
    suspend fun markAsFailed(id: Long, error: String)

    @Query("UPDATE pending_transactions SET sync_status = 'pending', sync_error = null WHERE id = :id")
    suspend fun resetToPending(id: Long)

    @Query("UPDATE pending_transactions SET sync_status = 'pending', sync_error = null WHERE sync_status = 'failed'")
    suspend fun resetAllFailedToPending()

    @Query("DELETE FROM pending_transactions WHERE sync_status = 'synced'")
    suspend fun deleteSynced()

    @Query("DELETE FROM pending_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status = 'pending'")
    suspend fun getPendingCount(): Int

    @Query("SELECT COUNT(*) FROM pending_transactions WHERE sync_status = 'failed'")
    suspend fun getFailedCount(): Int

    @Query("UPDATE pending_transactions SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: Long, category: String)
}
