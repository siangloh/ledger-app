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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recentNotifications = java.util.concurrent.ConcurrentHashMap<String, Long>()

    companion object {
        private const val TAG = "LedgerNotifService"

        // Phase-1 纯粹营销推广词汇
        val PURE_PROMO_KEYWORDS = listOf(
            "top up now", "reload now", "limited time offer", "apply for loan", "cash loan",
            "充值返", "立即充值", "申请贷款", "邀请好友", "分享领",
            "tebus", "baucar", "rebut", "voucher", "super brand day", "brand day",
            "% off", "off!", "diskaun", "add to cart", "free shipping", "flash sale", "shocking sale",
            "just for you", "cashback", "no min. spend", "no min spend", "min. spend", "min spend",
            "limited redemption", "limited redemptions", "penebusan terhad", "tiada perbelanjaan minimum",
            "pay with duitnow", "pay with shopeepay", "pay with", "bayar guna", "bayar dengan",
            "points from your transaction", "redeem great rewards", "earn points",
            "live stream", "livestream", "spin & win", "shake & win"
        )

        // 交易动作/动词与标识 (避免使用孤立的 "to " 或 "from "，防止普通英语句子误判)
        val TRANSACTION_VERBS = listOf(
            "paid", "spent", "transferred", "transfer", "debited", "debit", "payment",
            "received", "credited", "credit", "purchase", "purchased", "bought", "charge",
            "charged", "transaction", "txn", "付款", "扣款", "转账", "收款", "支付", "已支付",
            "消费", "支出", "duitnow payment", "duitnow transfer", "paid via duitnow",
            "duitnow to", "duitnow from", "duitnow qr payment", "qr pay", "paid to",
            "transfer to", "transferred to", "sent to", "payment to", "received from",
            "transfer from", "transferred from", "refund from", "payment from", "successful",
            "completed", "you have paid", "you've paid", "sent", "reload", "top up",
            "using your cd", "using your card", "card transaction alert", "card transaction"
        )

        // 明确的已完成交易动词特征
        val COMPLETED_TXN_VERBS = listOf(
            "paid", "you have paid", "you've paid", "spent", "deducted", "debited", "charged",
            "transferred", "successful", "completed", "has been deducted", "payment to", "payment of",
            "已支付", "成功支付", "成功转账", "成功扣款", "扣款", "支出",
            "using your cd", "using your card", "card transaction alert", "card transaction"
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
        val eventId = java.util.UUID.nameUUIDFromBytes(
            (sbn.key + ":" + sbn.postTime + ":" + fullContent).toByteArray(Charsets.UTF_8)
        ).toString()

        // 0. 短时重复通知拦截 (例如系统进度更新、通知栏触碰刷新造成的重复触发)
        val cacheKey = "$pkgName:$fullContent"
        val now = System.currentTimeMillis()
        val lastSeen = recentNotifications[cacheKey]
        if (lastSeen != null && (now - lastSeen) < 30_000L) {
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

        // 3. Phase-1 纯营销/广告拦截：若命中强营销关键词且缺少已完成动账凭证，直接本地拦截
        val hasPurePromoWord = PURE_PROMO_KEYWORDS.any { lower.contains(it) }
        val hasCompletedTxn = COMPLETED_TXN_VERBS.any { lower.contains(it) }
        if (hasPurePromoWord && !hasCompletedTxn) {
            Log.d(TAG, "Phase-1 Filtered: contains pure promo keywords without completed transaction -> $fullContent")
            serviceScope.launch {
                insertLogAndPrune(
                    db,
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
            // 省电优化：如果既没有金额也没有动词（例如纯聊天、验证码短信、系统通知），直接过滤跳过，
            // 严禁对海量无关消息频繁唤醒磁盘执行 Room 事务，大幅保护电池与闪存寿命。
            val shouldLogForDiagnosis = hasAmount || hasVerb || AppSelectionManager.KNOWN_BANK_KEYWORDS.any { pkgName.lowercase().contains(it) }
            if (shouldLogForDiagnosis) {
                serviceScope.launch {
                    insertLogAndPrune(
                        db,
                        NotificationLog(
                            sourcePackage = pkgName,
                            rawText = fullContent,
                            matchedPhase1 = false,
                            sentToBackend = false,
                            outcome = "ignored_no_keywords"
                        )
                    )
                }
            }
            return
        }

        // 5. 通过 Phase-1 检验，尝试发送给后端（或离线/等待WiFi时加入 Room 队列）
        // 5. 纯本地独立记账：直接在设备本地解析并入库，彻底脱离外部服务器
        serviceScope.launch {
            try {
                val parsed = com.siangloh.ledger.parser.LocalNotificationParser.parse(fullContent, pkgName)
                if (parsed != null) {
                    if (parsed.isPromo) {
                        insertLogAndPrune(
                            db,
                            NotificationLog(
                                sourcePackage = pkgName,
                                rawText = fullContent,
                                matchedPhase1 = true,
                                sentToBackend = false,
                                outcome = "ignored_promo_keyword"
                            )
                        )
                        return@launch
                    }
                    if (parsed.isInternalTransfer) {
                        insertLogAndPrune(
                            db,
                            NotificationLog(
                                sourcePackage = pkgName,
                                rawText = fullContent,
                                matchedPhase1 = true,
                                sentToBackend = false,
                                outcome = "synced_internal_transfer"
                            )
                        )
                        return@launch
                    }
                    if (parsed.amount > 0) {
                        val txId = com.siangloh.ledger.data.LocalLedgerRepository.recordNotificationTransaction(this@NotificationService, parsed)
                        com.siangloh.ledger.ui.overlay.InstantReviewManager.showInstantReview(
                            this@NotificationService,
                            parsed.merchant,
                            parsed.amount,
                            parsed.category,
                            parsed.channel,
                            txId
                        )
                        insertLogAndPrune(
                            db,
                            NotificationLog(
                                sourcePackage = pkgName,
                                rawText = fullContent,
                                matchedPhase1 = true,
                                sentToBackend = false,
                                outcome = "local_recorded"
                            )
                        )
                        try {
                            val intent = android.content.Intent("com.siangloh.ledger.SYNC_COMPLETED")
                            intent.setPackage(packageName)
                            sendBroadcast(intent)
                        } catch (_: Exception) {}
                        return@launch
                    }
                }

                insertLogAndPrune(
                    db,
                    NotificationLog(
                        sourcePackage = pkgName,
                        rawText = fullContent,
                        matchedPhase1 = true,
                        sentToBackend = false,
                        outcome = "ignored_no_keywords"
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Local notification processing failed: ${e.message}", e)
            }
        }
    }

    private suspend fun insertLogAndPrune(db: AppDatabase, log: NotificationLog) {
        try {
            db.notificationLogDao().insert(log)
            db.notificationLogDao().pruneOldLogs()
        } catch (e: Exception) {
            Log.d(TAG, "Failed to insert or prune notification log: ${e.message}")
        }
    }

    override fun onDestroy() {
        try {
            serviceScope.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up NotificationService: ${e.message}")
        }
        super.onDestroy()
    }

    private suspend fun queueOfflineNotification(pkgName: String, text: String, eventId: String) {
        val db = AppDatabase.getInstance(this)
        val pendingCount = db.pendingNotificationDao().countPendingWithEventId(eventId)
        if (pendingCount > 0) {
            Log.d(TAG, "Notification already exists in offline queue, skip duplicate enqueue.")
            return
        }
        db.pendingNotificationDao().insert(
            PendingNotification(
                packageName = pkgName,
                rawText = text,
                eventId = eventId,
                sync_status = "pending"
            )
        )
        SyncWorker.enqueueSync(this)
    }
}
