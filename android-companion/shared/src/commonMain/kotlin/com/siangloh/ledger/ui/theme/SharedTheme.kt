package com.siangloh.ledger.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PrimaryTeal = Color(0xFF0F766E)
val Teal50 = Color(0xFFF0FDFA)
val Slate50 = Color(0xFFF8FAFC)
val Slate100 = Color(0xFFF1F5F9)
val Slate200 = Color(0xFFE2E8F0)
val Slate400 = Color(0xFF94A3B8)
val Slate500 = Color(0xFF64748B)
val Slate600 = Color(0xFF475569)
val Slate700 = Color(0xFF334155)
val Slate800 = Color(0xFF1E293B)
val Slate900 = Color(0xFF0F172A)
val ExpenseRed = Color(0xFFDC2626)
val Rose50 = Color(0xFFFFF1F2)
val IncomeGreen = Color(0xFF059669)
val Emerald50 = Color(0xFFECFDF5)
val WarningAmber = Color(0xFFD97706)

private val LedgerSharedLightColorScheme = lightColorScheme(
    primary = PrimaryTeal,
    onPrimary = Color.White,
    primaryContainer = Teal50,
    onPrimaryContainer = Slate900,
    secondary = Slate600,
    onSecondary = Color.White,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate800,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate700,
    surfaceTint = Color.Transparent,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Slate200,
    outlineVariant = Slate100,
    error = ExpenseRed,
    onError = Color.White,
    errorContainer = Rose50,
    onErrorContainer = ExpenseRed
)

@Composable
fun LedgerSharedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LedgerSharedLightColorScheme,
        content = content
    )
}
