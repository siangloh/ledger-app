package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_notifications")
data class PendingNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sync_status: String = "pending" // "pending", "synced", "failed"
)
