package com.siangloh.ledger.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

expect fun platformName(): String

fun sharedHealthCheck(): String = "Ledger Shared KMP Ready (${platformName()})"

@Composable
fun LedgerSharedBootScreen() {
    val primaryTeal = Color(0xFF0F766E)
    val teal50 = Color(0xFFF0FDFA)
    val slate50 = Color(0xFFF8FAFC)
    val slate500 = Color(0xFF64748B)
    val slate900 = Color(0xFF0F172A)

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
            }
        }
    }
}
