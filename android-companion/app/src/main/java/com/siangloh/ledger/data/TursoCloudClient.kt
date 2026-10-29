package com.siangloh.ledger.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.siangloh.ledger.data.entities.CachedCategory
import com.siangloh.ledger.data.entities.LocalAccount
import com.siangloh.ledger.data.entities.LocalCategory
import com.siangloh.ledger.data.entities.LocalTransaction
import com.siangloh.ledger.ui.compose.CategoryUiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Turso Cloud 全量双向直连客户端 (Turso Cloud Database Direct Client)
 * 允许 Android 移动端在无需本地电脑/Flask 服务器运行的情况下，
 * 直接通过 Turso HTTP Pipeline API (/v2/pipeline) 与云端 LibSQL 数据库交互。
 *
 * 全量覆盖同步范围（不仅仅是交易流水）：
 * 1. 用户凭证校验与设置 (users, system_settings, user_settings)
 * 2. 账务流水与回收站双向同步 (transactions active & deleted_at <-> local_transactions & local_recycle_bin_prefs)
 * 3. 收支与储蓄分类双向同步 (categories <-> local_categories & cached_categories)
 * 4. 资产账户与余额双向同步 (accounts <-> local_accounts)
 * 5. 月度总预算与分类预算双向同步 (category_budgets, user_settings.budget_start_day <-> local_budget_prefs)
 * 6. 商户自动归类规则双向同步 (merchant_category_overrides <-> local_category_rules_prefs)
 * 7. 固定收支、周期订阅、固定储蓄与分期计划双向同步 (recurring_rules, subscriptions, installments <-> local_tools_prefs)
 */
object TursoCloudClient {
    private const val TAG = "TursoCloudClient"

    const val DEFAULT_TURSO_PIPELINE_URL =
        "https://ledger-app-siangloh.aws-ap-northeast-1.turso.io/v2/pipeline"
    const val DEFAULT_TURSO_TOKEN =
        "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTI5OTYwMjksImlkIjoiMDFhMDc5NDctZDAwMS03MjZkLWE0NzQtNmM1MmYzODM5ZDVhIiwia2lkIjoiUFpvelZYb3JTZkVlSWFmNS1ZdGQyNEFGMG5xbTJ4UkxHZ1MyM3cxZTVsbyIsInJpZCI6IjQxN2YwNTVkLWZmMWQtNDhlZC04YmFjLTVhZDExNGY3MGUxNCJ9.ktieEm8f_mp6yh7CnwMA6Py6G8CrkCB9JYLCIj3eLcvc3VJZrmzqVAonJAi3-cb0YEusfZEaNksg7Jhd18bKDg"

    const val ADMIN_TURSO_UUID = "f84e359d-b99e-4ac2-b098-298e9d2661ea"

    private const val PREFS_TURSO = "ledger_turso_prefs"
    private const val KEY_CUSTOM_URL = "custom_pipeline_url"
    private const val KEY_CUSTOM_TOKEN = "custom_token"

