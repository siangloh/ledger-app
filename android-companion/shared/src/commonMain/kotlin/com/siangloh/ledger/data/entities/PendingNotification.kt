package com.siangloh.ledger.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.datetime.Clock

@Entity(tableName = "pending_notifications")
data class PendingNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val rawText: String,
    @ColumnInfo(defaultValue = "''") val eventId: String = "",
    val timestamp: Long = Clock.System.now().toEpochMilliseconds(),
    val sync_status: String = "pending" // "pending", "synced", "failed"
)
