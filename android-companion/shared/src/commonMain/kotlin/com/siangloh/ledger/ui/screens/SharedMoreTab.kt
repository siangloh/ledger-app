package com.siangloh.ledger.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.core.prefs.PlatformPrefs
import com.siangloh.ledger.data.LedgerBackupCore
import com.siangloh.ledger.platform.LocaleStateHolder
import com.siangloh.ledger.platform.PlatformBiometricAuth
import com.siangloh.ledger.platform.PlatformCapabilities
import com.siangloh.ledger.platform.sharePlatformTextFile
import com.siangloh.ledger.platform.showPlatformToast
import com.siangloh.ledger.ui.components.SharedSegmentedTabs
import com.siangloh.ledger.ui.i18n.LedgerUiStrings
import com.siangloh.ledger.ui.i18n.SharedStrings
import com.siangloh.ledger.ui.theme.Emerald50
import com.siangloh.ledger.ui.theme.IncomeGreen
import com.siangloh.ledger.ui.theme.PrimaryTeal
import com.siangloh.ledger.ui.theme.Slate100
import com.siangloh.ledger.ui.theme.Slate200
import com.siangloh.ledger.ui.theme.Slate50
import com.siangloh.ledger.ui.theme.Slate500
import com.siangloh.ledger.ui.theme.Slate600
import com.siangloh.ledger.ui.theme.Slate700
import com.siangloh.ledger.ui.theme.Slate900
import com.siangloh.ledger.ui.theme.Teal50
import kotlinx.coroutines.launch

private const val SECURITY_PREFS_NAME = "ledger_security_prefs"
private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"

private val LanguageCodes = listOf("zh", "en", "ms")
private val LanguageLabels = listOf("中文", "English", "Melayu")

private data class ExportDialogInfo(
    val title: String,
    val summary: String,
    val detail: String
)

