package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_transactions")
data class LocalTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String, // YYYY-MM-DD
    val time: String = "", // HH:MM:SS
    val type: String, // "expense", "income", "transfer"
    val category: String,
    val amount: Double,
    val note: String = "",
    val tags: String = "",
    val accountId: Int? = null,
    val toAccountId: Int? = null,
    val source: String = "manual", // "manual", "auto_notification"
    val channel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
