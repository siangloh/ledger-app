package com.siangloh.ledger.ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.siangloh.ledger.MainActivity
import com.siangloh.ledger.R
import java.util.concurrent.atomic.AtomicInteger

object InstantReviewManager {

    private const val TAG = "InstantReviewManager"
    private const val ALERT_CHANNEL_ID = "instant_expense_alert_v1"
    private val notifIdGenerator = AtomicInteger(2000)

    /** 分类名为空或属于这些“兜底分类”时，视为系统没把握，只进待整理，不打扰用户 */
    private val UNSURE_CATEGORIES = setOf("", "其他", "未分类", "待分类")

    val COMMON_CATEGORIES = listOf(
        "餐饮" to "🍔",
        "交通" to "🚗",
        "杂货" to "🛒",
        "账单" to "💡",
        "购物" to "🛍️",
        "娱乐" to "🎮",
        "医疗" to "💊",
        "转账" to "🔄",
        "其他" to "🏷️"
    )

    /**
     * 自动记账成功后的轻提示。
     * - 有把握（商户/分类都明确）：显示小提示条（无悬浮窗权限则发静音通知），可「改分类」或「撤销」
     * - 没把握：什么都不弹，交易已在服务端进入「待整理」
     */
    fun showInstantReview(
        context: Context,
        merchant: String,
        amount: Double,
        predictedCategory: String,
        channel: String,
        transactionId: Long,
        currency: String = "RM"
    ) {
        val cleanMerchant = merchant.trim()
        val cleanCategory = predictedCategory.trim()

        val confident = cleanMerchant.isNotEmpty() && cleanCategory !in UNSURE_CATEGORIES
        if (!confident) {
            Log.i(TAG, "Low confidence (merchant='$cleanMerchant', category='$cleanCategory'): left in review inbox, no popup")
            return
        }

        Handler(Looper.getMainLooper()).post {
            try {
                showStandardNotification(context, cleanMerchant, amount, cleanCategory, channel, transactionId, currency)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to display transaction notification: ${e.message}", e)
            }
        }
    }

    // ------------------------------------------------------------------
    // 悬浮小提示条
    // ------------------------------------------------------------------
    private fun showFloatingToast(
        context: Context,
        merchant: String,
        amount: Double,
        initialCategory: String,
        transactionId: Long,
        currency: String
    ) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 30
        }

        val composeView = ComposeView(context)
        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        var isDismissed = false
        fun dismiss() {
            if (isDismissed) return
            isDismissed = true
            try { windowManager.removeView(composeView) } catch (_: Exception) {}
            try { composeView.disposeComposition() } catch (_: Exception) {}
            try {
                lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_PAUSE)
                lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_STOP)
                lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_DESTROY)
            } catch (_: Exception) {}
        }

        composeView.setContent {
            InstantReviewToast(
                merchant = merchant,
                amount = amount,
                currency = currency,
                initialCategory = initialCategory,
                canUndo = transactionId > 0,
                onCommit = { category, saveRule ->
                    // 只在用户真的改了分类时，才发一次请求（以最终选择为准）
                    if (category != initialCategory) {
                        sendQuickCategorizeRequest(context, transactionId, category, merchant, saveRule)
                    }
                },
                onUndo = { TransactionUndo.undo(context.applicationContext, transactionId) },
                onFinished = { dismiss() }
            )
        }

        windowManager.addView(composeView, params)
    }

    // ------------------------------------------------------------------
    // 没有悬浮窗权限时：静音通知（不响、不震动、不弹横幅），15 秒自动消失
    // ------------------------------------------------------------------
    // 标准应用通知：提示交易已自动记录入账，支持查看明细与快速撤销
    // ------------------------------------------------------------------
    private fun showStandardNotification(
        context: Context,
        merchant: String,
        amount: Double,
        category: String,
        channel: String,
        transactionId: Long,
        currency: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.notif_recorded_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_recorded_channel_desc)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(notifChannel)
        }

        val notifId = notifIdGenerator.incrementAndGet()

        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val suffix = channel.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        val formattedAmount = String.format("%.2f", amount)
        val title = context.getString(R.string.notif_recorded_title, merchant)
        val body = context.getString(R.string.notif_recorded_body, currency, formattedAmount, category, suffix)

        val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        if (transactionId > 0) {
            val undoIntent = Intent(context, UndoTransactionReceiver::class.java).apply {
                putExtra(UndoTransactionReceiver.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(UndoTransactionReceiver.EXTRA_NOTIFICATION_ID, notifId)
            }
            val undoPending = PendingIntent.getBroadcast(
                context,
                notifId * 10,
                undoIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, context.getString(R.string.notif_action_undo), undoPending)
        }

        notificationManager.notify(notifId, builder.build())
    }

    private fun sendQuickCategorizeRequest(
        context: Context,
        transactionId: Long,
        category: String,
        merchant: String,
        saveRule: Boolean
    ) {
        if (transactionId <= 0) return
        val intent = Intent(context, QuickCategoryReceiver::class.java).apply {
            action = QuickCategoryReceiver.ACTION_CATEGORIZE
            putExtra(QuickCategoryReceiver.EXTRA_TRANSACTION_ID, transactionId)
            putExtra(QuickCategoryReceiver.EXTRA_CATEGORY, category)
            putExtra(QuickCategoryReceiver.EXTRA_MERCHANT, merchant)
            putExtra(QuickCategoryReceiver.EXTRA_REMEMBER, saveRule)
        }
        context.sendBroadcast(intent)
    }
}

