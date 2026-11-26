package com.siangloh.ledger.data

import android.content.Context
import androidx.room.Room

@Volatile
private var INSTANCE: AppDatabase? = null
private val lock = Any()

actual fun getPlatformAppDatabase(platformContext: Any?): AppDatabase {
    INSTANCE?.let { return it }
    return synchronized(lock) {
        INSTANCE?.let { return@synchronized it }
        val appCtx = (platformContext as? Context)?.applicationContext
            ?: throw IllegalStateException("Android Context is required to initialize AppDatabase")
        val dbFile = appCtx.getDatabasePath(AppDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        val builder = Room.databaseBuilder<AppDatabase>(
            context = appCtx,
            name = dbFile.absolutePath
        )
        AppDatabase.configureBuilder(builder).also { INSTANCE = it }
    }
}
