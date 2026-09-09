package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.PendingNotification

@Dao
interface PendingNotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: PendingNotification): Long

    @Query("SELECT * FROM pending_notifications WHERE sync_status = 'pending' ORDER BY timestamp ASC")
    suspend fun getPendingNotifications(): List<PendingNotification>

    @Query("UPDATE pending_notifications SET sync_status = 'synced' WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("DELETE FROM pending_notifications WHERE id = :id")
    suspend fun delete(id: Long)
}