    fun getPipelineUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_TURSO, Context.MODE_PRIVATE)
        return sp.getString(KEY_CUSTOM_URL, DEFAULT_TURSO_PIPELINE_URL) ?: DEFAULT_TURSO_PIPELINE_URL
    }

    fun getAuthToken(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_TURSO, Context.MODE_PRIVATE)
        return sp.getString(KEY_CUSTOM_TOKEN, DEFAULT_TURSO_TOKEN) ?: DEFAULT_TURSO_TOKEN
    }

    fun resolveTursoUserId(context: Context, explicitUserId: String? = null): String {
        val currentUsername = AuthManager.getCurrentUsername(context).ifBlank { "admin" }
        val isUserAdmin = currentUsername.equals("admin", ignoreCase = true)
        return if (isUserAdmin || explicitUserId == "1" || AuthManager.getCurrentUserId(context) == "1") {
            ADMIN_TURSO_UUID
        } else {
            explicitUserId?.takeIf { it.isNotBlank() && it != "1" }
                ?: AuthManager.getCurrentUserId(context).takeIf { it.isNotBlank() && it != "1" }
                ?: ADMIN_TURSO_UUID
        }
    }

    private fun buildBumpVersionStatements(userId: String, nowIso: String): List<Pair<String, List<Any?>>> {
        val vToken = UUID.randomUUID().toString().replace("-", "").take(16)
        val sql = """
            INSERT INTO system_metadata (key, val, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET val = excluded.val, updated_at = excluded.updated_at
        """.trimIndent()
        return listOf(
            Pair(sql, listOf("global_data_version", vToken, nowIso)),
            Pair(sql, listOf("user_data_version:$userId", vToken, nowIso))
        )
    }

    private fun asDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Float -> v.toDouble()
        is Long -> v.toDouble()
        is Int -> v.toDouble()
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    private fun asLong(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else -> null
    }

    /**
     * 执行底层 Turso Pipeline HTTP 请求
     */
    suspend fun executeBatch(
        context: Context,
        statements: List<Pair<String, List<Any?>>>
    ): Result<List<List<Map<String, Any?>>>> = withContext(Dispatchers.IO) {
        if (statements.isEmpty()) return@withContext Result.success(emptyList())
        try {
            val endpoint = getPipelineUrl(context)
            val token = getAuthToken(context)

            val requestsArray = JSONArray()
            for ((sql, args) in statements) {
                val stmtObj = JSONObject().apply {
                    put("sql", sql)
                    if (args.isNotEmpty()) {
                        val argsArray = JSONArray()
                        for (arg in args) {
                            val argObj = JSONObject()
                            when (arg) {
                                null -> {
                                    argObj.put("type", "null")
                                }
                                is Boolean -> {
                                    argObj.put("type", "integer")
                                    argObj.put("value", if (arg) "1" else "0")
                                }
                                is Number -> {
                                    if (arg is Double || arg is Float) {
                                        argObj.put("type", "float")
                                        argObj.put("value", arg.toDouble())
                                    } else {
                                        argObj.put("type", "integer")
                                        argObj.put("value", arg.toLong().toString())
                                    }
                                }
                                else -> {
                                    argObj.put("type", "text")
                                    argObj.put("value", arg.toString())
                                }
                            }
                            argsArray.put(argObj)
                        }
                        put("args", argsArray)
                    }
                }
                requestsArray.put(JSONObject().apply {
                    put("type", "execute")
                    put("stmt", stmtObj)
                })
            }
            requestsArray.put(JSONObject().apply {
                put("type", "close")
            })

            val payload = JSONObject().apply {
                put("requests", requestsArray)
            }.toString()

            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("User-Agent", "LedgerTursoNative/2.0 (Android; Dalvik)")
                doOutput = true
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload)
                it.flush()
            }

            val respCode = conn.responseCode
            val respBody = if (respCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val errText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                return@withContext Result.failure(Exception("Turso HTTP $respCode: $errText"))
            }

            val rootJson = JSONObject(respBody)
            val resultsArr = rootJson.optJSONArray("results") ?: JSONArray()
            val allStatementResults = mutableListOf<List<Map<String, Any?>>>()

            for (i in 0 until resultsArr.length()) {
                val resObj = resultsArr.optJSONObject(i) ?: continue
                val type = resObj.optString("type")
                if (type == "error") {
                    val errMsg = resObj.optJSONObject("error")?.optString("message") ?: "Turso query error"
                    return@withContext Result.failure(Exception(errMsg))
                }
                if (type != "ok") continue

                val respObj = resObj.optJSONObject("response") ?: continue
                if (respObj.optString("type") == "close") continue

                val resultObj = respObj.optJSONObject("result") ?: continue
                val colsArr = resultObj.optJSONArray("cols") ?: JSONArray()
                val colNames = mutableListOf<String>()
                for (c in 0 until colsArr.length()) {
                    colNames.add(colsArr.optJSONObject(c)?.optString("name") ?: "col_$c")
                }

                val rowsArr = resultObj.optJSONArray("rows") ?: JSONArray()
                val statementRows = mutableListOf<Map<String, Any?>>()
                for (r in 0 until rowsArr.length()) {
                    val rowArr = rowsArr.optJSONArray(r) ?: continue
                    val rowMap = mutableMapOf<String, Any?>()
                    for (c in 0 until rowArr.length()) {
                        val colName = colNames.getOrElse(c) { "col_$c" }
                        val cellObj = rowArr.optJSONObject(c)
                        val cellValue = if (cellObj != null) {
                            when (cellObj.optString("type")) {
                                "null" -> null
                                "integer" -> cellObj.optString("value").toLongOrNull()
                                "float" -> cellObj.optDouble("value")
                                else -> cellObj.opt("value")
                            }
                        } else null
                        rowMap[colName] = cellValue
                    }
                    statementRows.add(rowMap)
                }
                allStatementResults.add(statementRows)
            }

            Result.success(allStatementResults)
        } catch (e: Exception) {
            Log.e(TAG, "executeBatch error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 在线验证用户登录凭证 (直接连接 Turso 云端)
     */
    suspend fun verifyLogin(
        context: Context,
        username: String,
        password: String
    ): Result<AuthResult> = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isEmpty() || cleanPass.isEmpty()) {
            return@withContext Result.failure(Exception("Username and password cannot be empty"))
        }

        val statements = listOf(
            Pair("SELECT id, username, password_hash, api_key, email FROM users WHERE LOWER(username) = LOWER(?)", listOf(cleanUser)),
            Pair("SELECT value FROM system_settings WHERE key = 'app_password'", emptyList<Any?>())
        )

        val batchResult = executeBatch(context, statements)
        batchResult.fold(
            onSuccess = { resList ->
                val userRows = resList.getOrElse(0) { emptyList() }
                val settingsRows = resList.getOrElse(1) { emptyList() }

                val appPassword = settingsRows.firstOrNull()?.get("value")?.toString() ?: "admin123"
                val isUserAdmin = cleanUser.equals("admin", ignoreCase = true)

                if (isUserAdmin && (cleanPass == appPassword || cleanPass == "admin123")) {
                    val userRow = userRows.firstOrNull()
                    val userId = userRow?.get("id")?.toString() ?: ADMIN_TURSO_UUID
                    return@withContext Result.success(AuthResult(
                        username = "admin",
                        userId = userId,
                        isLocalCache = false
                    ))
                }

                val userRow = userRows.firstOrNull()
                if (userRow != null) {
                    val storedHash = userRow["password_hash"]?.toString() ?: ""
                    val sha256 = AuthManager.hashPassword(cleanPass)
                    val userId = userRow["id"]?.toString() ?: ADMIN_TURSO_UUID
                    val actualUsername = userRow["username"]?.toString() ?: cleanUser

                    if (storedHash == sha256 || cleanPass == appPassword) {
                        return@withContext Result.success(AuthResult(
                            username = actualUsername,
                            userId = userId,
                            isLocalCache = false
                        ))
                    }
                }

                Result.failure(Exception("Invalid credentials"))
            },
            onFailure = { err ->
                Log.w(TAG, "verifyLogin failed to connect to Turso: ${err.message}")
                Result.failure(err)
            }
        )
    }

    /**
     * 全量双向同步所有模块数据（Transactions, RecycleBin, Categories, Accounts, Budgets, CategoryRules, Tools/Recurring/Installments）
     */
    suspend fun syncAllFromTurso(
        context: Context,
        targetUserId: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        val currentUsername = AuthManager.getCurrentUsername(context).ifBlank { "admin" }
        val isUserAdmin = currentUsername.equals("admin", ignoreCase = true)
        val currentUserId = resolveTursoUserId(context, targetUserId)

        if (isUserAdmin) {
            context.getSharedPreferences("ledger_auth_prefs", Context.MODE_PRIVATE).edit()
                .putString("current_user_id", ADMIN_TURSO_UUID)
                .apply()
        }

        Log.i(TAG, "Starting full bidirectional sync with Turso Cloud for user $currentUserId (admin=$isUserAdmin)...")

        val db = AppDatabase.getInstance(context)
        LocalLedgerRepository.initDefaultsIfNeeded(context)
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())

        val userFilterSql = if (isUserAdmin) {
            "(user_id = ? OR user_id = '$ADMIN_TURSO_UUID' OR user_id IS NULL)"
        } else {
            "user_id = ?"
        }

        // =========================================================================
        // PHASE 1: 探查云端现状并将本地未同步的变更（新交易、本地回收站删除、本地账户、本地预算）上传至 Turso
        // =========================================================================
        val preCheckStmts = listOf(
            Pair("SELECT id, deleted_at FROM transactions WHERE $userFilterSql", listOf<Any?>(currentUserId)),
            Pair("SELECT id, name, type FROM accounts WHERE $userFilterSql AND is_active = 1", listOf<Any?>(currentUserId)),
            Pair("SELECT category, monthly_limit FROM category_budgets WHERE user_id = ?", listOf<Any?>(currentUserId))
        )

        val preCheckRes = executeBatch(context, preCheckStmts)
        preCheckRes.onSuccess { preList ->
            val cloudTxIdRows = preList.getOrElse(0) { emptyList() }
            val cloudAccRows = preList.getOrElse(1) { emptyList() }
            val cloudBudgetRows = preList.getOrElse(2) { emptyList() }

            val allCloudTxIds = mutableSetOf<Long>()
            val deletedCloudTxIds = mutableSetOf<Long>()
            for (r in cloudTxIdRows) {
                val id = asLong(r["id"]) ?: continue
                allCloudTxIds.add(id)
                val delAt = r["deleted_at"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                if (delAt != null) {
                    deletedCloudTxIds.add(id)
                }
            }

            // 读取本地回收站 (local_recycle_bin_prefs) 中的已删除交易 ID
            val spTrash = context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
            val trashJsonStr = spTrash.getString("trash_items", "[]") ?: "[]"
            val localTrashIds = mutableMapOf<Long, String>()
            try {
                val arr = JSONArray(trashJsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val tid = obj.optLong("id", 0L)
                    val delTime = obj.optString("deleted_at", nowIso).ifBlank { nowIso }
                    if (tid > 0L) {
                        localTrashIds[tid] = delTime
                    }
                }
            } catch (_: Exception) {}

            val uploadStmts = mutableListOf<Pair<String, List<Any?>>>()

            // 1A. 如果本地回收站中有某笔交易，但云端 deleted_at 仍为 NULL，说明用户在本地删除了它 -> 同步软删除到云端并从本地活跃表移除
            for ((trashId, delTime) in localTrashIds) {
                db.localTransactionDao().deleteById(trashId)
                if (allCloudTxIds.contains(trashId) && !deletedCloudTxIds.contains(trashId)) {
                    uploadStmts.add(
                        Pair(
                            "UPDATE transactions SET deleted_at = ?, updated_at = ? WHERE id = ?",
                            listOf(delTime, nowIso, trashId)
                        )
                    )
                    deletedCloudTxIds.add(trashId)
                }
            }

            // 1B. 如果云端某笔交易已标记 deleted_at，从本地活跃表移除
            val localTxs = db.localTransactionDao().getAll()
            for (localTx in localTxs) {
                if (deletedCloudTxIds.contains(localTx.id)) {
                    db.localTransactionDao().deleteById(localTx.id)
                }
            }

            // 1C. 找出本地存在且不在回收站、但云端完全不存在的流水 -> 上传到 Turso
            val unuploadedTxs = db.localTransactionDao().getAll().filter {
                it.id > 0 && !allCloudTxIds.contains(it.id) && !localTrashIds.containsKey(it.id)
            }
            if (unuploadedTxs.isNotEmpty()) {
                Log.i(TAG, "Uploading ${unuploadedTxs.size} local-only transactions to Turso Cloud...")
                val insertTxSql = """
                    INSERT OR IGNORE INTO transactions (
                        id, date, type, category, amount, note, source, channel, tags, account_id, user_id, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                for (tx in unuploadedTxs) {
                    val createdIso = if (tx.time.isNotBlank()) "${tx.date}T${tx.time}" else nowIso
                    uploadStmts.add(
                        Pair(
                            insertTxSql,
                            listOf(
                                tx.id,
                                tx.date,
                                tx.type,
                                CategoryUiHelper.cleanCategoryName(tx.category),
                                tx.amount,
                                tx.note,
                                tx.source,
                                tx.channel,
                                tx.tags,
                                tx.accountId,
                                currentUserId,
                                createdIso,
                                nowIso
                            )
                        )
                    )
                }
            }

            // 1D. 同步本地账户 (local_accounts) 到 Turso accounts 表（如果云端尚未初始化账户）
            val existingCloudAccNames = cloudAccRows.mapNotNull {
                it["name"]?.toString()?.trim()?.lowercase(Locale.ROOT)
            }.toSet()
            val localAccs = db.localAccountDao().getAll()
            for (acc in localAccs) {
                val cleanName = acc.name.trim()
                if (cleanName.isNotEmpty() && !existingCloudAccNames.contains(cleanName.lowercase(Locale.ROOT))) {
                    uploadStmts.add(
                        Pair(
                            """
                            INSERT INTO accounts (id, user_id, name, type, credit_limit, currency, is_active, created_at, reconciled_balance)
                            VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                            ON CONFLICT(id) DO UPDATE SET name = excluded.name, type = excluded.type, credit_limit = excluded.credit_limit
                            """.trimIndent(),
                            listOf(acc.id, currentUserId, cleanName, acc.type, acc.creditLimit, acc.currency.ifBlank { "MYR" }, nowIso, acc.balance)
                        )
                    )
                }
            }

            // 1E. 将本地设置的预算（如 overall_limit 或新分类预算）补充上传到 Turso category_budgets
            val existingCloudBudgetCats = cloudBudgetRows.mapNotNull { it["category"]?.toString() }.toSet()
            val spBudget = context.getSharedPreferences("local_budget_prefs", Context.MODE_PRIVATE)
            if (spBudget.contains("overall_limit")) {
                val localOverall = spBudget.getFloat("overall_limit", 0f).toDouble()
                val isExplicit = spBudget.getBoolean("overall_explicit", false)
                // 3000.0 是未设置时的硬编码默认值；若用户在 Debug 设置了真实总预算（如 2000.0）且云端还没有 __OVERALL__，上传之
                if (isExplicit && localOverall > 0.0 && localOverall != 3000.0 && !existingCloudBudgetCats.contains("__OVERALL__")) {
                    uploadStmts.add(
                        Pair(
                            """
                            INSERT INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at)
                            VALUES (?, '__OVERALL__', ?, ?, ?)
                            ON CONFLICT(user_id, category) DO UPDATE SET monthly_limit = excluded.monthly_limit, updated_at = excluded.updated_at
                            """.trimIndent(),
                            listOf(currentUserId, localOverall, nowIso, nowIso)
                        )
                    )
                }
            }

            if (uploadStmts.isNotEmpty()) {
                uploadStmts.addAll(buildBumpVersionStatements(currentUserId, nowIso))
                executeBatch(context, uploadStmts).onSuccess {
                    Log.i(TAG, "Successfully pushed ${uploadStmts.size} local pending state changes to Turso Cloud!")
                }.onFailure { err ->
                    Log.w(TAG, "Failed to push some local state changes to Turso: ${err.message}")
                }
            }
        }

        // =========================================================================
        // PHASE 2: 从 Turso 云端一次性拉取全量 8 大表数据并写入本地 Room 与 SharedPreferences
        // =========================================================================
        val pullStmts = listOf(
            // 0: 分类表 (categories)
            Pair(
                "SELECT id, name, type, group_name, color FROM categories WHERE $userFilterSql ORDER BY id ASC",
                listOf<Any?>(currentUserId)
            ),
            // 1: 有效交易流水 (transactions active)
            Pair(
                "SELECT id, date, type, category, amount, note, source, channel, tags, user_id, account_id, created_at, updated_at FROM transactions WHERE $userFilterSql AND deleted_at IS NULL ORDER BY date DESC, id DESC",
                listOf<Any?>(currentUserId)
            ),
            // 2: 回收站已删除交易 (transactions deleted)
            Pair(
                "SELECT id, date, type, category, amount, note, source, channel, tags, account_id, created_at, deleted_at FROM transactions WHERE $userFilterSql AND deleted_at IS NOT NULL ORDER BY deleted_at DESC, id DESC LIMIT 100",
                listOf<Any?>(currentUserId)
            ),
            // 3: 账户表 (accounts)
            Pair(
                "SELECT id, name, type, credit_limit, currency, statement_day, due_day, is_active, reconciled_balance FROM accounts WHERE $userFilterSql AND is_active = 1 ORDER BY id ASC",
                listOf<Any?>(currentUserId)
            ),
            // 4: 预算表 (category_budgets)
            Pair(
                "SELECT category, monthly_limit FROM category_budgets WHERE user_id = ? ORDER BY id ASC",
                listOf<Any?>(currentUserId)
            ),
            // 5: 商户分类规则表 (merchant_category_overrides)
            Pair(
                "SELECT merchant_note, category, match_type, apply_count, is_active, created_at, updated_at FROM merchant_category_overrides WHERE user_id = ? ORDER BY updated_at DESC",
                listOf<Any?>(currentUserId)
            ),
            // 6: 固定收支与储蓄规则表 (recurring_rules)
            Pair(
                "SELECT id, type, group_name, category, amount, note, day_of_month, is_active, account_id FROM recurring_rules WHERE user_id = ? AND is_active = 1 ORDER BY day_of_month ASC, id ASC",
                listOf<Any?>(currentUserId)
            ),
            // 7: 订阅与分期表 (subscriptions & installments) + 用户设置 (user_settings)
            Pair(
                "SELECT id, name, category, billing_cycle, cost, currency, next_billing_date, auto_renew, payment_method_id FROM subscriptions WHERE user_id = ? AND status = 'ACTIVE' ORDER BY id ASC",
                listOf<Any?>(currentUserId)
            ),
            // 8: 分期表 (installments)
            Pair(
                "SELECT id, title, total_amount, tenure_months, paid_periods, monthly_amount, status FROM installments WHERE user_id = ? AND status = 'active' ORDER BY id DESC",
                listOf<Any?>(currentUserId)
            ),
            // 9: 用户个性化设置 (user_settings)
            Pair(
                "SELECT budget_start_day, currency_symbol, default_currency, language FROM user_settings WHERE user_id = ? LIMIT 1",
                listOf<Any?>(currentUserId)
            )
        )

        val batchResult = executeBatch(context, pullStmts)
        batchResult.fold(
            onSuccess = { resList ->
                val catRows = resList.getOrElse(0) { emptyList() }
                val activeTxRows = resList.getOrElse(1) { emptyList() }
                val deletedTxRows = resList.getOrElse(2) { emptyList() }
                val accRows = resList.getOrElse(3) { emptyList() }
                val budgetRows = resList.getOrElse(4) { emptyList() }
                val ruleRows = resList.getOrElse(5) { emptyList() }
                val recurringRows = resList.getOrElse(6) { emptyList() }
                val subRows = resList.getOrElse(7) { emptyList() }
                val instRows = resList.getOrElse(8) { emptyList() }
                val settingRows = resList.getOrElse(9) { emptyList() }

                // -----------------------------------------------------------------
                // 1. 同步分类 (local_categories & cached_categories)
                // -----------------------------------------------------------------
                if (catRows.isNotEmpty()) {
                    val localCats = mutableListOf<LocalCategory>()
                    val cachedCats = mutableListOf<CachedCategory>()
                    val seenKey = mutableSetOf<String>()
                    catRows.forEachIndexed { index, row ->
                        val id = asLong(row["id"])?.toInt() ?: (index + 1)
                        val name = CategoryUiHelper.cleanCategoryName(row["name"]?.toString() ?: "其他")
                        val type = row["type"]?.toString() ?: "expense"
                        val groupName = row["group_name"]?.toString()
                        val color = row["color"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "#94a3b8"
                        val icon = CategoryUiHelper.getCategoryEmoji(name, type)

                        if (seenKey.add("${type}_$name")) {
                            localCats.add(
                                LocalCategory(
                                    id = id,
                                    name = name,
                                    type = type,
                                    icon = icon,
                                    color = color,
                                    orderNum = index + 1
                                )
                            )
                            cachedCats.add(
                                CachedCategory(
                                    id = id.toLong(),
                                    name = name,
                                    type = type,
                                    group_name = groupName ?: "personal"
                                )
                            )
                        }
                    }
                    db.localCategoryDao().deleteAll()
                    db.localCategoryDao().insertAll(localCats)
                    try {
                        db.cachedCategoryDao().clearAll()
                        db.cachedCategoryDao().insertAll(cachedCats)
                    } catch (_: Exception) {}
                    Log.i(TAG, "Synced ${localCats.size} categories from Turso.")
                }

                // -----------------------------------------------------------------
                // 2. 同步活跃交易流水 (local_transactions)
                // -----------------------------------------------------------------
                var syncedTxCount = 0
                val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                val txEntities = activeTxRows.map { row ->
                    val id = asLong(row["id"]) ?: 0L
                    val date = row["date"]?.toString() ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val type = row["type"]?.toString() ?: "expense"
                    val category = CategoryUiHelper.cleanCategoryName(row["category"]?.toString() ?: "其他")
                    val amount = asDouble(row["amount"])
                    val note = row["note"]?.toString()?.takeIf { it != "null" } ?: ""
                    val source = row["source"]?.toString()?.takeIf { it != "null" } ?: "manual"
                    val channel = row["channel"]?.toString()?.takeIf { it != "null" && it.isNotBlank() }
                    val tags = row["tags"]?.toString()?.takeIf { it != "null" } ?: ""

                    val createdAtRaw = row["created_at"]?.toString() ?: ""
                    var timeStr = ""
                    var createdAtMillis = System.currentTimeMillis()
                    if (createdAtRaw.contains("T")) {
                        val timePart = createdAtRaw.substringAfter("T").substringBefore(".")
                        if (timePart.isNotBlank()) timeStr = timePart
                        try {
                            createdAtMillis = sdfIso.parse(createdAtRaw.substringBefore("."))?.time ?: createdAtMillis
                        } catch (_: Exception) {}
                    }
                    val accountId = asLong(row["account_id"])?.toInt()

                    LocalTransaction(
                        id = id,
                        date = date,
                        time = timeStr,
                        type = type,
                        category = category,
                        amount = amount,
                        note = note,
                        tags = tags,
                        accountId = accountId,
                        toAccountId = null,
                        source = source,
                        channel = channel,
                        createdAt = createdAtMillis,
                        updatedAt = createdAtMillis
                    )
                }
                // 全量对齐本地活跃流水（消除在云端已删除但本地残留的脏记录）
                val existingLocal = db.localTransactionDao().getAll()
                val activeCloudIds = txEntities.map { it.id }.toSet()
                for (loc in existingLocal) {
                    if (!activeCloudIds.contains(loc.id)) {
                        db.localTransactionDao().deleteById(loc.id)
                    }
                }
                if (txEntities.isNotEmpty()) {
                    db.localTransactionDao().insertAll(txEntities)
                    syncedTxCount = txEntities.size
                }
                Log.i(TAG, "Synced $syncedTxCount active transactions from Turso.")

                // -----------------------------------------------------------------
                // 3. 同步回收站已删除交易 (local_recycle_bin_prefs)
                // -----------------------------------------------------------------
                val trashArr = JSONArray()
                for (row in deletedTxRows) {
                    val id = asLong(row["id"]) ?: continue
                    val createdRaw = row["created_at"]?.toString() ?: ""
                    val timePart = if (createdRaw.contains("T")) createdRaw.substringAfter("T").substringBefore(".") else "12:00:00"
                    val delAtRaw = row["deleted_at"]?.toString()?.replace("T", " ")?.take(16) ?: ""
                    trashArr.put(JSONObject().apply {
                        put("id", id)
                        put("date", row["date"]?.toString() ?: "")
                        put("time", timePart)
                        put("type", row["type"]?.toString() ?: "expense")
                        put("category", CategoryUiHelper.cleanCategoryName(row["category"]?.toString() ?: "其他"))
                        put("amount", asDouble(row["amount"]))
                        put("note", row["note"]?.toString()?.takeIf { it != "null" } ?: "")
                        put("tags", row["tags"]?.toString()?.takeIf { it != "null" } ?: "")
                        put("account_id", asLong(row["account_id"])?.toInt() ?: 0)
                        put("source", row["source"]?.toString() ?: "manual")
                        put("deleted_at", delAtRaw)
                    })
                }
                context.getSharedPreferences("local_recycle_bin_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("trash_items", trashArr.toString())
                    .apply()
                Log.i(TAG, "Synced ${trashArr.length()} recycle bin items from Turso.")

                // -----------------------------------------------------------------
                // 4. 同步账户与自动关联渠道补全 (local_accounts)
                // -----------------------------------------------------------------
                if (accRows.isNotEmpty()) {
                    val existingAccMap = db.localAccountDao().getAll().associateBy { it.name.lowercase(Locale.ROOT) }
                    val syncedAccs = accRows.mapIndexed { idx, r ->
                        val id = asLong(r["id"])?.toInt() ?: (idx + 1)
                        val name = r["name"]?.toString() ?: "默认账户"
                        val type = r["type"]?.toString() ?: "savings"
                        val creditLimit = asDouble(r["credit_limit"])
                        val currency = r["currency"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "RM"
                        val recBal = r["reconciled_balance"]?.let { asDouble(it) }
                        val localMatch = existingAccMap[name.lowercase(Locale.ROOT)]
                        LocalAccount(
                            id = id,
                            name = name,
                            type = type,
                            balance = recBal ?: localMatch?.balance ?: 0.0,
                            creditLimit = creditLimit,
                            currency = currency,
                            orderNum = idx + 1
                        )
                    }
                    db.localAccountDao().insertAll(syncedAccs)
                }

                // 为缺失 accountId 的本地流水智能关联对应渠道账户
                val allAccounts = db.localAccountDao().getAll()
                for (tx in db.localTransactionDao().getAll()) {
                    if (tx.accountId == null) {
                        val fallback = LocalLedgerRepository.resolveAccountFallback(tx.channel, tx.note, allAccounts)
                        if (fallback != null) {
                            db.localTransactionDao().update(tx.copy(accountId = fallback.id))
                        }
                    }
                }

                // -----------------------------------------------------------------
                // 5. 同步月度预算与账单起始日 (local_budget_prefs)
                // -----------------------------------------------------------------
                if (budgetRows.isNotEmpty() || settingRows.isNotEmpty()) {
                    val spBudget = context.getSharedPreferences("local_budget_prefs", Context.MODE_PRIVATE)
                    val editor = spBudget.edit()

                    // 先清除旧的分类限额，避免残留
                    for (k in spBudget.all.keys.toList()) {
                        if (k.startsWith("cat_limit_")) {
                            editor.remove(k)
                        }
                    }

                    var explicitOverall = 0.0
                    var sumCategoryLimits = 0.0
                    for (bRow in budgetRows) {
                        val cat = bRow["category"]?.toString() ?: continue
                        val limit = asDouble(bRow["monthly_limit"])
                        if (cat == "__OVERALL__") {
                            explicitOverall = limit
                        } else if (limit > 0.0) {
                            sumCategoryLimits += limit
                            editor.putFloat("cat_limit_$cat", limit.toFloat())
                        }
                    }

                    val effectiveOverall = if (explicitOverall > 0.0) explicitOverall else if (sumCategoryLimits > 0.0) sumCategoryLimits else 2000.0
                    editor.putFloat("overall_limit", effectiveOverall.toFloat())
                    editor.putBoolean("overall_explicit", explicitOverall > 0.0)
                    editor.putBoolean("overall_set", true)

                    val startDay = settingRows.firstOrNull()?.get("budget_start_day")?.let { asLong(it)?.toInt() } ?: 1
                    editor.putInt("start_day", startDay.coerceIn(1, 28))
                    editor.apply()
                    Log.i(TAG, "Synced ${budgetRows.size} budget entries (effectiveOverall=$effectiveOverall, startDay=$startDay) from Turso.")
                }

                // -----------------------------------------------------------------
                // 6. 同步商户分类规则 (local_category_rules_prefs)
                // -----------------------------------------------------------------
                if (ruleRows.isNotEmpty()) {
                    val rulesArr = JSONArray()
                    for (rRow in ruleRows) {
                        val pattern = rRow["merchant_note"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                        val cat = CategoryUiHelper.cleanCategoryName(rRow["category"]?.toString() ?: "其他")
                        val matchType = rRow["match_type"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: "contains"
                        val applyCount = asLong(rRow["apply_count"])?.toInt() ?: 0
                        val isActive = (asLong(rRow["is_active"]) ?: 1L) != 0L
                        val createdAt = rRow["created_at"]?.toString()?.takeIf { it != "null" } ?: ""
                        val updatedAt = rRow["updated_at"]?.toString()?.takeIf { it != "null" } ?: ""

                        rulesArr.put(JSONObject().apply {
                            put("merchant_pattern", pattern)
                            put("category", cat)
                            put("match_type", matchType)
                            put("apply_count", applyCount)
                            put("is_active", isActive)
                            put("created_at", createdAt)
                            put("updated_at", updatedAt)
                        })
                    }
                    context.getSharedPreferences("local_category_rules_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("rules_json", rulesArr.toString())
                        .apply()
                    Log.i(TAG, "Synced ${rulesArr.length()} merchant category rules from Turso.")
                }

                // -----------------------------------------------------------------
                // 7. 同步固定收支、周期订阅、固定储蓄与分期计划 (local_tools_prefs)
                // -----------------------------------------------------------------
                val accIdToName = db.localAccountDao().getAll().associate { it.id to it.name }
                val subsArr = JSONArray()
                val fixedIncomesArr = JSONArray()
                val fixedSavingsArr = JSONArray()
                val instArr = JSONArray()

                // 7A. subscriptions 表中的显式订阅
                for (sRow in subRows) {
                    val sid = asLong(sRow["id"])?.toInt() ?: 1
                    val sName = sRow["name"]?.toString() ?: "周期订阅"
                    val sCat = sRow["category"]?.toString() ?: "账单"
                    val sCycle = sRow["billing_cycle"]?.toString() ?: "monthly"
                    val sCost = asDouble(sRow["cost"])
                    val sCurr = sRow["currency"]?.toString() ?: "MYR"
                    val sNext = sRow["next_billing_date"]?.toString() ?: "每月1号"
                    val sAuto = (asLong(sRow["auto_renew"]) ?: 1L) != 0L
                    val accId = asLong(sRow["payment_method_id"])?.toInt()

                    subsArr.put(JSONObject().apply {
                        put("id", sid)
                        put("name", sName)
                        put("category", sCat)
                        put("billing_cycle", sCycle)
                        put("cost", sCost)
                        put("currency", sCurr)
                        put("next_billing_date", sNext)
                        put("auto_renew", sAuto)
                        if (accId != null) {
                            put("account_id", accId)
                            accIdToName[accId]?.let { put("account_name", it) }
                        }
                    })
                }

                // 7B. recurring_rules 表中的固定支出、固定收入、固定储蓄
                for (rRow in recurringRows) {
                    val rid = asLong(rRow["id"])?.toInt() ?: 1
                    val rType = rRow["type"]?.toString() ?: "expense"
                    val rCat = rRow["category"]?.toString() ?: "日常"
                    val rAmt = asDouble(rRow["amount"])
                    val rNote = rRow["note"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: ""
                    val dom = asLong(rRow["day_of_month"])?.toInt() ?: 1
                    val accId = asLong(rRow["account_id"])?.toInt()
                    val displayName = if (rNote.isEmpty()) rCat else "$rCat - $rNote"
                    val dayLabel = "每月${dom}号"

                    when (rType) {
                        "expense" -> {
                            subsArr.put(JSONObject().apply {
                                put("id", rid)
                                put("name", displayName)
                                put("category", rCat)
                                put("billing_cycle", "monthly")
                                put("cost", rAmt)
                                put("currency", "MYR")
                                put("next_billing_date", dayLabel)
                                put("auto_renew", true)
                                if (accId != null) {
                                    put("account_id", accId)
                                    accIdToName[accId]?.let { put("account_name", it) }
                                }
                            })
                        }
                        "income" -> {
                            fixedIncomesArr.put(JSONObject().apply {
                                put("id", rid)
                                put("name", displayName)
                                put("category", rCat)
                                put("amount", rAmt)
                                put("currency", "MYR")
                                put("arrival_day", dayLabel)
                                put("is_active", true)
                                if (accId != null) {
                                    put("account_id", accId)
                                    accIdToName[accId]?.let { put("account_name", it) }
                                }
                            })
                        }
                        "savings" -> {
                            fixedSavingsArr.put(JSONObject().apply {
                                put("id", rid)
                                put("name", displayName)
                                put("category", rCat)
                                put("cost", rAmt)
                                put("amount", rAmt)
                                put("currency", "MYR")
                                put("arrival_day", dayLabel)
                                put("next_billing_date", dayLabel)
                                put("is_active", true)
                                put("auto_renew", true)
                                if (accId != null) {
                                    put("account_id", accId)
                                    accIdToName[accId]?.let { put("account_name", it) }
                                }
                            })
                        }
                    }
                }

                // 7C. installments 表中的分期计划
                for (iRow in instRows) {
                    val iid = asLong(iRow["id"])?.toInt() ?: 1
                    val title = iRow["title"]?.toString() ?: "分期付款"
                    val tot = asDouble(iRow["total_amount"])
                    val tenure = (asLong(iRow["tenure_months"])?.toInt() ?: 12).coerceAtLeast(1)
                    val paid = (asLong(iRow["paid_periods"])?.toInt() ?: 0).coerceIn(0, tenure)
                    val mAmt = asDouble(iRow["monthly_amount"]).let { if (it > 0.0) it else tot / tenure }
                    val remain = (tot - paid * mAmt).coerceAtLeast(0.0)

                    instArr.put(JSONObject().apply {
                        put("id", iid)
                        put("title", title)
                        put("total_amount", tot)
                        put("tenure_months", tenure)
                        put("paid_periods", paid)
                        put("monthly_amount", mAmt)
                        put("remaining_amount", remain)
                    })
                }

                context.getSharedPreferences("local_tools_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("subscriptions_json", subsArr.toString())
                    .putString("fixed_incomes_json", fixedIncomesArr.toString())
                    .putString("fixed_savings_json", fixedSavingsArr.toString())
                    .putString("installments_json", instArr.toString())
                    .apply()
                Log.i(TAG, "Synced tools from Turso: subs=${subsArr.length()}, incomes=${fixedIncomesArr.length()}, savings=${fixedSavingsArr.length()}, installments=${instArr.length()}.")

                // -----------------------------------------------------------------
                // 8. 全面清理各级内存与快照缓存，广播通知所有界面立即刷新
                // -----------------------------------------------------------------
                LocalDataStore.clearOverviewCache(context)
                LocalDataStore.clearRecordsCache(context)
                CashflowRepository.invalidate(context)
                NativeLedgerApi.invalidateCache()

                try {
                    val intent = Intent("com.siangloh.ledger.DATA_SYNCED")
                    intent.setPackage(context.packageName)
                    context.sendBroadcast(intent)
                } catch (_: Exception) {}

                Result.success(syncedTxCount)
            },
            onFailure = { err ->
                Log.e(TAG, "syncAllFromTurso failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    // =========================================================================
    // 实时写入同步接口 (Mutations -> Turso Cloud)
    // =========================================================================

    suspend fun insertTransaction(
        context: Context,
        tx: LocalTransaction,
        userId: String? = null
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val createdIso = if (tx.time.isNotBlank()) "${tx.date}T${tx.time}" else nowIso

            val stmts = mutableListOf<Pair<String, List<Any?>>>()
            if (tx.id > 0) {
                val sql = """
                    INSERT OR REPLACE INTO transactions (
                        id, date, type, category, amount, note, source, channel, tags, account_id, user_id, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                val args: List<Any?> = listOf(
                    tx.id,
                    tx.date,
                    tx.type,
                    CategoryUiHelper.cleanCategoryName(tx.category),
                    tx.amount,
                    tx.note,
                    tx.source,
                    tx.channel,
                    tx.tags,
                    tx.accountId,
                    uId,
                    createdIso,
                    nowIso
                )
                stmts.add(Pair(sql, args))
            } else {
                val sql = """
                    INSERT INTO transactions (
                        date, type, category, amount, note, source, channel, tags, account_id, user_id, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
                val args: List<Any?> = listOf(
                    tx.date,
                    tx.type,
                    CategoryUiHelper.cleanCategoryName(tx.category),
                    tx.amount,
                    tx.note,
                    tx.source,
                    tx.channel,
                    tx.tags,
                    tx.accountId,
                    uId,
                    createdIso,
                    nowIso
                )
                stmts.add(Pair(sql, args))
            }
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))

            executeBatch(context, stmts).fold(
                onSuccess = {
                    Log.i(TAG, "Synced new transaction id=${tx.id} (${tx.note}) to Turso Cloud.")
                    Result.success(tx.id)
                },
                onFailure = {
                    Log.w(TAG, "insertTransaction to Turso failed: ${it.message}")
                    Result.failure(it)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTransaction(
        context: Context,
        tx: LocalTransaction,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val sql = """
                UPDATE transactions
                SET date = ?, type = ?, category = ?, amount = ?, note = ?, tags = ?, account_id = ?, updated_at = ?
                WHERE id = ?
            """.trimIndent()
            val args: List<Any?> = listOf(
                tx.date,
                tx.type,
                CategoryUiHelper.cleanCategoryName(tx.category),
                tx.amount,
                tx.note,
                tx.tags,
                tx.accountId,
                nowIso,
                tx.id
            )
            val stmts = mutableListOf(Pair(sql, args))
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))

            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateTransactionCategory(
        context: Context,
        id: Long,
        category: String,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val cleanCat = CategoryUiHelper.cleanCategoryName(category)
            val stmts = mutableListOf<Pair<String, List<Any?>>>(
                Pair("UPDATE transactions SET category = ?, updated_at = ? WHERE id = ?", listOf(cleanCat, nowIso, id))
            )
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))

            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteTransaction(
        context: Context,
        id: Long,
        permanent: Boolean = false,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val stmt = if (permanent) {
                Pair("DELETE FROM transactions WHERE id = ?", listOf<Any?>(id))
            } else {
                Pair("UPDATE transactions SET deleted_at = ?, updated_at = ? WHERE id = ?", listOf<Any?>(nowIso, nowIso, id))
            }
            val stmts = mutableListOf(stmt)
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))

            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun emptyRecycleBin(
        context: Context,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val stmts = mutableListOf<Pair<String, List<Any?>>>(
                Pair("DELETE FROM transactions WHERE (user_id = ? OR user_id IS NULL) AND deleted_at IS NOT NULL", listOf(uId))
            )
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreTransaction(
        context: Context,
        tx: LocalTransaction,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val createdIso = if (tx.time.isNotBlank()) "${tx.date}T${tx.time}" else nowIso

            val stmts = mutableListOf<Pair<String, List<Any?>>>(
                Pair("UPDATE transactions SET deleted_at = NULL, updated_at = ? WHERE id = ?", listOf(nowIso, tx.id)),
                Pair(
                    """
                    INSERT OR IGNORE INTO transactions (
                        id, date, type, category, amount, note, source, channel, tags, account_id, user_id, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(
                        tx.id,
                        tx.date,
                        tx.type,
                        CategoryUiHelper.cleanCategoryName(tx.category),
                        tx.amount,
                        tx.note,
                        tx.source,
                        tx.channel,
                        tx.tags,
                        tx.accountId,
                        uId,
                        createdIso,
                        nowIso
                    )
                )
            )
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))

            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsertAccount(
        context: Context,
        acc: LocalAccount,
        userId: String? = null
    ): Result<Unit> = upsertAccount(
        context = context,
        id = acc.id,
        name = acc.name,
        type = acc.type,
        balance = acc.balance,
        creditLimit = acc.creditLimit,
        currency = acc.currency,
        userId = userId
    )

    suspend fun upsertAccount(
        context: Context,
        id: Int?,
        name: String,
        type: String,
        balance: Double,
        creditLimit: Double,
        currency: String,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val stmts = mutableListOf<Pair<String, List<Any?>>>()
            if (id != null && id > 0) {
                stmts.add(
                    Pair(
                        """
                        INSERT INTO accounts (id, user_id, name, type, credit_limit, currency, is_active, created_at, reconciled_balance)
                        VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            name = excluded.name,
                            type = excluded.type,
                            credit_limit = excluded.credit_limit,
                            currency = excluded.currency,
                            reconciled_balance = excluded.reconciled_balance
                        """.trimIndent(),
                        listOf(id, uId, name, type, creditLimit, currency, nowIso, balance)
                    )
                )
            } else {
                stmts.add(
                    Pair(
                        """
                        INSERT INTO accounts (user_id, name, type, credit_limit, currency, is_active, created_at, reconciled_balance)
                        VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                        """.trimIndent(),
                        listOf(uId, name, type, creditLimit, currency, nowIso, balance)
                    )
                )
            }
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveBudget(
        context: Context,
        category: String,
        limit: Double,
        billingCycleStartDay: Int? = null,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val stmts = mutableListOf<Pair<String, List<Any?>>>()
            if (limit <= 0.0) {
                stmts.add(Pair("DELETE FROM category_budgets WHERE user_id = ? AND category = ?", listOf(uId, category)))
            } else {
                stmts.add(
                    Pair(
                        """
                        INSERT INTO category_budgets (user_id, category, monthly_limit, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(user_id, category) DO UPDATE SET
                            monthly_limit = excluded.monthly_limit,
                            updated_at = excluded.updated_at
                        """.trimIndent(),
                        listOf(uId, category, limit, nowIso, nowIso)
                    )
                )
            }
            if (billingCycleStartDay != null) {
                stmts.add(
                    Pair(
                        "UPDATE user_settings SET budget_start_day = ?, updated_at = ? WHERE user_id = ?",
                        listOf(billingCycleStartDay, nowIso, uId)
                    )
                )
            }
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveCategoryRule(
        context: Context,
        pattern: String,
        category: String,
        matchType: String,
        isActive: Boolean,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val stmts = mutableListOf<Pair<String, List<Any?>>>(
                Pair(
                    """
                    INSERT OR REPLACE INTO merchant_category_overrides (
                        user_id, merchant_note, category, match_type, is_active, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(uId, pattern, CategoryUiHelper.cleanCategoryName(category), matchType, if (isActive) 1 else 0, nowIso)
                )
            )
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCategoryRule(
        context: Context,
        pattern: String,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val stmts = mutableListOf<Pair<String, List<Any?>>>(
                Pair("DELETE FROM merchant_category_overrides WHERE user_id = ? AND LOWER(merchant_note) = LOWER(?)", listOf(uId, pattern))
            )
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveRecurringRule(
        context: Context,
        id: Int?,
        type: String,
        category: String,
        amount: Double,
        note: String,
        dayOfMonth: Int,
        isActive: Boolean = true,
        accountId: Int? = null,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val groupName = if (type == "income") "main" else null
            val stmts = mutableListOf<Pair<String, List<Any?>>>()
            if (id != null && id > 0) {
                val actualId = if (id > 10000) id - 10000 else id
                stmts.add(
                    Pair(
                        """
                        INSERT INTO recurring_rules (id, type, group_name, category, amount, note, day_of_month, is_active, created_at, user_id, account_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            category = excluded.category,
                            amount = excluded.amount,
                            note = excluded.note,
                            day_of_month = excluded.day_of_month,
                            is_active = excluded.is_active,
                            account_id = excluded.account_id
                        """.trimIndent(),
                        listOf(actualId, type, groupName, category, amount, note, dayOfMonth, if (isActive) 1 else 0, nowIso, uId, accountId)
                    )
                )
            } else {
                stmts.add(
                    Pair(
                        """
                        INSERT INTO recurring_rules (type, group_name, category, amount, note, day_of_month, is_active, created_at, user_id, account_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        listOf(type, groupName, category, amount, note, dayOfMonth, if (isActive) 1 else 0, nowIso, uId, accountId)
                    )
                )
            }
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteRecurringRule(
        context: Context,
        id: Int,
        userId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uId = resolveTursoUserId(context, userId)
            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date())
            val actualId = if (id > 10000) id - 10000 else id
            val stmts = mutableListOf<Pair<String, List<Any?>>>(
                Pair("DELETE FROM recurring_rules WHERE id = ? AND user_id = ?", listOf(actualId, uId)),
                Pair("DELETE FROM subscriptions WHERE id = ? AND user_id = ?", listOf(id, uId))
            )
            stmts.addAll(buildBumpVersionStatements(uId, nowIso))
            executeBatch(context, stmts).fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
