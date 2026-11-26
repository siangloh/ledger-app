package com.siangloh.ledger.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.platform.PlatformBiometricAuth
import com.siangloh.ledger.ui.theme.Emerald50
import com.siangloh.ledger.ui.theme.ExpenseRed
import com.siangloh.ledger.ui.theme.IncomeGreen
import com.siangloh.ledger.ui.theme.PrimaryTeal
import com.siangloh.ledger.ui.theme.Rose50
import com.siangloh.ledger.ui.theme.Slate100
import com.siangloh.ledger.ui.theme.Slate200
import com.siangloh.ledger.ui.theme.Slate50
import com.siangloh.ledger.ui.theme.Slate500
import com.siangloh.ledger.ui.theme.Slate600
import com.siangloh.ledger.ui.theme.Slate700
import com.siangloh.ledger.ui.theme.Slate900
import com.siangloh.ledger.ui.theme.Teal50
import com.siangloh.ledger.ui.theme.WarningAmber

@Composable
fun SharedSegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    activePillColor: Color = Color.White,
    activeTextColor: Color = PrimaryTeal
) {
    if (options.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, options.lastIndex)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = Slate100,
        border = BorderStroke(1.dp, Slate200)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
        ) {
            val tabWidth = maxWidth / options.size
            val indicatorOffset by animateDpAsState(
                targetValue = tabWidth * safeIndex,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "segmented_pill_offset"
            )

            Surface(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(tabWidth)
                    .fillMaxHeight(),
                shape = RoundedCornerShape(11.dp),
                color = activePillColor,
                border = BorderStroke(0.5.dp, Slate200),
                shadowElevation = 1.dp
            ) {}

            Row(modifier = Modifier.fillMaxSize()) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == safeIndex
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) activeTextColor else Slate600,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "segmented_text_color"
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (index != safeIndex) {
                                    onSelect(index)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = textColor,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SharedMonthSwitcher(
    yearMonth: String,
    slideDir: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onPrev) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = "Previous month",
                    tint = Slate700
                )
            }

            AnimatedContent(
                targetState = yearMonth,
                transitionSpec = {
                    if (slideDir >= 0) {
                        (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally { width -> -width / 3 } + fadeIn()) togetherWith
                            (slideOutHorizontally { width -> width / 3 } + fadeOut())
                    }
                },
                label = "shared_month_switcher"
            ) { targetMonth ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CalendarMonth,
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = targetMonth,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                }
            }

            IconButton(onClick = onNext) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Next month",
                    tint = Slate700
                )
            }
        }
    }
}

private data class CategoryVisualSpec(
    val icon: ImageVector,
    val tint: Color,
    val background: Color
)

private val AmberBg = Color(0xFFFFFBEB)
private val SkyTint = Color(0xFF0284C7)
private val SkyBg = Color(0xFFF0F9FF)
private val OrangeTint = Color(0xFFEA580C)
private val OrangeBg = Color(0xFFFFF7ED)
private val IndigoTint = Color(0xFF4F46E5)
private val IndigoBg = Color(0xFFEEF2FF)
private val VioletTint = Color(0xFF7C3AED)
private val VioletBg = Color(0xFFF5F3FF)

