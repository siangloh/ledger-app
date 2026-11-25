package com.siangloh.ledger.ui.compose.components

import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.siangloh.ledger.R
import com.siangloh.ledger.data.BackupPayload
import com.siangloh.ledger.data.BiometricHelper
import com.siangloh.ledger.data.LedgerBackupManager
import com.siangloh.ledger.ui.compose.theme.*
import com.siangloh.ledger.util.AppLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SECURITY_TAG = "SecurityAndBackupUI"

/**
 * 全屏隐私保护锁屏遮罩：打开 App 或切回前台超时后隐藏资产余额，支持指纹/面容/设备锁屏密码一键解锁。
 */
@Composable
fun AppLockOverlay(
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val promptTitle = stringResource(R.string.security_app_lock_prompt_title)
    val promptSubtitle = stringResource(R.string.security_app_lock_prompt_subtitle)
    val cancelLabel = stringResource(R.string.common_cancel)

    fun triggerAuth() {
        val activity = BiometricHelper.findFragmentActivity(context)
        if (activity == null) {
            AppLockManager.markUnlocked()
            onUnlocked()
            return
        }
        BiometricHelper.authenticateForAppLock(
            activity = activity,
            title = promptTitle,
            subtitle = promptSubtitle,
            negativeButtonText = cancelLabel,
            onSuccess = {
                AppLockManager.markUnlocked()
                onUnlocked()
            },
            onError = { code, msg ->
                Log.d(SECURITY_TAG, "App lock auth dismissed/error: code=$code, msg=$msg")
            }
        )
    }

    LaunchedEffect(Unit) {
        triggerAuth()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Slate50
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = Teal50,
                border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.25f)),
                modifier = Modifier.size(84.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = stringResource(R.string.security_app_lock_overlay_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Slate900
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.security_app_lock_overlay_subtitle),
                fontSize = 13.5.sp,
                color = Slate500,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = { triggerAuth() },
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryTeal,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.security_app_lock_unlock_btn),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 更多页「隐私与数据安全」卡片：
 * 1. 指纹 / 面容应用锁开关（开启前先完成一次身份验证）；
 * 2. 一键导出完整账本备份（.json）；
 * 3. 从备份文件恢复（带记录数预览确认弹窗）；
 * 4. 导出 Excel 兼容账单表格（.csv）。
 */