@Composable
fun SharedMoreTab(
    dataRevision: Int,
    onDataChanged: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val securityPrefs = remember { PlatformPrefs(SECURITY_PREFS_NAME) }
    var appLockEnabled by remember(dataRevision) {
        mutableStateOf(securityPrefs.getBoolean(KEY_APP_LOCK_ENABLED, false))
    }

    val currentLang by LocaleStateHolder.language.collectAsState()
    val s = remember(currentLang) { SharedStrings.forLanguage(currentLang) }
    val selectedLangIndex = LanguageCodes.indexOf(currentLang).coerceAtLeast(0)

    var isExporting by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf<ExportDialogInfo?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate50)
            .testTag("more_root")
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = s.settingsTitle,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Slate900
        )

        // Privacy App Lock Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionIconBadge(
                    icon = Icons.Rounded.Lock,
                    tint = PrimaryTeal,
                    bg = Teal50
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.appLockTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = s.appLockSubtitle,
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
                Switch(
                    checked = appLockEnabled,
                    modifier = Modifier.testTag("more_switch_applock"),
                    onCheckedChange = { enable ->
                        if (enable) {
                            if (!PlatformBiometricAuth.isAvailable()) {
                                showPlatformToast(s.biometricUnavailable)
                                return@Switch
                            }
                            PlatformBiometricAuth.authenticate(
                                title = s.enableAppLockTitle,
                                subtitle = s.enableAppLockSubtitle,
                                cancelLabel = s.cancel
                            ) { success, errMsg ->
                                if (success) {
                                    securityPrefs.putBoolean(KEY_APP_LOCK_ENABLED, true)
                                    appLockEnabled = true
                                    showPlatformToast(s.appLockEnabledToast)
                                } else if (!errMsg.isNullOrBlank()) {
                                    showPlatformToast(errMsg)
                                }
                            }
                        } else {
                            securityPrefs.putBoolean(KEY_APP_LOCK_ENABLED, false)
                            appLockEnabled = false
                            showPlatformToast(s.appLockDisabledToast)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryTeal,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Slate200,
                        uncheckedBorderColor = Slate200
                    )
                )
            }
        }

        // Language Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionIconBadge(
                        icon = Icons.Rounded.Language,
                        tint = PrimaryTeal,
                        bg = Teal50
                    )
                    Column {
                        Text(
                            text = s.languageTitle,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = s.languageSubtitle,
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }
                }

                SharedSegmentedTabs(
                    options = LanguageLabels,
                    selectedIndex = selectedLangIndex,
                    onSelect = { idx ->
                        val code = LanguageCodes[idx]
                        LocaleStateHolder.setLanguage(code)
                        val updatedStrings = SharedStrings.forLanguage(code)
                        showPlatformToast(SharedStrings.format1(updatedStrings.languageSwitchedFmt, LanguageLabels[idx]))
                    },
                    itemTestTagPrefix = "more_lang"
                )
            }
        }

        // Offline Backup & CSV Export Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            border = BorderStroke(1.dp, Slate200),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionIconBadge(
                        icon = Icons.Rounded.Download,
                        tint = IncomeGreen,
                        bg = Emerald50
                    )
                    Column {
                        Text(
                            text = s.backupExportTitle,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                        Text(
                            text = s.backupExportSubtitle,
                            fontSize = 12.sp,
                            color = Slate500
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            if (isExporting) return@Button
                            isExporting = true
                            scope.launch {
                                try {
                                    val result = LedgerBackupCore.exportFromDatabase()
                                    val summaryMsg = SharedStrings.format2(
                                        s.backupSummaryFmt,
                                        result.accountCount,
                                        result.transactionCount
                                    )
                                    val shared = sharePlatformTextFile(
                                        fileName = "ledger-backup.json",
                                        mimeType = "application/json",
                                        content = result.jsonContent
                                    )
                                    showPlatformToast(summaryMsg)
                                    if (!shared) {
                                        exportDialog = ExportDialogInfo(
                                            title = s.backupDialogTitle,
                                            summary = summaryMsg,
                                            detail = SharedStrings.format3(
                                                s.backupDialogDetailFmt,
                                                result.accountCount,
                                                result.categoryCount,
                                                result.transactionCount
                                            )
                                        )
                                    }
                                    onDataChanged()
                                } catch (e: Exception) {
                                    println("Backup export error: ${e.message}")
                                    showPlatformToast(s.backupFailed)
                                } finally {
                                    isExporting = false
                                }
                            }
                        },
                        enabled = !isExporting,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryTeal,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("more_btn_backup")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = s.backupButton,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (isExporting) return@OutlinedButton
                            isExporting = true
                            scope.launch {
                                try {
                                    val result = LedgerBackupCore.exportCsvFromDatabase()
                                    val summaryMsg = SharedStrings.format1(s.csvSummaryFmt, result.transactionCount)
                                    val shared = sharePlatformTextFile(
                                        fileName = "ledger-transactions.csv",
                                        mimeType = "text/csv",
                                        content = result.csvContent
                                    )
                                    showPlatformToast(summaryMsg)
                                    if (!shared) {
                                        exportDialog = ExportDialogInfo(
                                            title = s.csvDialogTitle,
                                            summary = summaryMsg,
                                            detail = s.csvDialogDetail
                                        )
                                    }
                                } catch (e: Exception) {
                                    println("CSV export error: ${e.message}")
                                    showPlatformToast(s.csvFailed)
                                } finally {
                                    isExporting = false
                                }
                            }
                        },
                        enabled = !isExporting,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Slate200),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Slate700
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("more_btn_csv")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TableChart,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = s.exportCsvButton,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // iOS-only Auto-Tracking via Shortcuts Guide Card
        if (PlatformCapabilities.isIos) {
            IosShortcutsGuideCard(s = s)
        }

        Spacer(Modifier.height(12.dp))
    }

    val info = exportDialog
    if (info != null) {
        AlertDialog(
            onDismissRequest = { exportDialog = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = info.title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = info.summary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate700
                    )
                    Text(
                        text = info.detail,
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { exportDialog = null },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryTeal,
                        contentColor = Color.White
                    )
                ) {
                    Text(s.done, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun IosShortcutsGuideCard(s: LedgerUiStrings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Slate200),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionIconBadge(
                    icon = Icons.Rounded.Bolt,
                    tint = PrimaryTeal,
                    bg = Teal50
                )
                Column {
                    Text(
                        text = s.iosShortcutsTitle,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = s.iosShortcutsSubtitle,
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
            }

            HorizontalDivider(color = Slate100)

            ShortcutStepItem(
                icon = Icons.Rounded.CreditCard,
                title = s.iosShortcutApplePayTitle,
                description = s.iosShortcutApplePayDesc
            )

            ShortcutStepItem(
                icon = Icons.Rounded.Sms,
                title = s.iosShortcutBankSmsTitle,
                description = s.iosShortcutBankSmsDesc
            )

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Slate50,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = s.iosShortcutsPrivacyNote,
                        fontSize = 12.sp,
                        color = Slate600
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutStepItem(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Slate100),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Slate700,
                modifier = Modifier.size(16.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = Slate600,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun SectionIconBadge(
    icon: ImageVector,
    tint: Color,
    bg: Color
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}
