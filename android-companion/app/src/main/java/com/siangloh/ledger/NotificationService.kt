package com.siangloh.ledger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.entities.NotificationLog
import com.siangloh.ledger.data.entities.PendingNotification
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.AppSelectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "LedgerNotifService"

        // Phase-1 常见营销促销敏感词（排除掉含这类词汇的通知，即便包含 RM 也判定为广告）
        val PROMO_KEYWORDS = listOf(
            "cashback", "voucher", "promo", "promotion", "discount", "claim",
            "top up now", "reload now", "limited time", "优惠", "红包", "抽奖",
            "充值返", "限时", "领券", "立减"
        )

        // 交易动作/动词与标识
        val TRANSACTION_VERBS = listOf(
            "paid", "spent", "transferred", "transfer", "debited", "payment",
            "received", "credited", "付款", "扣款", "转账", "收款",
            "duitnow", "qr pay", "to ", "from ", "successful", "completed"
        )

        // 严格金额格式正则：RM/MYR 紧跟数字且必须带有两位小数 (例如 RM 15.50 或 MYR20.00)
        val STRICT_AMOUNT_REGEX = Regex("""(?:RM|MYR)\s*[0-9]+(?:\.[0-9]{2})""", RegexOption.IGNORE_CASE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected and actively listening.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: return

        // 1. 用户自定义监听应用检查：如果用户未在设置中开启此应用，彻底跳过（且不写入日志以保护隐私）
        if (!AppSelectionManager.isAppMonitored(this, pkgName)) {
            return
        }

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val ticker = notif.tickerText?.toString() ?: ""

        // 2. 读取 EXTRA_TEXT_LINES (针对折叠/收件箱多行通知)
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val linesText = lines?.joinToString(" ") { it?.toString() ?: "" } ?: ""

        // 合并所有有效文本内容
        val fullContent = listOf(title, text, bigText, subText, ticker, linesText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()

        if (fullContent.isBlank()) return

        Log.i(TAG, "Captured notification from [$pkgName]: $fullContent")

        val lower = fullContent.lowercase()
        val db = AppDatabase.getInstance(this)

        // 3. Phase-1 本地快速营销/广告过滤
        val hasPromoWord = PROMO_KEYWORDS.any { lower.contains(it) }
        if (hasPromoWord) {
            Log.d(TAG, "Phase-1 Filtered: contains promo keywords -> $fullContent")
            serviceScope.launch {
                db.notificationLogDao().insert(
                    NotificationLog(
                        sourcePackage = pkgName,
                        rawText = fullContent,
                        matchedPhase1 = false,
                        sentToBackend = false,
                        outcome = "ignored_promo_keyword"
                    )
                )
            }
            return
        }

        // 4. Phase-1 交易关键词与金额验证
        val hasVerb = TRANSACTION_VERBS.any { lower.contains(it) }
        val hasStrictAmount = STRICT_AMOUNT_REGEX.containsMatchIn(fullContent)

        if (!hasVerb || !hasStrictAmount) {
            Log.d(TAG, "Phase-1 Filtered: lacks transaction verb or strict RM amount -> $fullContent")
            serviceScope.launch {
                db.notificationLogDao().insert(
                    NotificationLog(
                        sourcePackage = pkgName,
                        rawText = fullContent,
                        matchedPhase1 = false,
                        sentToBackend = false,
                        outcome = "ignored_no_keywords"
                    )
                )
            }
            return
        }

        // 5. 通过 Phase-1 检验，尝试发送给后端（或离线加入 Room 队列）
        if (NetworkHelper.isOnline(this)) {
            Log.i(TAG, "Posting transaction notification to backend...")
            NetworkHelper.postNotificationAsync(fullContent) { success, msg, verdict ->
                serviceScope.launch {
                    val finalOutcome = if (verdict == "rejected_promo") {
                        "synced_rejected_promo"
                    } else if (success) {
                        "synced_success"
                    } else {
                        // 网络异常或 5xx，排队等待稍后重试
                        queueOfflineNotification(pkgName, fullContent)
                        "queued_offline"
                    }

                    db.notificationLogDao().insert(
                        NotificationLog(
                            sourcePackage = pkgName,
                            rawText = fullContent,
                            matchedPhase1 = true,
                            sentToBackend = true,
                            backendVerdict = verdict,
                            outcome = finalOutcome
                        )
                    )
                }
            }
        } else {
            // 离线状态：直接加入本地 pending_notifications 数据库并触发 WorkManager
            Log.i(TAG, "Device offline: queuing notification in local database...")
            serviceScope.launch {
                queueOfflineNotification(pkgName, fullContent)
                db.notificationLogDao().insert(
                    NotificationLog(
                        sourcePackage = pkgName,
                        rawText = fullContent,
                        matchedPhase1 = true,
                        sentToBackend = false,
                        outcome = "queued_offline"
                    )
                )
            }
        }
    }

    private suspend fun queueOfflineNotification(pkgName: String, text: String) {
        val db = AppDatabase.getInstance(this)
        db.pendingNotificationDao().insert(
            PendingNotification(
                packageName = pkgName,
                rawText = text,
                sync_status = "pending"
            )
        )
        SyncWorker.enqueueSync(this)
    }
}
