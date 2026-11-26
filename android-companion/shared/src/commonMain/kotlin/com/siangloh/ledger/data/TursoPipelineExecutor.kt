package com.siangloh.ledger.data

import com.siangloh.ledger.core.json.JSONArray
import com.siangloh.ledger.core.json.JSONObject
import com.siangloh.ledger.core.net.KmpHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Multiplatform Turso Cloud HTTP Pipeline (/v2/pipeline) executor shared across Android and iOS.
 */
object TursoPipelineExecutor {
    const val DEFAULT_TURSO_PIPELINE_URL =
        "https://ledger-app-siangloh.aws-ap-northeast-1.turso.io/v2/pipeline"
    const val DEFAULT_TURSO_TOKEN =
        "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3OTI5OTYwMjksImlkIjoiMDFhMDc5NDctZDAwMS03MjZkLWE0NzQtNmM1MmYzODM5ZDVhIiwia2lkIjoiUFpvelZYb3JTZkVlSWFmNS1ZdGQyNEFGMG5xbTJ4UkxHZ1MyM3cxZTVsbyIsInJpZCI6IjQxN2YwNTVkLWZmMWQtNDhlZC04YmFjLTVhZDExNGY3MGUxNCJ9.ktieEm8f_mp6yh7CnwMA6Py6G8CrkCB9JYLCIj3eLcvc3VJZrmzqVAonJAi3-cb0YEusfZEaNksg7Jhd18bKDg"
    const val ADMIN_TURSO_UUID = "f84e359d-b99e-4ac2-b098-298e9d2661ea"

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Generates a 16-character lowercase random hex token for `system_metadata` version tracking.
     */
    fun generateVersionToken(): String {
        val bytes = Random.nextBytes(8)
        val chars = CharArray(16)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xff
            chars[i * 2] = HEX_DIGITS[v ushr 4]
            chars[i * 2 + 1] = HEX_DIGITS[v and 0x0f]
        }
        return chars.concatToString()
    }

    /**
     * Builds the pair of `system_metadata` upsert statements bumping global and per-user data versions.
     */
    fun buildBumpVersionStatements(
        userId: String,
        nowIso: String
    ): List<Pair<String, List<Any?>>> {
        val vToken = generateVersionToken()
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

    internal fun buildRequestPayload(statements: List<Pair<String, List<Any?>>>): String {
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
            requestsArray.put(
                JSONObject().apply {
                    put("type", "execute")
                    put("stmt", stmtObj)
                }
            )
        }
        requestsArray.put(
            JSONObject().apply {
                put("type", "close")
            }
        )
        return JSONObject().apply {
            put("requests", requestsArray)
        }.toString()
    }

    internal fun parsePipelineResponse(respBody: String): Result<List<List<Map<String, Any?>>>> {
        val rootJson = JSONObject(respBody)
        val resultsArr = rootJson.optJSONArray("results") ?: JSONArray()
        val allStatementResults = mutableListOf<List<Map<String, Any?>>>()

        for (i in 0 until resultsArr.length()) {
            val resObj = resultsArr.optJSONObject(i) ?: continue
            val type = resObj.optString("type")
            if (type == "error") {
                val errMsg = resObj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Turso query error"
                return Result.failure(Exception(errMsg))
            }
            if (type != "ok") continue

            val respObj = resObj.optJSONObject("response") ?: continue
            if (respObj.optString("type") == "close") continue

            val resultObj = respObj.optJSONObject("result") ?: continue
            val colsArr = resultObj.optJSONArray("cols") ?: JSONArray()
            val colNames = mutableListOf<String>()
            for (c in 0 until colsArr.length()) {
                colNames.add(colsArr.optJSONObject(c)?.optString("name")?.takeIf { it.isNotEmpty() } ?: "col_$c")
            }

            val rowsArr = resultObj.optJSONArray("rows") ?: JSONArray()
            val statementRows = mutableListOf<Map<String, Any?>>()
            for (r in 0 until rowsArr.length()) {
                val rowArr = rowsArr.optJSONArray(r) ?: continue
                val rowMap = mutableMapOf<String, Any?>()
                for (c in 0 until rowArr.length()) {
                    val colName = colNames.getOrElse(c) { "col_$c" }
                    val cellObj = rowArr.optJSONObject(c)
                    val cellValue: Any? = if (cellObj != null) {
                        when (cellObj.optString("type")) {
                            "null" -> null
                            "integer" -> cellObj.optString("value").toLongOrNull()
                            "float" -> cellObj.optDouble("value")
                            else -> {
                                val raw = cellObj.opt("value")
                                if (raw == null || raw === JSONObject.NULL) null else raw
                            }
                        }
                    } else {
                        null
                    }
                    rowMap[colName] = cellValue
                }
                statementRows.add(rowMap)
            }
            allStatementResults.add(statementRows)
        }

        return Result.success(allStatementResults)
    }

    suspend fun executeBatch(
        endpoint: String = DEFAULT_TURSO_PIPELINE_URL,
        authToken: String = DEFAULT_TURSO_TOKEN,
        statements: List<Pair<String, List<Any?>>>
    ): Result<List<List<Map<String, Any?>>>> = withContext(Dispatchers.IO) {
        if (statements.isEmpty()) return@withContext Result.success(emptyList())
        try {
            val payload = buildRequestPayload(statements)
            val httpResp = KmpHttpClient.postJson(
                url = endpoint,
                jsonBody = payload,
                headers = mapOf("Authorization" to "Bearer $authToken")
            )
            if (httpResp.statusCode !in 200..299) {
                return@withContext Result.failure(
                    Exception("Turso HTTP ${httpResp.statusCode}: ${httpResp.body}")
                )
            }
            parsePipelineResponse(httpResp.body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
