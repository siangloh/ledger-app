package com.siangloh.ledger.data

import android.content.Context
import androidx.biometric.BiometricManager
import com.siangloh.ledger.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

data class AuthResult(
    val username: String,
    val userId: String,
    val isLocalCache: Boolean
)

object AuthManager {
    private const val PREFS_NAME = "ledger_auth_prefs"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_CURRENT_USERNAME = "current_username"
    private const val KEY_CURRENT_USER_ID = "current_user_id"
    private const val KEY_USERS_CACHE = "cached_users_json"
    private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"

    fun isBiometricSupported(context: Context): Boolean {
        return try {
            val bm = BiometricManager.from(context)
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    fun isBiometricEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_BIOMETRIC_ENABLED, true)
    }

    fun setBiometricEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_BIOMETRIC_ENABLED, enabled)
            .apply()
    }

    fun getLastUsername(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cur = sp.getString(KEY_CURRENT_USERNAME, "") ?: ""
        if (cur.isNotBlank()) return cur
        val cacheStr = sp.getString(KEY_USERS_CACHE, "{}") ?: "{}"
        return try {
            val obj = JSONObject(cacheStr)
            val keys = obj.keys()
            if (keys.hasNext()) {
                obj.optJSONObject(keys.next())?.optString("username", "") ?: ""
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun canUseBiometricLogin(context: Context): Boolean {
        if (!isBiometricSupported(context)) return false
        if (!isBiometricEnabled(context)) return false
        val user = getLastUsername(context)
        return user.isNotBlank() && hasCachedUser(context, user)
    }

    fun loginWithBiometric(context: Context, targetUsername: String? = null): Result<AuthResult> {
        val usernameToUse = targetUsername?.takeIf { it.isNotBlank() } ?: getLastUsername(context)
        if (usernameToUse.isBlank()) {
            return Result.failure(Exception("未找到可快速登录的账号，请先使用密码登录"))
        }
        val cleanUser = usernameToUse.trim()
        val userKey = cleanUser.lowercase(Locale.ROOT)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheStr = sp.getString(KEY_USERS_CACHE, "{}") ?: "{}"
        val cacheObj = try { JSONObject(cacheStr) } catch (_: Exception) { JSONObject() }

        val userRecord = cacheObj.optJSONObject(userKey)
            ?: return Result.failure(Exception("该账号尚未缓存离线凭据，请先使用密码登录一次"))

        val userId = userRecord.optString("user_id")
        val origName = userRecord.optString("username", cleanUser)
        val apiKey = userRecord.optString("api_key")

        sp.edit()
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .putString(KEY_CURRENT_USERNAME, origName)
            .putString(KEY_CURRENT_USER_ID, userId)
            .apply()

        NetworkHelper.setUsername(context, origName)
        if (apiKey.isNotEmpty()) {
            NetworkHelper.setApiKey(context, apiKey)
        }

        return Result.success(AuthResult(origName, userId, isLocalCache = true))
    }

    fun isLoggedIn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_LOGGED_IN, false)
    }

    fun getCurrentUsername(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_CURRENT_USERNAME, "") ?: ""
    }

    fun getCurrentUserId(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_CURRENT_USER_ID, "") ?: ""
    }

    fun hasCachedUser(context: Context, username: String): Boolean {
        val clean = username.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return false
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheStr = sp.getString(KEY_USERS_CACHE, "{}") ?: "{}"
        return try {
            JSONObject(cacheStr).has(clean)
        } catch (_: Exception) {
            false
        }
    }

    fun hashPassword(password: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 执行用户登录：
     * 1. 优先比对本地已缓存的用户名与密码哈希；若本地匹配成功，直接离线极速登入。
     * 2. 若本地无该用户记录或比对未命中，则发起网络请求向在线后端 (Online DB) 验证。
     * 3. 在线验证成功后，将用户名和密码哈希缓存至本地，下次登入即可免联网离线进入。
     */
    suspend fun login(
        context: Context,
        username: String,
        password: String,
        serverUrl: String? = null
    ): Result<AuthResult> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isEmpty() || cleanPass.isEmpty()) {
            return@withContext Result.failure(Exception("请输入用户名和密码"))
        }

        val passHash = hashPassword(cleanPass)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheStr = sp.getString(KEY_USERS_CACHE, "{}") ?: "{}"
        val cacheObj = try { JSONObject(cacheStr) } catch (_: Exception) { JSONObject() }
        val userKey = cleanUser.lowercase(Locale.ROOT)

        // 1. 检查本地缓存中是否存在匹配的凭据
        if (cacheObj.has(userKey)) {
            val userRecord = cacheObj.optJSONObject(userKey)
            if (userRecord != null) {
                val cachedHash = userRecord.optString("password_hash")
                if (cachedHash == passHash) {
                    val userId = userRecord.optString("user_id")
                    val origName = userRecord.optString("username", cleanUser)
                    val apiKey = userRecord.optString("api_key")

                    sp.edit()
                        .putBoolean(KEY_IS_LOGGED_IN, true)
                        .putString(KEY_CURRENT_USERNAME, origName)
                        .putString(KEY_CURRENT_USER_ID, userId)
                        .putBoolean(KEY_BIOMETRIC_ENABLED, true)
                        .apply()

                    NetworkHelper.setUsername(context, origName)
                    if (apiKey.isNotEmpty()) {
                        NetworkHelper.setApiKey(context, apiKey)
                    }
                    if (!serverUrl.isNullOrBlank()) {
                        NetworkHelper.setServerUrl(context, serverUrl)
                    }

                    return@withContext Result.success(AuthResult(origName, userId, isLocalCache = true))
                }
            }
        }

        // 2. 本地不存在或本地验证未过，联网请求在线后端验证 (Online DB)
        val onlineRes = NativeLedgerApi.login(context, cleanUser, cleanPass, serverUrl)
        onlineRes.fold(
            onSuccess = { json ->
                val userObj = json.optJSONObject("user")
                val userId = userObj?.optString("id") ?: ""
                val retUsername = userObj?.optString("username") ?: cleanUser
                val apiKey = userObj?.optString("api_key") ?: ""

                // 记录凭证到本地缓存
                val newRecord = JSONObject().apply {
                    put("username", retUsername)
                    put("password_hash", passHash)
                    put("user_id", userId)
                    put("api_key", apiKey)
                    put("saved_at", System.currentTimeMillis())
                }
                cacheObj.put(userKey, newRecord)

                sp.edit()
                    .putBoolean(KEY_IS_LOGGED_IN, true)
                    .putString(KEY_CURRENT_USERNAME, retUsername)
                    .putString(KEY_CURRENT_USER_ID, userId)
                    .putString(KEY_USERS_CACHE, cacheObj.toString())
                    .putBoolean(KEY_BIOMETRIC_ENABLED, true)
                    .apply()

                NetworkHelper.setUsername(context, retUsername)
                if (apiKey.isNotEmpty()) {
                    NetworkHelper.setApiKey(context, apiKey)
                }
                if (!serverUrl.isNullOrBlank()) {
                    NetworkHelper.setServerUrl(context, serverUrl)
                }

                // 首次拉取成功后，后台全量同步账户与分类数据
                try {
                    LocalDataStore.syncAllUserData(context)
                } catch (_: Exception) {}

                Result.success(AuthResult(retUsername, userId, isLocalCache = false))
            },
            onFailure = { err ->
                Result.failure(err)
            }
        )
    }

    fun logout(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
    }
}
