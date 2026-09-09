package com.siangloh.ledger.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.entities.NotificationLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LedgerSyncWorker"
        private const val WORK_NAME = "ledger_background_sync"

        fun enqueueSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting background sync...")
        val db = AppDatabase.getInstance(applicationContext)

        try {
            // 1. 同步离线记账交易记录
            val pendingTxs = db.pendingTransactionDao().getPendingTransactions()
            if (pendingTxs.isNotEmpty()) {
                Log.i(TAG, "Syncing ${pendingTxs.size} pending transactions...")
                val latch = CountDownLatch(1)
                NetworkHelper.syncPendingTransactions(pendingTxs) { success, syncedIds ->
                    if (success && syncedIds.isNotEmpty()) {
                        kotlinx.coroutines.runBlocking {
                            db.pendingTransactionDao().markAsSynced(syncedIds)
                            db.pendingTransactionDao().deleteSynced()
                        }
                    }
                    latch.countDown()
                }
                latch.await(30, TimeUnit.SECONDS)
            }

            // 2. 同步离线缓存的未发通知
            val pendingNotifs = db.pendingNotificationDao().getPendingNotifications()
            if (pendingNotifs.isNotEmpty()) {
                Log.i(TAG, "Retrying ${pendingNotifs.size} pending notifications...")
                for (notif in pendingNotifs) {
                    val latch = CountDownLatch(1)
                    NetworkHelper.postNotificationAsync(notif.rawText) { success, msg, verdict ->
                        kotlinx.coroutines.runBlocking {
                            if (success || verdict == "rejected_promo") {
                                db.pendingNotificationDao().delete(notif.id)
                                val finalOutcome = if (verdict == "rejected_promo") "synced_rejected_promo" else "synced_success"
                                db.notificationLogDao().insert(
                                    NotificationLog(
                                        sourcePackage = notif.packageName,
                                        rawText = notif.rawText,
                                        matchedPhase1 = true,
                                        sentToBackend = true,
                                        backendVerdict = verdict,
                                        outcome = finalOutcome
                                    )
                                )
                            }
                        }
                        latch.countDown()
                    }
                    latch.await(15, TimeUnit.SECONDS)
                }
            }

            // 3. 拉取并刷新分类列表
            val catLatch = CountDownLatch(1)
            NetworkHelper.fetchCategories { success, categories ->
                if (success && categories.isNotEmpty()) {
                    kotlinx.coroutines.runBlocking {
                        db.cachedCategoryDao().clearAll()
                        db.cachedCategoryDao().insertAll(categories)
                    }
                }
                catLatch.countDown()
            }
            catLatch.await(15, TimeUnit.SECONDS)

            Log.i(TAG, "Background sync completed successfully.")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed: ${e.message}", e)
            Result.retry()
        }
    }
}
