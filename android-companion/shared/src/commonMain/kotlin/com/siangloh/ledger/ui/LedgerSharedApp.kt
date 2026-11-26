package com.siangloh.ledger.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.core.prefs.PlatformPrefs
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.platform.LocaleStateHolder
import com.siangloh.ledger.platform.PlatformToastBus
import com.siangloh.ledger.ui.components.SharedAppLockOverlay
import com.siangloh.ledger.ui.screens.SharedAddTab
import com.siangloh.ledger.ui.screens.SharedAnalyticsAccountsTab
import com.siangloh.ledger.ui.screens.SharedMoreTab
import com.siangloh.ledger.ui.screens.SharedOverviewTab
import com.siangloh.ledger.ui.screens.SharedRecordsTab
import com.siangloh.ledger.ui.theme.LedgerSharedTheme
import com.siangloh.ledger.ui.theme.PrimaryTeal
import com.siangloh.ledger.ui.theme.Slate200
import com.siangloh.ledger.ui.theme.Slate400
import com.siangloh.ledger.ui.theme.Slate50
import com.siangloh.ledger.ui.theme.Slate500
import com.siangloh.ledger.ui.theme.Slate600
import com.siangloh.ledger.ui.theme.Teal50
import kotlinx.coroutines.flow.collectLatest

private const val SECURITY_PREFS_NAME = "ledger_security_prefs"
private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"

private data class SharedNavTab(
    val index: Int,
    val icon: ImageVector,
    val labelZh: String,
    val labelEn: String,
    val labelMs: String
) {
    fun label(lang: String): String = when (lang) {
        "en" -> labelEn
        "ms" -> labelMs
        else -> labelZh
    }
}

private val SHARED_NAV_TABS = listOf(
    SharedNavTab(0, Icons.Rounded.Home, "概览", "Overview", "Ringkasan"),
    SharedNavTab(1, Icons.Rounded.ReceiptLong, "账单", "Records", "Rekod"),
    SharedNavTab(2, Icons.Rounded.AddCircle, "记账", "Add", "Catat"),
    SharedNavTab(3, Icons.Rounded.PieChart, "分析", "Analytics", "Analisis"),
    SharedNavTab(4, Icons.Rounded.Tune, "更多", "More", "Lagi")
)

@Composable
fun LedgerSharedApp() {
    LedgerSharedTheme {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }
        var dataRevision by remember { mutableIntStateOf(0) }
        var isLocked by remember {
            mutableStateOf(
                runCatching {
                    PlatformPrefs(SECURITY_PREFS_NAME).getBoolean(KEY_APP_LOCK_ENABLED, false)
                }.getOrDefault(false)
            )
        }
        val currentLang by LocaleStateHolder.language.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        LaunchedEffect(Unit) {
            runCatching {
                SharedLedgerEngine.initDefaultsIfNeeded()
            }
            dataRevision++
        }

        LaunchedEffect(snackbarHostState) {
            PlatformToastBus.messages.collectLatest { message ->
                if (message.isNotBlank()) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Slate50)
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Slate50,
                contentWindowInsets = WindowInsets.statusBars,
                snackbarHost = {
                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { data ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Slate200),
                            shadowElevation = 4.dp
                        ) {
                            Snackbar(
                                snackbarData = data,
                                containerColor = Color.White,
                                contentColor = Slate600,
                                actionColor = PrimaryTeal,
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                    }
                },
                bottomBar = {
                    Surface(
                        color = Color.White,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp
                    ) {
                        Column {
                            HorizontalDivider(thickness = 1.dp, color = Slate200)
                            NavigationBar(
                                containerColor = Color.White,
                                tonalElevation = 0.dp,
                                windowInsets = WindowInsets.navigationBars
                            ) {
                                SHARED_NAV_TABS.forEach { tab ->
                                    val isSelected = selectedTab == tab.index
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = { selectedTab = tab.index },
                                        icon = {
                                            Icon(
                                                imageVector = tab.icon,
                                                contentDescription = tab.label(currentLang),
                                                modifier = Modifier.size(if (tab.index == 2) 26.dp else 22.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = tab.label(currentLang),
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = PrimaryTeal,
                                            selectedTextColor = PrimaryTeal,
                                            indicatorColor = Teal50,
                                            unselectedIconColor = Slate400,
                                            unselectedTextColor = Slate500
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    Crossfade(
                        targetState = selectedTab,
                        label = "ledger_shared_tab"
                    ) { tabIndex ->
                        when (tabIndex) {
                            0 -> SharedOverviewTab(
                                dataRevision = dataRevision,
                                onNavigateToAdd = { selectedTab = 2 },
                                onNavigateToRecords = { selectedTab = 1 }
                            )
                            1 -> SharedRecordsTab(
                                dataRevision = dataRevision,
                                onDataChanged = { dataRevision++ },
                                onNavigateToAdd = { selectedTab = 2 }
                            )
                            2 -> SharedAddTab(
                                dataRevision = dataRevision,
                                onSaved = {
                                    dataRevision++
                                    selectedTab = 0
                                }
                            )
                            3 -> SharedAnalyticsAccountsTab(
                                dataRevision = dataRevision,
                                onDataChanged = { dataRevision++ }
                            )
                            else -> SharedMoreTab(
                                dataRevision = dataRevision,
                                onDataChanged = { dataRevision++ }
                            )
                        }
                    }
                }
            }

            if (isLocked) {
                SharedAppLockOverlay(
                    onUnlocked = { isLocked = false }
                )
            }
        }
    }
}
