package com.siangloh.ledger.ui.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LightColorScheme = lightColorScheme(
    primary = Teal700,
    onPrimary = Color.White,
    primaryContainer = Teal50,
    onPrimaryContainer = Teal900,
    secondary = Slate600,
    onSecondary = Color.White,
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
    error = ExpenseRed,
    onError = Color.White
)

val DarkColorScheme = darkColorScheme(
    primary = Teal300,
    onPrimary = Teal900,
    primaryContainer = Teal800,
    onPrimaryContainer = Teal100,
    secondary = Slate400,
    onSecondary = Slate950,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate300,
    outline = Slate700,
    error = Color(0xFFEF4444),
    onError = Color.White
)

@Composable
fun ledgerInputColors(
    containerColor: Color = Color.White
) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Slate900,
    unfocusedTextColor = Slate900,
    focusedContainerColor = containerColor,
    unfocusedContainerColor = containerColor,
    focusedBorderColor = PrimaryTeal,
    unfocusedBorderColor = Slate300,
    focusedLabelColor = PrimaryTeal,
    unfocusedLabelColor = Slate600,
    focusedPlaceholderColor = Slate400,
    unfocusedPlaceholderColor = Slate400,
    cursorColor = PrimaryTeal
)

@Composable
fun LedgerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
