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

import com.siangloh.ledger.data.daos.LocalAccountDao
import com.siangloh.ledger.data.daos.LocalCategoryDao
import com.siangloh.ledger.data.daos.LocalTransactionDao
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction

@Database(
    entities = [
        PendingTransaction::class,
        CachedCategory::class,
        PendingNotification::class,
        NotificationLog::class,
        LocalTransaction::class,
        LocalAccount::class,
        LocalCategory::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun cachedCategoryDao(): CachedCategoryDao
    abstract fun pendingNotificationDao(): PendingNotificationDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun localTransactionDao(): LocalTransactionDao
    abstract fun localAccountDao(): LocalAccountDao
    abstract fun localCategoryDao(): LocalCategoryDao

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_transactions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        date TEXT NOT NULL,
                        time TEXT NOT NULL,
                        type TEXT NOT NULL,
                        category TEXT NOT NULL,
                        amount REAL NOT NULL,
                        note TEXT NOT NULL,
                        tags TEXT NOT NULL,
                        accountId INTEGER,
                        toAccountId INTEGER,
                        source TEXT NOT NULL,
                        channel TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        balance REAL NOT NULL,
                        creditLimit REAL NOT NULL,
                        currency TEXT NOT NULL,
                        orderNum INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        color TEXT NOT NULL,
                        orderNum INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ledger_local.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                 .fallbackToDestructiveMigration()
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
