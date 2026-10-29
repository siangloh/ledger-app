package com.siangloh.ledger.data

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.R
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
    private const val TAG = "AuthManager"
    const val PREFS_NAME = "ledger_auth_prefs"
    const val KEY_IS_LOGGED_IN = "is_logged_in"
    const val KEY_CURRENT_USERNAME = "current_username"
    const val KEY_CURRENT_USER_ID = "current_user_id"
    const val KEY_USERS_CACHE = "cached_users_json"
    const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"

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
            return Result.failure(Exception(context.getString(R.string.login_empty_username)))
        }
        val cleanUser = usernameToUse.trim()
        val userKey = cleanUser.lowercase(Locale.ROOT)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheStr = sp.getString(KEY_USERS_CACHE, "{}") ?: "{}"
        val cacheObj = try { JSONObject(cacheStr) } catch (_: Exception) { JSONObject() }

        val userRecord = cacheObj.optJSONObject(userKey)
            ?: return Result.failure(Exception(context.getString(R.string.login_biometric_failed)))

        val userId = if (userKey == "admin") TursoCloudClient.ADMIN_TURSO_UUID else userRecord.optString("user_id", TursoCloudClient.ADMIN_TURSO_UUID)
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
        val uid = sp.getString(KEY_CURRENT_USER_ID, "") ?: ""
        if (uid.isBlank() || uid == "1" || getCurrentUsername(context).equals("admin", ignoreCase = true)) {
            return TursoCloudClient.ADMIN_TURSO_UUID
        }
        return uid
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
     * 2. 若本地无匹配，直连 Turso 云端数据库进行在线凭据校验与拉取。
     * 3. 校验成功后自动全量预载/同步 Turso 云端流水与分类到本地 Room 数据库。
     */
    suspend fun login(
        context: Context,
        username: String,
        password: String,
        serverUrl: String? = null
    ): Result<AuthResult> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isEmpty()) {
            return@withContext Result.failure(Exception(context.getString(R.string.login_empty_username)))
        }
        if (cleanPass.isEmpty()) {
            return@withContext Result.failure(Exception(context.getString(R.string.login_empty_password)))
        }

        val passHash = hashPassword(cleanPass)
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cacheStr = sp.getString(KEY_USERS_CACHE, "{}") ?: "{}"
        val cacheObj = try { JSONObject(cacheStr) } catch (_: Exception) { JSONObject() }
        val userKey = cleanUser.lowercase(Locale.ROOT)

        // 1. 检查本地缓存中是否存在匹配的凭据 (极速秒进)
        if (cacheObj.has(userKey)) {
            val userRecord = cacheObj.optJSONObject(userKey)
            if (userRecord != null) {
                val cachedHash = userRecord.optString("password_hash")
                if (cachedHash == passHash) {
                    val userId = if (userKey == "admin") TursoCloudClient.ADMIN_TURSO_UUID else userRecord.optString("user_id", TursoCloudClient.ADMIN_TURSO_UUID)
                    val origName = userRecord.optString("username", cleanUser)
                    val apiKey = userRecord.optString("api_key")

                    if (userKey == "admin") {
                        userRecord.put("user_id", userId)
                        cacheObj.put(userKey, userRecord)
                    }

                    sp.edit()
                        .putBoolean(KEY_IS_LOGGED_IN, true)
                        .putString(KEY_CURRENT_USERNAME, origName)
                        .putString(KEY_CURRENT_USER_ID, userId)
                        .putString(KEY_USERS_CACHE, cacheObj.toString())
                        .putBoolean(KEY_BIOMETRIC_ENABLED, true)
                        .apply()

                    NetworkHelper.setUsername(context, origName)
                    if (apiKey.isNotEmpty()) {
                        NetworkHelper.setApiKey(context, apiKey)
                    }
                    if (!serverUrl.isNullOrBlank()) {
                        NetworkHelper.setServerUrl(context, serverUrl)
                    }

                    // 登录成功后，立即拉取 Turso 云端流水并刷新 UI
                    try {
                        TursoCloudClient.syncAllFromTurso(context, userId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cached login Turso sync warning: ${e.message}")
                    }

                    return@withContext Result.success(AuthResult(origName, userId, isLocalCache = true))
                }
            }
        }

        // 2. 优先通过 Turso Cloud 直接验证与同步 (无需 PC / 本地后端)
        try {
            val tursoRes = TursoCloudClient.verifyLogin(context, cleanUser, cleanPass)
            if (tursoRes.isSuccess) {
                val authRes = tursoRes.getOrThrow()
                val retUsername = authRes.username
                val userId = if (userKey == "admin") TursoCloudClient.ADMIN_TURSO_UUID else authRes.userId

                val newRecord = JSONObject().apply {
                    put("username", retUsername)
                    put("password_hash", passHash)
                    put("user_id", userId)
                    put("api_key", "")
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
                if (!serverUrl.isNullOrBlank()) {
                    NetworkHelper.setServerUrl(context, serverUrl)
                }

                // 首次拉取成功后，全量同步 Turso 云端流水与分类数据
                try {
                    TursoCloudClient.syncAllFromTurso(context, userId)
                } catch (e: Exception) {
                    Log.w(TAG, "Post-login Turso sync warning: ${e.message}")
                }

                return@withContext Result.success(authRes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Turso cloud login failed: ${e.message}")
        }

        // 3. 默认管理员离线登录兜底 (保证首次安装/完全无网络下直接进入)
        if (userKey == "admin" && cleanPass == "admin123") {
            val defaultAdminId = TursoCloudClient.ADMIN_TURSO_UUID
            val defaultRecord = JSONObject().apply {
                put("username", "admin")
                put("password_hash", passHash)
                put("user_id", defaultAdminId)
                put("api_key", "")
                put("saved_at", System.currentTimeMillis())
            }
            cacheObj.put(userKey, defaultRecord)

            sp.edit()
                .putBoolean(KEY_IS_LOGGED_IN, true)
                .putString(KEY_CURRENT_USERNAME, "admin")
                .putString(KEY_CURRENT_USER_ID, defaultAdminId)
                .putString(KEY_USERS_CACHE, cacheObj.toString())
                .putBoolean(KEY_BIOMETRIC_ENABLED, true)
                .apply()

            NetworkHelper.setUsername(context, "admin")
            if (!serverUrl.isNullOrBlank()) {
                NetworkHelper.setServerUrl(context, serverUrl)
            }

            // 离线兜底登入后也尝试异步同步 Turso
            try {
                TursoCloudClient.syncAllFromTurso(context, defaultAdminId)
            } catch (_: Exception) {}

            return@withContext Result.success(AuthResult("admin", defaultAdminId, isLocalCache = true))
        }

        // 4. 本地与 Turso 直连均未命中时，尝试向局域网/自定义服务器接口验证兜底
        val onlineRes = NativeLedgerApi.login(context, cleanUser, cleanPass, serverUrl)
        onlineRes.fold(
            onSuccess = { json ->
                val userObj = json.optJSONObject("user")
                val userId = if (userKey == "admin") TursoCloudClient.ADMIN_TURSO_UUID else (userObj?.optString("id") ?: TursoCloudClient.ADMIN_TURSO_UUID)
                val retUsername = userObj?.optString("username") ?: cleanUser
                val apiKey = userObj?.optString("api_key") ?: ""

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

                try {
                    LocalDataStore.syncAllUserData(context, force = true)
                } catch (_: Exception) {}

                Result.success(AuthResult(retUsername, userId, isLocalCache = false))
            },
            onFailure = { _ ->
                Result.failure(Exception(context.getString(R.string.login_invalid_credentials)))
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
