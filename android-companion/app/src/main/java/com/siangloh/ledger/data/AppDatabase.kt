package com.siangloh.ledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 4,
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

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN account_id INTEGER")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN to_account_id INTEGER")
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_transactions ADD COLUMN sync_error TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_notifications ADD COLUMN eventId TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledger_local.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                 .fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
