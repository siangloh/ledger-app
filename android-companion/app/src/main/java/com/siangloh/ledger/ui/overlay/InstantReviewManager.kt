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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.siangloh.ledger.MainActivity
import com.siangloh.ledger.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

object InstantReviewManager {

    private const val TAG = "InstantReviewManager"
    private const val CHANNEL_ID = "instant_expense_channel"
    private val notifIdGenerator = AtomicInteger(2000)

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

    fun showInstantReview(
        context: Context,
        merchant: String,
        amount: Double,
        predictedCategory: String,
        channel: String,
        transactionId: Long
    ) {
        val cleanMerchant = merchant.ifBlank { "待确认商户" }
        val cleanCategory = predictedCategory.ifBlank { "餐饮" }

        Handler(Looper.getMainLooper()).post {
            try {
                if (Settings.canDrawOverlays(context)) {
                    Log.i(TAG, "Showing ExpenseMY-style floating card overlay for: $cleanMerchant")
                    showFloatingOverlay(context, cleanMerchant, amount, cleanCategory, channel, transactionId)
                } else {
                    Log.i(TAG, "Floating overlay permission not granted. Falling back to Heads-up Notification.")
                    showHeadsUpNotification(context, cleanMerchant, amount, cleanCategory, channel, transactionId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to display instant review UI: ${e.message}, falling back to notification")
                showHeadsUpNotification(context, cleanMerchant, amount, cleanCategory, channel, transactionId)
            }
        }
    }

    private fun showFloatingOverlay(
        context: Context,
        merchant: String,
        amount: Double,
        initialCategory: String,
        channel: String,
        transactionId: Long
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 30
        }

        val composeView = ComposeView(context)

        // Floating card lifecycle mock setup for Compose in WindowManager
        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(androidx.lifecycle.Lifecycle.Event.ON_RESUME)

        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeViewModelStoreOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        var isDismissed = false
        fun dismiss() {
            if (!isDismissed) {
                isDismissed = true
                try {
                    windowManager.removeView(composeView)
                } catch (_: Exception) {}
            }
        }

        composeView.setContent {
            var selectedCategory by remember { mutableStateOf(initialCategory) }
            var rememberMerchant by remember { mutableStateOf(true) }
            var isVisible by remember { mutableStateOf(true) }

            // 6秒无操作自动确认并关闭
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(6500)
                isVisible = false
                kotlinx.coroutines.delay(300)
                dismiss()
            }

            AnimatedVisibility(
                visible = isVisible,
                enter = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(300)) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(250)) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF0F172A)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            // Top Tag: Channel badge + Close button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "${channel.ifBlank { "自动记账" }} · 识别成功",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        isVisible = false
                                        dismiss()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Merchant & Amount
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = merchant,
                                        color = Color.White,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "已自动归入【$selectedCategory】",
                                        color = Color(0xFF38BDF8),
                                        fontSize = 12.sp
                                    )
                                }

                                Text(
                                    text = "RM ${String.format("%.2f", amount)}",
                                    color = Color(0xFF34D399),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Horizontal scroll of quick category chips
                            Text(
                                text = "快速切换分类:",
                                color = Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            val displayCategories = remember(initialCategory) {
                                val list = COMMON_CATEGORIES.toMutableList()
                                if (initialCategory.isNotBlank() && list.none { it.first == initialCategory }) {
                                    list.add(0, initialCategory to "🏷️")
                                }
                                list
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                displayCategories.forEach { (catName, emoji) ->
                                    val isSelected = catName == selectedCategory
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B),
                                        modifier = Modifier.clickable {
                                            selectedCategory = catName
                                            // 立即触发后台异步分类更新
                                            sendQuickCategorizeRequest(context, transactionId, catName, merchant, rememberMerchant)
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = emoji, fontSize = 13.sp)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = catName,
                                                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Bottom actions
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { rememberMerchant = !rememberMerchant }
                                ) {
                                    Checkbox(
                                        checked = rememberMerchant,
                                        onCheckedChange = { rememberMerchant = it },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF10B981),
                                            uncheckedColor = Color(0xFF64748B)
                                        ),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "记住此商户规则",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 12.sp
                                    )
                                }

                                Button(
                                    onClick = {
                                        sendQuickCategorizeRequest(context, transactionId, selectedCategory, merchant, rememberMerchant)
                                        isVisible = false
                                        dismiss()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Confirm",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("确认", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        windowManager.addView(composeView, params)
    }

    private fun showHeadsUpNotification(
        context: Context,
        merchant: String,
        amount: Double,
        category: String,
        channel: String,
        transactionId: Long
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifChannel = NotificationChannel(
                CHANNEL_ID,
                "付款即时识别与分类",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "付完款后在通知栏快速确认商户和修正分类"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(notifChannel)
        }

        val notifId = notifIdGenerator.incrementAndGet()

        // Content intent: open App
        val contentIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("已自动记账: $merchant")
            .setContentText("RM ${String.format("%.2f", amount)} · 预选分类: $category (${channel.ifBlank { "已扣款" }})")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        // Add 3 high-frequency quick action buttons: [🍔 餐饮] [🚗 交通] [🛒 杂货]
        val quickCats = listOf("餐饮" to "🍔 餐饮", "交通" to "🚗 交通", "杂货" to "🛒 杂货")
        quickCats.forEachIndexed { idx, (catKey, label) ->
            val actionIntent = Intent(context, QuickCategoryReceiver::class.java).apply {
                action = QuickCategoryReceiver.ACTION_CATEGORIZE
                putExtra(QuickCategoryReceiver.EXTRA_TRANSACTION_ID, transactionId)
                putExtra(QuickCategoryReceiver.EXTRA_CATEGORY, catKey)
                putExtra(QuickCategoryReceiver.EXTRA_MERCHANT, merchant)
                putExtra(QuickCategoryReceiver.EXTRA_REMEMBER, true)
                putExtra(QuickCategoryReceiver.EXTRA_NOTIFICATION_ID, notifId)
            }
            val actionPendingIntent = PendingIntent.getBroadcast(
                context,
                notifId * 10 + idx,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, label, actionPendingIntent)
        }

        notificationManager.notify(notifId, builder.build())
    }

    private fun sendQuickCategorizeRequest(
        context: Context,
        transactionId: Long,
        category: String,
        merchant: String,
        remember: Boolean
    ) {
        if (transactionId <= 0) return
        val intent = Intent(context, QuickCategoryReceiver::class.java).apply {
            action = QuickCategoryReceiver.ACTION_CATEGORIZE
            putExtra(QuickCategoryReceiver.EXTRA_TRANSACTION_ID, transactionId)
            putExtra(QuickCategoryReceiver.EXTRA_CATEGORY, category)
            putExtra(QuickCategoryReceiver.EXTRA_MERCHANT, merchant)
            putExtra(QuickCategoryReceiver.EXTRA_REMEMBER, remember)
        }
        context.sendBroadcast(intent)
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
