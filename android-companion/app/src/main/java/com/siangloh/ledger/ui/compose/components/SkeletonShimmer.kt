package com.siangloh.ledger.ui.compose.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.siangloh.ledger.ui.compose.theme.Slate100
import com.siangloh.ledger.ui.compose.theme.Slate200
import com.siangloh.ledger.ui.compose.theme.Slate50

/**
 * 丝滑线性扫光骨架屏动画修饰符 (Skeleton Shimmer Modifier)
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnim by transition.animateFloat(
        initialValue = -350f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerColors = listOf(
        Color(0xFFE2E8F0), // Slate 200
        Color(0xFFF8FAFC), // Slate 50 (动态高光扫光)
        Color(0xFFE2E8F0)  // Slate 200
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 350f, translateAnim - 350f),
        end = Offset(translateAnim, translateAnim)
    )

    this.background(brush)
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .shimmerEffect()
    )
}

@Composable
fun SkeletonCircle(
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    SkeletonBox(
        modifier = modifier.size(size),
        shape = CircleShape
    )
}

// =============================================================================
// 1. 概览页局部卡片骨架屏 (Component-Level Skeletons)
// 保证顶部状态栏与周期选择器 (PeriodSelector) 始终常驻显示，仅对下方数据卡片展示骨架
// =============================================================================
@Composable
fun SpendingHeroCardSkeleton(
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF0F172A),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(modifier = Modifier.size(width = 80.dp, height = 14.dp))
                SkeletonBox(modifier = Modifier.size(width = 90.dp, height = 12.dp))
            }
            Spacer(modifier = Modifier.height(10.dp))
            SkeletonBox(modifier = Modifier.size(width = 190.dp, height = 32.dp))
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                SkeletonBox(modifier = Modifier.size(width = 110.dp, height = 14.dp))
                SkeletonBox(modifier = Modifier.size(width = 110.dp, height = 14.dp))
            }
        }
    }
}

@Composable
fun SpendingTrendSkeleton(
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(modifier = Modifier.size(width = 90.dp, height = 16.dp))
                SkeletonBox(modifier = Modifier.size(width = 110.dp, height = 14.dp))
            }
            Spacer(modifier = Modifier.height(16.dp))
            SkeletonBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

@Composable
fun RecentTransactionsSkeleton(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SkeletonBox(modifier = Modifier.size(width = 110.dp, height = 18.dp))
        repeat(3) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonCircle(size = 40.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBox(modifier = Modifier.size(width = 120.dp, height = 15.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonBox(modifier = Modifier.size(width = 80.dp, height = 11.dp))
                    }
                    SkeletonBox(modifier = Modifier.size(width = 75.dp, height = 18.dp))
                }
            }
        }
    }
}

// =============================================================================
// 全屏冷启动备用骨架屏 (Overview Fullscreen Skeleton)
// =============================================================================
@Composable
fun OverviewSkeletonScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Slate50)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 顶栏状态与设置骨架
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonBox(modifier = Modifier.size(width = 150.dp, height = 30.dp), shape = RoundedCornerShape(15.dp))
            SkeletonCircle(size = 32.dp)
        }

        // 月份切换器骨架
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(42.dp), shape = RoundedCornerShape(12.dp))

        // 资产结余总览大卡片骨架
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                SkeletonBox(modifier = Modifier.size(width = 90.dp, height = 18.dp))
                Spacer(modifier = Modifier.height(12.dp))
                SkeletonBox(modifier = Modifier.size(width = 200.dp, height = 38.dp))
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SkeletonBox(modifier = Modifier.weight(1f).height(65.dp), shape = RoundedCornerShape(12.dp))
                    SkeletonBox(modifier = Modifier.weight(1f).height(65.dp), shape = RoundedCornerShape(12.dp))
                }
            }
        }

        // 快捷操作骨架行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            repeat(4) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SkeletonCircle(size = 46.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBox(modifier = Modifier.size(width = 44.dp, height = 12.dp))
                }
            }
        }

        // 待整理收件箱骨架
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(72.dp), shape = RoundedCornerShape(16.dp))

        // 近期流水标题与条目骨架
        SkeletonBox(modifier = Modifier.size(width = 110.dp, height = 20.dp))
        repeat(3) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonCircle(size = 40.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBox(modifier = Modifier.size(width = 120.dp, height = 15.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonBox(modifier = Modifier.size(width = 80.dp, height = 11.dp))
                    }
                    SkeletonBox(modifier = Modifier.size(width = 75.dp, height = 18.dp))
                }
            }
        }
    }
}

// =============================================================================
// 2. 流水明细页骨架屏 (Records Skeleton Screen)
// =============================================================================
@Composable
fun RecordsSkeletonList(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 月度汇总统计条骨架
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate100),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                repeat(3) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SkeletonBox(modifier = Modifier.size(width = 45.dp, height = 12.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonBox(modifier = Modifier.size(width = 70.dp, height = 16.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        SkeletonBox(modifier = Modifier.size(width = 90.dp, height = 14.dp))

        // 流水条目列表骨架
        repeat(5) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonCircle(size = 40.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBox(modifier = Modifier.size(width = 130.dp, height = 15.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonBox(modifier = Modifier.size(width = 90.dp, height = 12.dp))
                    }
                    SkeletonBox(modifier = Modifier.size(width = 70.dp, height = 18.dp))
                }
            }
        }
    }
}

// =============================================================================
// 3. 资产账户页骨架屏 (Accounts Skeleton Screen)
// =============================================================================
@Composable
fun AccountsSkeletonScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 净资产主卡片骨架
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                SkeletonBox(modifier = Modifier.size(width = 100.dp, height = 15.dp))
                Spacer(modifier = Modifier.height(10.dp))
                SkeletonBox(modifier = Modifier.size(width = 190.dp, height = 36.dp))
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SkeletonBox(modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(10.dp))
                    SkeletonBox(modifier = Modifier.weight(1f).height(60.dp), shape = RoundedCornerShape(10.dp))
                }
            }
        }

        // 操作按钮行骨架
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SkeletonBox(modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp))
            SkeletonBox(modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(12.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))
        SkeletonBox(modifier = Modifier.size(width = 130.dp, height = 18.dp))

        // 账户卡片列表骨架
        repeat(4) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkeletonCircle(size = 42.dp)
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        SkeletonBox(modifier = Modifier.size(width = 110.dp, height = 16.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        SkeletonBox(modifier = Modifier.size(width = 65.dp, height = 12.dp))
                    }
                    SkeletonBox(modifier = Modifier.size(width = 85.dp, height = 20.dp))
                }
            }
        }
    }
}

// =============================================================================
// 4. 更多工具页骨架屏 (More Tools Skeleton Screen)
// =============================================================================
@Composable
fun MoreToolsSkeletonScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 功能中心主卡片骨架
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(160.dp), shape = RoundedCornerShape(16.dp))

        // 订阅大厅骨架卡片
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(100.dp), shape = RoundedCornerShape(16.dp))

        // 负债分期骨架卡片
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(100.dp), shape = RoundedCornerShape(16.dp))

        // 系统设置骨架卡片
        SkeletonBox(modifier = Modifier.fillMaxWidth().height(180.dp), shape = RoundedCornerShape(16.dp))
    }
}
