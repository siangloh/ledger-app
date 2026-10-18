package com.siangloh.ledger.ui.compose.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Emerald500,
    secondary = Sky400,
    background = Slate900,
    surface = Slate800,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Slate50,
    onSurface = Slate50
)

private val LightColorScheme = lightColorScheme(
    primary = Emerald600,
    secondary = Sky500,
    background = Slate50,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Slate900,
    onSurface = Slate900
)

@Composable
fun LedgerTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    // 强制使用明亮、高对比度主题语义色彩，杜绝系统深色模式下与硬编码卡片颜色混用产生的“深底深字”或低对比度问题
    MaterialTheme(
        colorScheme = LightColorScheme,
        content = content
    )
}
