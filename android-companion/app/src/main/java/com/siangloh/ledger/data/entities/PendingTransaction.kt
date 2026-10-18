package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_transactions")
data class PendingTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String,
    val type: String, // "expense" or "income"
    val group_name: String = "personal",
    val category: String,
    val amount: Double,
    val note: String,
    val source: String = "offline_manual",
    val sync_status: String = "pending", // "local_only", "pending", "synced", "failed"
    val sync_error: String? = null,
    val created_at: Long = System.currentTimeMillis(),
    val account_id: Long? = null,
    val to_account_id: Long? = null,
    val tags: String = ""
)
