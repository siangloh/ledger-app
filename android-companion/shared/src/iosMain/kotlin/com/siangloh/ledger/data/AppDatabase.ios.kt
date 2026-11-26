package com.siangloh.ledger.data

import androidx.room.Room
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
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
        val docsDir = NSHomeDirectory() + "/Documents"
        NSFileManager.defaultManager.createDirectoryAtPath(
            docsDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        val dbFilePath = "$docsDir/${AppDatabase.DATABASE_NAME}"
        val builder = Room.databaseBuilder<AppDatabase>(name = dbFilePath)
        return AppDatabase.configureBuilder(builder).also { INSTANCE = it }
    } finally {
        lock.unlock()
    }
}
