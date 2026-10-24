package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_categories")
data class LocalCategory(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val type: String, // "expense", "income", "savings"
    val icon: String = "🏷️",
    val color: String = "#94a3b8",
    val orderNum: Int = 0
)
