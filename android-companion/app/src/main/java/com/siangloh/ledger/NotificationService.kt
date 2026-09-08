package com.siangloh.ledger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "LedgerNotifService"

        // 宽松匹配包名包含 tng / touchngo / ewallet / publicbank / mypb
        fun isTargetPackage(pkg: String): Boolean {
            val lower = pkg.lowercase()
            return lower.contains("tng") ||
                   lower.contains("touch") ||
                   lower.contains("ewallet") ||
                   lower.contains("publicbank") ||
                   lower.contains("mypb") ||
                   lower.contains("maybank")
        }

        // 扣款与转账关键字过滤，忽略营销广告
        val TRANSACTION_KEYWORDS = listOf(
            "paid", "spent", "transferred", "transfer", "debited", "payment",
            "received", "credited", "rm", "myr", "付款", "扣款", "转账", "收款"
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListenerService connected and actively listening.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkgName = sbn.packageName ?: return
        if (!isTargetPackage(pkgName)) return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val ticker = notif.tickerText?.toString() ?: ""

        // 合并所有有效文本内容
        val fullContent = listOf(title, text, bigText, subText, ticker)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()

        if (fullContent.isBlank()) return

        Log.i(TAG, "Captured notification from [$pkgName]: $fullContent")

        // 只要包含 RM 或 交易关键词，全部发送给后端解析
        if (isTransactionNotification(fullContent)) {
            Log.i(TAG, "Posting transaction notification to server...")
            NetworkHelper.postNotificationAsync(fullContent) { success, msg ->
                if (success) {
                    Log.i(TAG, "Auto-track successful: $msg")
                } else {
                    Log.e(TAG, "Auto-track upload failed: $msg")
                }
            }
        } else {
            Log.d(TAG, "Ignored notification without RM or transaction keywords.")
        }
    }

    private fun isTransactionNotification(content: String): Boolean {
        val lower = content.lowercase()
        val hasRm = lower.contains("rm") || lower.contains("myr")
        val hasKeyword = TRANSACTION_KEYWORDS.any { lower.contains(it) }
        return hasRm || hasKeyword
    }
}