@Composable
fun SecurityAndBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var appLockEnabled by remember { mutableStateOf(AppLockManager.isAppLockEnabled(context)) }
    var isBusy by remember { mutableStateOf(false) }
    var pendingRestorePayload by remember { mutableStateOf<BackupPayload?>(null) }

    val promptTitle = stringResource(R.string.security_app_lock_prompt_title)
    val promptSubtitle = stringResource(R.string.security_app_lock_prompt_subtitle)
    val cancelLabel = stringResource(R.string.common_cancel)

    // 导出完整备份 (.json)
    val createBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        scope.launch {
            try {
                val summary = LedgerBackupManager.createBackup(context)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(summary.jsonContent.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Unable to open output stream")
                }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.security_backup_export_success_fmt,
                        summary.accountCount,
                        summary.transactionCount
                    ),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.w(SECURITY_TAG, "Backup export failed", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.security_backup_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isBusy = false
            }
        }
    }

    // 选择备份文件恢复 (.json)
    val openBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        scope.launch {
            try {
                val rawText = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("Unable to read backup file")
                }
                val parsed = withContext(Dispatchers.Default) {
                    LedgerBackupManager.parseBackupJson(rawText)
                }
                pendingRestorePayload = parsed
            } catch (e: Exception) {
                Log.w(SECURITY_TAG, "Backup parse failed", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.security_restore_invalid_file),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                isBusy = false
            }
        }
    }

    // 导出 Excel 兼容账单 (.csv)
    val createCsvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        isBusy = true
        scope.launch {
            try {
                val summary = LedgerBackupManager.createTransactionsCsv(context)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(summary.csvContent.toByteArray(Charsets.UTF_8))
                    } ?: throw IllegalStateException("Unable to open CSV output stream")
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.security_csv_export_success_fmt, summary.transactionCount),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.w(SECURITY_TAG, "CSV export failed", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.security_csv_export_failed),
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                isBusy = false
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Teal50),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = PrimaryTeal,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.security_section_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900,
                    modifier = Modifier.weight(1f)
                )
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = PrimaryTeal
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 1. 隐私应用锁开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.security_app_lock_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.security_app_lock_subtitle),
                        fontSize = 11.5.sp,
                        color = Slate500,
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Switch(
                    checked = appLockEnabled,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable) {
                            if (!AppLockManager.canEnableAppLock(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.security_app_lock_unsupported),
                                    Toast.LENGTH_LONG
                                ).show()
                                return@Switch
                            }
                            val activity = BiometricHelper.findFragmentActivity(context)
                            if (activity != null) {
                                BiometricHelper.authenticateForAppLock(
                                    activity = activity,
                                    title = promptTitle,
                                    subtitle = promptSubtitle,
                                    negativeButtonText = cancelLabel,
                                    onSuccess = {
                                        AppLockManager.setAppLockEnabled(context, true)
                                        appLockEnabled = true
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.security_app_lock_enabled_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                )
                            } else {
                                AppLockManager.setAppLockEnabled(context, true)
                                appLockEnabled = true
                            }
                        } else {
                            AppLockManager.setAppLockEnabled(context, false)
                            appLockEnabled = false
                            Toast.makeText(
                                context,
                                context.getString(R.string.security_app_lock_disabled_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = PrimaryTeal
                    )
                )
            }

            HorizontalDivider(
                color = Slate100,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            // 2. 导出完整备份
            val dateStamp = remember { SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) }
            SecurityActionRow(
                icon = Icons.Rounded.CloudDownload,
                iconTint = PrimaryTeal,
                iconBg = Teal50,
                title = stringResource(R.string.security_backup_export_title),
                subtitle = stringResource(R.string.security_backup_export_desc),
                enabled = !isBusy,
                onClick = { createBackupLauncher.launch("ledger_backup_$dateStamp.json") }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. 从备份恢复
            SecurityActionRow(
                icon = Icons.Rounded.Restore,
                iconTint = Color(0xFFD97706),
                iconBg = Color(0xFFFEF3C7),
                title = stringResource(R.string.security_backup_restore_title),
                subtitle = stringResource(R.string.security_backup_restore_desc),
                enabled = !isBusy,
                onClick = { openBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 4. 导出 Excel 账单表格 (CSV)
            SecurityActionRow(
                icon = Icons.Rounded.TableChart,
                iconTint = IncomeGreen,
                iconBg = Emerald50,
                title = stringResource(R.string.security_csv_export_title),
                subtitle = stringResource(R.string.security_csv_export_desc),
                enabled = !isBusy,
                onClick = { createCsvLauncher.launch("ledger_records_$dateStamp.csv") }
            )
        }
    }

    // 恢复前预览确认弹窗
    pendingRestorePayload?.let { payload ->
        AlertDialog(
            onDismissRequest = { if (!isBusy) pendingRestorePayload = null },
            containerColor = Color.White,
            title = {
                Text(
                    text = stringResource(R.string.security_restore_confirm_title),
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.security_restore_confirm_msg_fmt,
                        payload.accounts.size,
                        payload.categories.size,
                        payload.transactions.size
                    ),
                    color = Slate600,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isBusy = true
                        scope.launch {
                            try {
                                LedgerBackupManager.restoreBackup(context, payload)
                                pendingRestorePayload = null
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.security_restore_success_fmt,
                                        payload.transactions.size
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Log.w(SECURITY_TAG, "Backup restore failed", e)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.security_restore_invalid_file),
                                    Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal)
                ) {
                    Text(stringResource(R.string.security_restore_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingRestorePayload = null },
                    enabled = !isBusy
                ) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}

@Composable
private fun SecurityActionRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Slate50,
        border = BorderStroke(1.dp, Slate100),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Slate500,
                    lineHeight = 15.sp
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
