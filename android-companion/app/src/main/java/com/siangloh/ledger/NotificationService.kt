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

        // Phase-1 纯粹营销推广词汇（仅当完全不包含交易扣款动作或金额时才拦截）
        val PURE_PROMO_KEYWORDS = listOf(
            "top up now", "reload now", "limited time offer", "apply for loan", "cash loan",
            "充值返", "立即充值", "申请贷款", "邀请好友", "分享领"
        )

        // 交易动作/动词与标识
        val TRANSACTION_VERBS = listOf(
            "paid", "spent", "transferred", "transfer", "debited", "payment",
            "received", "credited", "付款", "扣款", "转账", "收款", "支付", "已支付",
            "duitnow", "qr pay", "to ", "from ", "successful", "completed", "you have paid",
            "you've paid", "sent to"
        )

        // 宽松金额格式正则：支持整数、单小数位、双小数位及千分位逗号 (例如 RM15, RM 15.5, RM 15.50, RM 1,250.00, MYR 20)
        val AMOUNT_REGEX = Regex("""(?:RM|MYR)\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE)
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
