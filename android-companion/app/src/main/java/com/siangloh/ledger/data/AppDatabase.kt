package com.siangloh.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.siangloh.ledger.data.daos.CachedCategoryDao
import com.siangloh.ledger.data.daos.NotificationLogDao
import com.siangloh.ledger.data.daos.PendingNotificationDao
import com.siangloh.ledger.data.daos.PendingTransactionDao
import com.siangloh.ledger.data.entities.CachedCategory
import com.siangloh.ledger.data.entities.NotificationLog
import com.siangloh.ledger.data.entities.PendingNotification
import com.siangloh.ledger.data.entities.PendingTransaction

@Database(
    entities = [
        PendingTransaction::class,
        CachedCategory::class,
        PendingNotification::class,
        NotificationLog::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun cachedCategoryDao(): CachedCategoryDao
    abstract fun pendingNotificationDao(): PendingNotificationDao
    abstract fun notificationLogDao(): NotificationLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledger_local.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
