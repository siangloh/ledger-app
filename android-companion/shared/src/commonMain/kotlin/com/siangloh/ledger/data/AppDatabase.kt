package com.siangloh.ledger.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.siangloh.ledger.data.daos.CachedCategoryDao
import com.siangloh.ledger.data.daos.LocalAccountDao
import com.siangloh.ledger.data.daos.LocalCategoryDao
import com.siangloh.ledger.data.daos.LocalTransactionDao
import com.siangloh.ledger.data.daos.NotificationLogDao
import com.siangloh.ledger.data.daos.PendingNotificationDao
import com.siangloh.ledger.data.daos.PendingTransactionDao
import com.siangloh.ledger.data.entities.CachedCategory
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.data.entities.NotificationLog
import com.siangloh.ledger.data.entities.PendingNotification
import com.siangloh.ledger.data.entities.PendingTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

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
    version = 6,
    exportSchema = false
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingTransactionDao(): PendingTransactionDao
    abstract fun cachedCategoryDao(): CachedCategoryDao
    abstract fun pendingNotificationDao(): PendingNotificationDao
    abstract fun notificationLogDao(): NotificationLogDao
    abstract fun localTransactionDao(): LocalTransactionDao
    abstract fun localAccountDao(): LocalAccountDao
    abstract fun localCategoryDao(): LocalCategoryDao

    companion object {
        const val DATABASE_NAME = "ledger_local.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE pending_transactions ADD COLUMN account_id INTEGER")
                connection.execSQL("ALTER TABLE pending_transactions ADD COLUMN to_account_id INTEGER")
                connection.execSQL("ALTER TABLE pending_transactions ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE pending_transactions ADD COLUMN sync_error TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE pending_notifications ADD COLUMN eventId TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
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
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_accounts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        balance REAL NOT NULL,
                        creditLimit REAL NOT NULL,
                        currency TEXT NOT NULL,
                        orderNum INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        icon TEXT NOT NULL,
                        color TEXT NOT NULL,
                        orderNum INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_local_transactions_date ON local_transactions(date)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_local_transactions_type ON local_transactions(type)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_local_transactions_category ON local_transactions(category)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_local_transactions_accountId ON local_transactions(accountId)")
            }
        }

        fun configureBuilder(builder: RoomDatabase.Builder<AppDatabase>): AppDatabase =
            builder
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()

        fun getInstance(context: Any? = null): AppDatabase = getPlatformAppDatabase(context)
    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

expect fun getPlatformAppDatabase(platformContext: Any? = null): AppDatabase
