package com.siangloh.ledger.ui.compose.theme

import androidx.compose.ui.graphics.Color

// =========================================================================
// 1. 基础中性色标 (Neutral Scale: 灰白 + 深灰)
// =========================================================================
val Slate950 = Color(0xFF0B1220) // Dark theme background
val Slate900 = Color(0xFF111827) // Dark theme surface / Light textPrimary
val Slate800 = Color(0xFF1E293B)
val Slate700 = Color(0xFF334155) // Dark border
val Slate600 = Color(0xFF475569) // Light textSecondary / Transfer
val Slate500 = Color(0xFF64748B) // Light textMuted
val Slate400 = Color(0xFF94A3B8)
val Slate300 = Color(0xFFCBD5E1)
val Slate200 = Color(0xFFE2E8F0) // Light border
val Slate100 = Color(0xFFF1F5F9) // Light surfaceSubtle
val Slate50  = Color(0xFFF8FAFC) // Light background

// =========================================================================
// 2. 深青品牌色标 (Deep Teal / Cyan - 彻底去除默认紫色)
// =========================================================================
val Teal900 = Color(0xFF042F2E) // Dark onPrimary
val Teal800 = Color(0xFF115E59) // Hover / Pressed
val Teal700 = Color(0xFF0F766E) // Primary Light (#0F766E)
val Teal600 = Color(0xFF0D9488)
val Teal500 = Color(0xFF14B8A6)
val Teal300 = Color(0xFF5EEAD4) // Primary Dark (#5EEAD4)
val Teal100 = Color(0xFFCCFBF1)
val Teal50  = Color(0xFFF0FDFA) // Primary Subtle Light (#F0FDFA)

// =========================================================================
// 3. 核心语义色彩 (Semantic Roles)
// =========================================================================
val PrimaryTeal       = Teal700
val PrimaryTealHover  = Teal800
val PrimaryTealSubtle = Teal50
val OnPrimaryTeal     = Color.White

val IncomeGreen  = Color(0xFF15803D) // 收入/成功 (#15803D)
val ExpenseRed   = Color(0xFFB91C1C) // 支出/删除/失败 (#B91C1C)
val WarningAmber = Color(0xFFB45309) // 待处理/风险 (#B45309)
val TransferGray = Color(0xFF475569) // 内部转账/中性说明 (#475569)

// 兼容别名（平滑替换原有 PrimaryBlue 和 Indigo600，指向深青品牌色）
val PrimaryBlue  = PrimaryTeal
val Indigo600    = PrimaryTeal
val Indigo50     = Teal50
val Indigo100    = Teal100

// 辅助色 (保留供徽标/提示使用)
val Amber50  = Color(0xFFFFFBEB)
val Amber100 = Color(0xFFFEF3C7)
val Amber200 = Color(0xFFFDE68A)
val Amber500 = Color(0xFFF59E0B)
val Amber600 = Color(0xFFD97706)
val Amber700 = Color(0xFFB45309)

val Emerald400 = Color(0xFF34D399)
val Emerald500 = IncomeGreen
val Emerald600 = IncomeGreen

val Sky50 = Color(0xFFF0F9FF)
val Sky100 = Color(0xFFE0F2FE)
val Sky200 = Color(0xFFBAE6FD)
val Sky400 = Color(0xFF38BDF8)
val Sky500 = Color(0xFF0EA5E9)
val Sky600 = Color(0xFF0284C7)
val Sky700 = Color(0xFF0369A1)

val Rose50 = Color(0xFFFFF1F2)
val Rose100 = Color(0xFFFFE4E6)
val Rose200 = Color(0xFFFECDD3)
val Rose500 = ExpenseRed
val Rose600 = Color(0xFFE11D48)

val Emerald50 = Color(0xFFECFDF5)
val Emerald100 = Color(0xFFD1FAE5)
val Emerald200 = Color(0xFFA7F3D0)
val Emerald700 = Color(0xFF047857)
