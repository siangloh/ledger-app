package com.siangloh.ledger.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.core.format.currentMonthString
import com.siangloh.ledger.data.SharedLedgerEngine

expect fun platformName(): String

fun sharedHealthCheck(): String = "Ledger Shared KMP Ready (${platformName()})"

@Composable
fun LedgerSharedBootScreen() {
    val primaryTeal = Color(0xFF0F766E)
    val teal50 = Color(0xFFF0FDFA)
    val emerald500 = Color(0xFF10B981)
    val rose50 = Color(0xFFFFF1F2)
    val rose700 = Color(0xFFBE123C)
    val slate50 = Color(0xFFF8FAFC)
    val slate500 = Color(0xFF64748B)
    val slate900 = Color(0xFF0F172A)

    var sqliteStatus by remember { mutableStateOf("Initializing Offline SQLite…") }
    var hasSqliteError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            SharedLedgerEngine.initDefaultsIfNeeded()
            val summary = SharedLedgerEngine.getAccountsSummary(currentMonthString())
            sqliteStatus = "Offline SQLite Ready · ${summary.items.size} Accounts"
            hasSqliteError = false
        } catch (e: Throwable) {
            sqliteStatus = "SQLite Error: ${e.message ?: e::class.simpleName}"
            hasSqliteError = true
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = slate50
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = teal50,
                    border = BorderStroke(1.dp, primaryTeal.copy(alpha = 0.25f)),
                    modifier = Modifier.size(84.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.AccountBalanceWallet,
                            contentDescription = null,
                            tint = primaryTeal,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Ledger",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = slate900
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Personal Finance · ${platformName()}",
                    fontSize = 14.sp,
                    color = slate500
                )
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (hasSqliteError) rose50 else teal50,
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (hasSqliteError) rose700.copy(alpha = 0.25f) else primaryTeal.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    color = if (hasSqliteError) rose700 else emerald500,
                                    shape = CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = sqliteStatus,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasSqliteError) rose700 else primaryTeal
                        )
                    }
                }
            }
        }
    }
}