private fun resolveCategoryVisualSpec(category: String, type: String): CategoryVisualSpec {
    val clean = SharedLedgerEngine.cleanCategoryName(category).trim().lowercase()
    val normalizedType = type.trim().lowercase()

    if (normalizedType == "account") {
        return when (clean) {
            "savings" -> CategoryVisualSpec(Icons.Rounded.Savings, PrimaryTeal, Teal50)
            "current" -> CategoryVisualSpec(Icons.Rounded.AccountBalance, SkyTint, SkyBg)
            "credit_card" -> CategoryVisualSpec(Icons.Rounded.CreditCard, IndigoTint, IndigoBg)
            "e_wallet" -> CategoryVisualSpec(Icons.Rounded.AccountBalanceWallet, IncomeGreen, Emerald50)
            "investment" -> CategoryVisualSpec(Icons.AutoMirrored.Rounded.TrendingUp, WarningAmber, AmberBg)
            "cash" -> CategoryVisualSpec(Icons.Rounded.Payments, IncomeGreen, Emerald50)
            else -> CategoryVisualSpec(Icons.Rounded.AccountBalanceWallet, Slate600, Slate100)
        }
    }

    if (
        normalizedType == "transfer" ||
        clean.contains("内部") ||
        clean.contains("划转") ||
        clean.contains("自转") ||
        clean.contains("转账") ||
        clean.contains("transfer") ||
        clean.contains("pindahan")
    ) {
        return CategoryVisualSpec(Icons.Rounded.SwapHoriz, Slate700, Slate100)
    }

    if (normalizedType == "savings") {
        return when {
            clean.contains("定期") || clean.contains("存款") || clean.contains("fixed") || clean.contains("deposit") ->
                CategoryVisualSpec(Icons.Rounded.AccountBalance, PrimaryTeal, Teal50)
            clean.contains("应急") || clean.contains("备用") || clean.contains("emergency") ->
                CategoryVisualSpec(Icons.Rounded.Shield, IncomeGreen, Emerald50)
            clean.contains("投资") || clean.contains("理财") || clean.contains("基金") || clean.contains("股票") || clean.contains("invest") ->
                CategoryVisualSpec(Icons.AutoMirrored.Rounded.ShowChart, SkyTint, SkyBg)
            clean.contains("车") || clean.contains("car") ->
                CategoryVisualSpec(Icons.Rounded.DirectionsCar, SkyTint, SkyBg)
            clean.contains("房") || clean.contains("house") || clean.contains("home") ->
                CategoryVisualSpec(Icons.Rounded.Home, PrimaryTeal, Teal50)
            clean.contains("心愿") || clean.contains("愿望") || clean.contains("wish") || clean.contains("dream") ->
                CategoryVisualSpec(Icons.Rounded.Star, WarningAmber, AmberBg)
            clean.contains("教育") || clean.contains("学费") || clean.contains("edu") ->
                CategoryVisualSpec(Icons.Rounded.School, SkyTint, SkyBg)
            clean.contains("退休") || clean.contains("养老") || clean.contains("retire") ->
                CategoryVisualSpec(Icons.Rounded.BeachAccess, OrangeTint, OrangeBg)
            clean.contains("黄金") || clean.contains("gold") ->
                CategoryVisualSpec(Icons.Rounded.MonetizationOn, WarningAmber, AmberBg)
            else ->
                CategoryVisualSpec(Icons.Rounded.Savings, PrimaryTeal, Teal50)
        }
    }

    if (normalizedType == "income") {
        return when {
            clean.contains("工资") || clean.contains("薪水") || clean.contains("salary") || clean.contains("gaji") ->
                CategoryVisualSpec(Icons.Rounded.AccountBalanceWallet, IncomeGreen, Emerald50)
            clean.contains("奖金") || clean.contains("分红") || clean.contains("bonus") ->
                CategoryVisualSpec(Icons.Rounded.CardGiftcard, WarningAmber, AmberBg)
            clean.contains("投资") || clean.contains("理财") || clean.contains("利息") || clean.contains("invest") || clean.contains("pelaburan") ->
                CategoryVisualSpec(Icons.AutoMirrored.Rounded.TrendingUp, PrimaryTeal, Teal50)
            clean.contains("兼职") || clean.contains("副业") || clean.contains("part-time") || clean.contains("sampingan") ->
                CategoryVisualSpec(Icons.Rounded.Work, SkyTint, SkyBg)
            clean.contains("退款") || clean.contains("报销") || clean.contains("返现") || clean.contains("回扣") ||
                clean.contains("refund") || clean.contains("rebate") || clean.contains("cashback") || clean.contains("reimburse") ->
                CategoryVisualSpec(Icons.Rounded.Receipt, PrimaryTeal, Teal50)
            else ->
                CategoryVisualSpec(Icons.Rounded.Payments, IncomeGreen, Emerald50)
        }
    }

    return when {
        clean.contains("餐") || clean.contains("外卖") || clean.contains("美食") || clean.contains("早餐") ||
            clean.contains("午餐") || clean.contains("晚餐") || clean.contains("food") || clean.contains("makan") || clean.contains("dining") ->
            CategoryVisualSpec(Icons.Rounded.Restaurant, OrangeTint, OrangeBg)
        clean.contains("交通") || clean.contains("打车") || clean.contains("油费") || clean.contains("停车") ||
            clean.contains("grab") || clean.contains("transport") || clean.contains("pengangkutan") ->
            CategoryVisualSpec(Icons.Rounded.DirectionsCar, SkyTint, SkyBg)
        clean.contains("杂货") || clean.contains("超市") || clean.contains("生活") || clean.contains("grocer") || clean.contains("runcit") ->
            CategoryVisualSpec(Icons.Rounded.ShoppingCart, IncomeGreen, Emerald50)
        clean.contains("通讯") || clean.contains("通信") || clean.contains("话费") || clean.contains("网费") ||
            clean.contains("宽带") || clean.contains("telecom") || clean.contains("phone") || clean.contains("mobile") || clean.contains("internet") ->
            CategoryVisualSpec(Icons.Rounded.PhoneIphone, IndigoTint, IndigoBg)
        clean.contains("账单") || clean.contains("水费") || clean.contains("电费") ||
            clean.contains("bill") || clean.contains("bil") || clean.contains("utilit") ->
            CategoryVisualSpec(Icons.Rounded.Bolt, WarningAmber, AmberBg)
        clean.contains("购") || clean.contains("衣服") || clean.contains("鞋") || clean.contains("shop") || clean.contains("beli") ->
            CategoryVisualSpec(Icons.Rounded.ShoppingBag, ExpenseRed, Rose50)
        clean.contains("娱乐") || clean.contains("电影") || clean.contains("游戏") || clean.contains("聚会") ||
            clean.contains("game") || clean.contains("entertain") || clean.contains("hiburan") ->
            CategoryVisualSpec(Icons.Rounded.SportsEsports, VioletTint, VioletBg)
        clean.contains("医疗") || clean.contains("看病") || clean.contains("药") || clean.contains("保健") ||
            clean.contains("health") || clean.contains("medic") || clean.contains("perubatan") ->
            CategoryVisualSpec(Icons.Rounded.MedicalServices, ExpenseRed, Rose50)
        clean.contains("住") || clean.contains("房租") || clean.contains("物业") || clean.contains("rent") || clean.contains("sewa") || clean.contains("housing") ->
            CategoryVisualSpec(Icons.Rounded.Home, PrimaryTeal, Teal50)
        clean.contains("教") || clean.contains("学") || clean.contains("书") || clean.contains("edu") || clean.contains("pendidikan") ->
            CategoryVisualSpec(Icons.Rounded.School, SkyTint, SkyBg)
        clean.contains("宠") || clean.contains("pet") ->
            CategoryVisualSpec(Icons.Rounded.Pets, WarningAmber, AmberBg)
        clean.contains("旅") || clean.contains("酒店") || clean.contains("机票") || clean.contains("travel") || clean.contains("melancong") || clean.contains("cuti") ->
            CategoryVisualSpec(Icons.Rounded.Flight, SkyTint, SkyBg)
        clean.contains("储蓄") || clean.contains("理财") || clean.contains("saving") || clean.contains("simpanan") ->
            CategoryVisualSpec(Icons.Rounded.Savings, PrimaryTeal, Teal50)
        else ->
            CategoryVisualSpec(Icons.Rounded.Category, Slate600, Slate100)
    }
}

