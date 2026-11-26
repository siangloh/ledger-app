package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_categories")
data class CachedCategory(
    @PrimaryKey
    val id: Long,
    val name: String,
    val type: String, // "expense" or "income"
    val group_name: String = "personal"
)
