package com.siangloh.ledger.ui.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.ui.compose.theme.*

data class NavItem(
    val title: String,
    val icon: ImageVector,
    val isPrimaryAction: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    initialTab: Int = 0,
    onScanReceipt: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(initialTab) }

    val navItems = listOf(
        NavItem("概览", Icons.Default.Dashboard),
        NavItem("流水", Icons.Default.ReceiptLong),
        NavItem("记一笔", Icons.Default.AddCircle, isPrimaryAction = true),
        NavItem("资产", Icons.Default.AccountBalanceWallet),
        NavItem("更多", Icons.Default.Widgets)
    )

    Scaffold(
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = Color.White
            ) {
                NavigationBar(
                    containerColor = Color.White,
                    contentColor = Slate600,
                    tonalElevation = 0.dp,
                    modifier = Modifier.navigationBarsPadding()
                ) {
                    navItems.forEachIndexed { index, item ->
                        val isSelected = selectedTab == index

                        if (item.isPrimaryAction) {
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectedTab = index },
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(46.dp)
                                            .clip(CircleShape)
                                            .background(PrimaryBlue)
                                            .shadow(4.dp, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = item.title,
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = item.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) PrimaryBlue else Slate600
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent,
                                    selectedIconColor = Color.White,
                                    unselectedIconColor = Color.White,
                                    selectedTextColor = PrimaryBlue,
                                    unselectedTextColor = Slate600
                                )
                            )
                        } else {
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectedTab = index },
                                icon = {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        modifier = Modifier.size(24.dp)
                                    )
                                },
                                label = {
                                    Text(
                                        text = item.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = PrimaryBlue.copy(alpha = 0.12f),
                                    selectedIconColor = PrimaryBlue,
                                    unselectedIconColor = Slate400,
                                    selectedTextColor = PrimaryBlue,
                                    unselectedTextColor = Slate500
                                )
                            )
                        }
                    }
                }
            }
        },
        containerColor = Slate50
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = paddingValues.calculateBottomPadding())
        ) {
            when (selectedTab) {
                0 -> {
                    OverviewScreen(
                        showBottomBar = false,
                        onNavigateToRecords = { selectedTab = 1 },
                        onNavigateToAdd = { selectedTab = 2 },
                        onNavigateToAccounts = { selectedTab = 3 },
                        onNavigateToTools = { selectedTab = 4 },
                        onScanReceipt = onScanReceipt,
                        onOpenSettings = { selectedTab = 4 }
                    )
                }
                1 -> {
                    RecordsScreen()
                }
                2 -> {
                    AddTransactionScreen(
                        onFinished = {
                            selectedTab = 1
                        }
                    )
                }
                3 -> {
                    AccountsScreen()
                }
                4 -> {
                    MoreToolsScreen(
                        onScanReceipt = onScanReceipt,
                        onNavigateToAdd = { selectedTab = 2 }
                    )
                }
            }
        }
    }
}