@Composable
fun SharedCategoryBadge(
    category: String,
    type: String,
    size: Dp = 38.dp
) {
    val spec = remember(category, type) { resolveCategoryVisualSpec(category, type) }
    val iconSize = (size.value * 0.52f).dp

    Surface(
        shape = CircleShape,
        color = spec.background,
        modifier = Modifier.size(size)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                tint = spec.tint,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
fun SharedAppLockOverlay(onUnlocked: () -> Unit) {
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun triggerUnlock() {
        statusMessage = null
        if (!PlatformBiometricAuth.isAvailable()) {
            onUnlocked()
            return
        }
        PlatformBiometricAuth.authenticate(
            title = "Unlock Ledger",
            subtitle = "Verify your identity to access your financial records",
            cancelLabel = "Cancel"
        ) { success, error ->
            if (success) {
                onUnlocked()
            } else {
                statusMessage = error?.takeIf { it.isNotBlank() } ?: "Verification cancelled. Tap below to try again."
            }
        }
    }

    LaunchedEffect(Unit) {
        triggerUnlock()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate50
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = Teal50,
                border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.25f)),
                modifier = Modifier.size(84.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Privacy Lock Active",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Slate900,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your balances and transaction history are protected.",
                fontSize = 14.sp,
                color = Slate500,
                textAlign = TextAlign.Center
            )

            if (!statusMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Rose50,
                    border = BorderStroke(1.dp, ExpenseRed.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = statusMessage ?: "",
                        fontSize = 13.sp,
                        color = ExpenseRed,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = { triggerUnlock() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryTeal,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Unlock with Biometrics",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