// ======================================================================
// 小提示条 UI：白底 + 主题色，默认只有一行，不需要用户“确认”
// ======================================================================
private val ToastTeal = com.siangloh.ledger.ui.compose.theme.PrimaryTeal
private val ToastTealSubtle = com.siangloh.ledger.ui.compose.theme.PrimaryTealSubtle
private val ToastText = com.siangloh.ledger.ui.compose.theme.Slate900
private val ToastSubText = com.siangloh.ledger.ui.compose.theme.Slate500
private val ToastChip = com.siangloh.ledger.ui.compose.theme.Slate100

@Composable
private fun InstantReviewToast(
    merchant: String,
    amount: Double,
    currency: String,
    initialCategory: String,
    canUndo: Boolean,
    onCommit: (category: String, saveRule: Boolean) -> Unit,
    onUndo: () -> Unit,
    onFinished: () -> Unit
) {
    var visible by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(initialCategory) }
    var saveRule by remember { mutableStateOf(true) }

    fun close() {
        if (closing) return
        closing = true
        onCommit(selected, saveRule)
        visible = false
    }

    fun undo() {
        if (closing) return
        closing = true
        onUndo()
        visible = false
    }

    // 收起时 3 秒后自动消失；展开改分类时给更长的时间，且每次操作都会重新计时
    LaunchedEffect(expanded, selected, saveRule) {
        kotlinx.coroutines.delay(if (expanded) 8000 else 3000)
        close()
    }

    // 退出动画结束后再移除窗口
    LaunchedEffect(visible) {
        if (!visible) {
            kotlinx.coroutines.delay(300)
            onFinished()
        }
    }

    val emojiMap = remember { InstantReviewManager.COMMON_CATEGORIES.toMap() }
    val categories = remember(initialCategory) {
        val list = InstantReviewManager.COMMON_CATEGORIES.toMutableList()
        if (list.none { it.first == initialCategory }) list.add(0, initialCategory to "🏷️")
        list
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(250)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(250)) + fadeOut()
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(ToastTealSubtle),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(emojiMap[selected] ?: "🏷️", fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            merchant,
                            color = ToastText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "✓ 已记账 · $selected",
                            color = ToastTeal,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$currency ${String.format("%.2f", amount)}",
                        color = ToastText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (expanded) {
                        TextButton(
                            onClick = { close() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("完成", color = ToastTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = { expanded = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("改分类", color = ToastSubText, fontSize = 13.sp)
                        }
                    }
                    if (canUndo) {
                        TextButton(
                            onClick = { undo() },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("撤销", color = ToastTeal, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                AnimatedVisibility(visible = expanded) {
                    Column {
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            categories.forEach { (name, emoji) ->
                                val isSel = name == selected
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSel) ToastTeal else ToastChip,
                                    modifier = Modifier.clickable { selected = name }
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(emoji, fontSize = 13.sp)
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            name,
                                            color = if (isSel) Color.White else ToastText,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }

                        // 只有真的改了分类，才问要不要记住规则
                        if (selected != initialCategory) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { saveRule = !saveRule }
                            ) {
                                Checkbox(
                                    checked = saveRule,
                                    onCheckedChange = { saveRule = it },
                                    colors = CheckboxDefaults.colors(checkedColor = ToastTeal),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    "以后「$merchant」都归为 $selected",
                                    color = ToastSubText,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lightweight mock LifecycleOwner to allow ComposeView to execute in WindowManager overlay.
 */
class OverlayLifecycleOwner : androidx.lifecycle.LifecycleOwner,
    androidx.lifecycle.ViewModelStoreOwner,
    androidx.savedstate.SavedStateRegistryOwner {

    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val viewModelStoreInstance = androidx.lifecycle.ViewModelStore()
    private val savedStateRegistryController = androidx.savedstate.SavedStateRegistryController.create(this)

    override val lifecycle: androidx.lifecycle.Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: androidx.lifecycle.ViewModelStore
        get() = viewModelStoreInstance

    override val savedStateRegistry: androidx.savedstate.SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun handleLifecycleEvent(event: androidx.lifecycle.Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }

    fun performRestore(savedState: android.os.Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }
}