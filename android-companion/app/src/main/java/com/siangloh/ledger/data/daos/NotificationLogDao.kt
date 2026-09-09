package com.siangloh.ledger.data.daos

import androidx.room.*
import com.siangloh.ledger.data.entities.NotificationLog

@Dao
interface NotificationLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: NotificationLog): Long

    @Query("SELECT * FROM notification_log ORDER BY timestamp DESC LIMIT 200")
    suspend fun getRecentLogs(): List<NotificationLog>

    @Query("DELETE FROM notification_log")
    suspend fun clearLogs()
}
