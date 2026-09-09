package com.siangloh.ledger.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "notification_log")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val sourcePackage: String,
    val rawText: String,
    val matchedPhase1: Boolean,
    val sentToBackend: Boolean,
    val backendVerdict: String? = null,
    val outcome: String // "ignored_app_off", "ignored_promo_keyword", "ignored_no_keywords", "queued_offline", "synced_success", "synced_rejected_promo", "error"
)
