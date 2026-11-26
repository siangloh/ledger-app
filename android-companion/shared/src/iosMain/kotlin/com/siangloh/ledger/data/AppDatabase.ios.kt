package com.siangloh.ledger.data

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLock
import kotlin.concurrent.Volatile

@Volatile
private var INSTANCE: AppDatabase? = null
private val lock = NSLock()

@OptIn(ExperimentalForeignApi::class)
actual fun getPlatformAppDatabase(platformContext: Any?): AppDatabase {
    INSTANCE?.let { return it }
    lock.lock()
    try {
        INSTANCE?.let { return it }
        val dbFilePath = NSHomeDirectory() + "/Documents/${AppDatabase.DATABASE_NAME}"
        val builder = Room.databaseBuilder<AppDatabase>(name = dbFilePath)
        return AppDatabase.configureBuilder(builder).also { INSTANCE = it }
    } finally {
        lock.unlock()
    }
}
