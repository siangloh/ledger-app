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
    private val recentNotifications = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        private const val TAG = "LedgerNotifService"

        // Phase-1 纯粹营销推广词汇（仅当完全不包含交易扣款动作或金额时才拦截）
        val PURE_PROMO_KEYWORDS = listOf(
            "top up now", "reload now", "limited time offer", "apply for loan", "cash loan",
            "充值返", "立即充值", "申请贷款", "邀请好友", "分享领",
            "tebus", "baucar", "rebut", "voucher", "super brand day", "brand day",
            "% off", "off!", "diskaun", "add to cart", "free shipping", "flash sale", "shocking sale"
        )

        // 交易动作/动词与标识 (避免使用孤立的 "to " 或 "from "，防止普通英语句子误判)
        val TRANSACTION_VERBS = listOf(
            "paid", "spent", "transferred", "transfer", "debited", "debit", "payment",
            "received", "credited", "credit", "purchase", "purchased", "bought", "charge",
            "charged", "transaction", "txn", "付款", "扣款", "转账", "收款", "支付", "已支付",
            "消费", "支出", "duitnow", "qr pay", "paid to", "transfer to", "transferred to",
            "sent to", "payment to", "received from", "transfer from", "transferred from",
            "refund from", "payment from", "successful", "completed", "you have paid",
            "you've paid", "sent", "reload", "top up"
        )

        // 宽松金额格式正则：支持带 RM/MYR/$ 货币符号，或纯数字小数金额 (例如 RM15, RM 15.5, RM 15.50, 15.50, RM 1,250.00, MYR 20, $15.00)
        val AMOUNT_REGEX = Regex("""(?:(?:RM|MYR|\$)\s*[0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|(?:RM|MYR|\$)\s*[0-9]+|\b[0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{1,2}\b|\b[0-9]+\.[0-9]{1,2}\b)""", RegexOption.IGNORE_CASE)
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

        // 0. 短时重复通知拦截 (例如系统进度更新、通知栏触碰刷新造成的重复触发)
        val cacheKey = "$pkgName:$fullContent"
        val now = System.currentTimeMillis()
        val lastSeen = recentNotifications[cacheKey]
        if (lastSeen != null && (now - lastSeen) < 60_000L) {
            Log.d(TAG, "Suppressed duplicate notification from system update: $cacheKey")
            return
        }
        recentNotifications[cacheKey] = now
        if (recentNotifications.size > 200) {
            val cutoff = now - 300_000L
            recentNotifications.entries.removeIf { it.value < cutoff }
        }

        Log.i(TAG, "Captured notification from [$pkgName]: $fullContent")

        val lower = fullContent.lowercase()
        val db = AppDatabase.getInstance(this)

        val hasVerb = TRANSACTION_VERBS.any { lower.contains(it) }
        val hasAmount = AMOUNT_REGEX.containsMatchIn(fullContent)

        // 3. Phase-1 纯营销/广告拦截：只有在消息是纯广告且完全缺少扣款动词或金额时才丢弃
        val hasPurePromoWord = PURE_PROMO_KEYWORDS.any { lower.contains(it) }
        if (hasPurePromoWord && (!hasVerb || !hasAmount)) {
            Log.d(TAG, "Phase-1 Filtered: contains pure promo keywords without transaction -> $fullContent")
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
        if (!hasVerb || !hasAmount) {
            Log.d(TAG, "Phase-1 Filtered: lacks transaction verb or valid RM amount -> $fullContent")
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
            NetworkHelper.postNotificationAsync(fullContent, this) { success, msg, verdict ->
                serviceScope.launch {
                    val finalOutcome = when (verdict) {
                        "rejected_promo" -> "synced_rejected_promo"
                        "duplicate_ignored" -> "synced_duplicate_ignored"
                        else -> if (success) "synced_success" else {
                            queueOfflineNotification(pkgName, fullContent)
                            "queued_offline"
                        }
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
        val pendingCount = db.pendingNotificationDao().countPendingWithText(text)
        if (pendingCount > 0) {
            Log.d(TAG, "Notification already exists in offline queue, skip duplicate enqueue.")
            return
        }
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
