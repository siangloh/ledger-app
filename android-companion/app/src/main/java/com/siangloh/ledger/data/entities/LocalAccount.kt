package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_accounts")
data class LocalAccount(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val type: String, // "cash", "e_wallet", "savings", "credit_card", "other"
    val balance: Double = 0.0,
    val creditLimit: Double = 0.0,
    val currency: String = "RM",
    val orderNum: Int = 0
)
