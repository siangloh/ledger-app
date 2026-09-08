package com.siangloh.ledger

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class NotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "LedgerNotifService"

        // 目标监听 App 包名：Touch 'n Go eWallet 与 Public Bank MyPB
        val TARGET_PACKAGES = setOf(
            "com.tngdigital.ewallet",
            "com.publicbank.mypb",
            "my.com.maybank2u.m2umobile" // 可选 Maybank
        )

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

        val extras = sbn.notification?.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        // 合并所有有效文本内容
        val fullContent = listOf(title, text, bigText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .trim()

        if (fullContent.isBlank()) return

        Log.d(TAG, "Captured notification from $pkgName: $fullContent")

        // 检查是否包含交易关键字且包含金额特征
        if (isTransactionNotification(fullContent)) {
            Log.i(TAG, "Valid transaction notification detected! Uploading to server...")
            NetworkHelper.postNotificationAsync(fullContent) { success, msg ->
                if (success) {
                    Log.i(TAG, "Successfully auto-tracked transaction: $msg")
                } else {
                    Log.e(TAG, "Auto-track upload failed: $msg")
                }
            }
        } else {
            Log.d(TAG, "Ignored non-transaction notification (likely marketing/ad).")
        }
    }

    private fun isTargetPackage(pkg: String): Boolean {
        return TARGET_PACKAGES.any { pkg.equals(it, ignoreCase = true) }
    }

    private fun isTransactionNotification(content: String): Boolean {
        val lower = content.lowercase()
        val hasKeyword = TRANSACTION_KEYWORDS.any { lower.contains(it) }
        val hasAmount = Regex("""(?:rm|myr)?\s*\d+(?:\.\d{1,2})?""", RegexOption.IGNORE_CASE).containsMatchIn(content)
        return hasKeyword && hasAmount
    }
}
