package com.siangloh.ledger.util

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.biometric.BiometricManager

/**
 * 隐私应用锁状态与宽限期管理：
 * - 支持指纹 / 面容 / 设备锁屏密码；
 * - 切到后台超过 30 秒后再返回会自动重新上锁，避免切去系统文件选择器或相机时频繁打扰。
 */
object AppLockManager {
    private const val TAG = "AppLockManager"
    private const val PREFS_NAME = "ledger_security_prefs"
    private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
    private const val BACKGROUND_GRACE_MS = 30_000L

    @Volatile
    private var lastBackgroundElapsedMs: Long = 0L

    @Volatile
    private var hasUnlockedCurrentSession: Boolean = false

    fun isAppLockEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_APP_LOCK_ENABLED, false)
    }

    fun setAppLockEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APP_LOCK_ENABLED, enabled)
            .apply()
        if (enabled) {
            hasUnlockedCurrentSession = true
            lastBackgroundElapsedMs = 0L
        }
    }

    fun canEnableAppLock(context: Context): Boolean {
        return try {
            val bm = BiometricManager.from(context)
            val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            } else {
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            }
            bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check biometric capability", e)
            false
        }
    }

    fun markUnlocked() {
        hasUnlockedCurrentSession = true
        lastBackgroundElapsedMs = 0L
    }

    fun recordBackgroundTimestamp() {
        lastBackgroundElapsedMs = SystemClock.elapsedRealtime()
    }

    fun shouldRequireUnlock(context: Context): Boolean {
        if (!isAppLockEnabled(context)) return false
        if (!hasUnlockedCurrentSession) return true
        val bgAt = lastBackgroundElapsedMs
        if (bgAt <= 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - bgAt
        return elapsed >= BACKGROUND_GRACE_MS
    }

    fun resetSessionLock() {
        hasUnlockedCurrentSession = false
        lastBackgroundElapsedMs = 0L
    }
}
