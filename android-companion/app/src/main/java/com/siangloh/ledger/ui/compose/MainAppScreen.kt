package com.siangloh.ledger.ui.compose

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.siangloh.ledger.R
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
    onOpenSettings: () -> Unit = {},
    onOpenWebUrl: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(initialTab) }
    var cashflowMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var cashflowRevision by remember { mutableIntStateOf(0) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val navItems = listOf(
        NavItem(stringResource(R.string.nav_overview), Icons.Default.Dashboard),
        NavItem(stringResource(R.string.nav_records), Icons.Default.ReceiptLong),
        NavItem(stringResource(R.string.nav_add), Icons.Default.AddCircle, isPrimaryAction = true),
        NavItem(stringResource(R.string.nav_accounts), Icons.Default.AccountBalanceWallet),
        NavItem(stringResource(R.string.nav_more), Icons.Default.Widgets)
    )

    Scaffold(
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Slate200),
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
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = PrimaryTealSubtle,
                                selectedIconColor = PrimaryTeal,
                                unselectedIconColor = Slate400,
                                selectedTextColor = PrimaryTeal,
                                unselectedTextColor = Slate500
                            )
                        )
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
            saveableStateHolder.SaveableStateProvider(selectedTab) {
                when (selectedTab) {
                    0 -> {
                        OverviewScreen(
                            showBottomBar = false,
                            onNavigateToRecords = { selectedTab = 1 },
                            onNavigateToAdd = { selectedTab = 2 },
                            onNavigateToAccounts = { selectedTab = 3 },
                            onNavigateToTools = { selectedTab = 4 },
                            onScanReceipt = onScanReceipt,
                            onOpenSettings = onOpenSettings,
                            onNavigateToCashflow = { cashflowMonth = it },
                            cashflowRevision = cashflowRevision
                        )
                    }
                    1 -> {
                        RecordsScreen(
                            onNavigateToAdd = { selectedTab = 2 }
                        )
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
                            onNavigateToAdd = { selectedTab = 2 },
                            onOpenSettings = onOpenSettings,
                            onOpenWebUrl = onOpenWebUrl
                        )
                    }
                }
            }
            if (selectedTab == 0) {
                cashflowMonth?.let { month -> CashflowAnalysisScreen(month) { cashflowMonth = null; cashflowRevision++ } }
            }
        }
    }
}
