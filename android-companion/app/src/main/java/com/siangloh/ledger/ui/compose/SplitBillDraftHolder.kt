package com.siangloh.ledger.ui.compose

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.siangloh.ledger.NetworkHelper
import com.siangloh.ledger.parser.ParsedReceiptData
import com.siangloh.ledger.parser.ReceiptOcrParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

data class SplitItemState(
    val id: String,
    val name: String,
    val price: Double,
    val assignedMembers: Set<String>
)

data class ReceiptJobState(
    val id: String,
    val uri: Uri? = null,
    val isAnalyzing: Boolean = true,
    val thumbnail: Bitmap? = null,
    val merchant: String = "Receipt",
    val items: List<SplitItemState> = emptyList(),
    val serviceCharge: Double = 0.0,
    val sst: Double = 0.0,
    val adjustment: Double = 0.0,
    val detectedTotal: Double = 0.0,
    val rawLines: List<String> = emptyList(),
    val errorMsg: String? = null
) {
    val itemsSubtotal: Double
        get() = round(items.sumOf { it.price } * 100.0) / 100.0

    val computedTotal: Double
        get() = round((itemsSubtotal + serviceCharge + sst + adjustment) * 100.0) / 100.0

    val tallyDiff: Double
        get() = if (detectedTotal > 0.0) round((computedTotal - detectedTotal) * 100.0) / 100.0 else 0.0

    val isTallied: Boolean
        get() = detectedTotal <= 0.0 || abs(tallyDiff) <= 0.05
}

/**
 * Pure calculation & splitting helpers for AA Split Bill (directly unit-testable without UI).
 */
object SplitBillLogic {
    private val MULTI_QTY_PREFIX_REGEX = Regex("""^(\d+)\s*[xX*]\s+(.+)$""")

    fun extractQuantityPrefix(name: String): Pair<Int, String>? {
        val m = MULTI_QTY_PREFIX_REGEX.find(name.trim()) ?: return null
        val qty = m.groupValues[1].toIntOrNull() ?: return null
        val baseName = m.groupValues[2].trim()
        if (qty !in 2..20 || baseName.isBlank()) return null
        return qty to baseName
    }

    /**
     * Splits a multi-quantity item (e.g. "3x Teh Ais" for RM 13.50 or RM 10.00) into individual
     * per-serving rows whose summed price matches the original item price to the exact sen.
     */
    fun splitMultiQuantityItem(item: SplitItemState): List<SplitItemState> {
        val (qty, baseName) = extractQuantityPrefix(item.name) ?: return listOf(item)
        val totalCents = round(item.price * 100.0).toInt()
        val baseCents = totalCents / qty
        val remainder = totalCents % qty
        return (1..qty).map { idx ->
            val cents = baseCents + if (idx <= remainder) 1 else 0
            SplitItemState(
                id = "${item.id}_q$idx",
                name = "$baseName (#$idx)",
                price = cents / 100.0,
                assignedMembers = item.assignedMembers
            )
        }
    }

    fun computeMemberTotals(jobs: List<ReceiptJobState>, members: List<String>): Map<String, Double> {
        val map = linkedMapOf<String, Double>()
        members.forEach { map[it] = 0.0 }
        jobs.filter { !it.isAnalyzing }.forEach { job ->
            val jobItemsSub = job.itemsSubtotal
            val jobExtra = job.serviceCharge + job.sst + job.adjustment
            val jobPersonSub = mutableMapOf<String, Double>()
            job.items.forEach { item ->
                val assignees = item.assignedMembers.filter { it in members }
                if (assignees.isNotEmpty()) {
                    val share = item.price / assignees.size
                    assignees.forEach { m ->
                        jobPersonSub[m] = (jobPersonSub[m] ?: 0.0) + share
                    }
                }
            }
            val assignedSubTotal = jobPersonSub.values.sum()
            val taxFactor = if (assignedSubTotal > 0.0 && jobItemsSub > 0.0) {
                (assignedSubTotal + jobExtra * (assignedSubTotal / jobItemsSub)) / assignedSubTotal
            } else 1.0
            jobPersonSub.forEach { (m, sub) ->
                map[m] = (map[m] ?: 0.0) + sub * taxFactor
            }
        }
        return map.mapValues { (_, v) -> round(v * 100.0) / 100.0 }
    }
}

/**
 * Holds AA Split Bill queue and frequent friend chips across tab switches, background OCR scans,
 * and configuration changes, while persisting frequent friend names per user/server profile.
 */
object SplitBillDraftHolder {
    var members by mutableStateOf<List<String>>(emptyList())
    var receiptJobs by mutableStateOf<List<ReceiptJobState>>(emptyList())
    var activeJobId by mutableStateOf<String?>(null)
    var pendingCameraUri: Uri? = null

    private val ocrScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val activeTasks = mutableMapOf<String, Job>()
    private var currentProfileKey: String? = null
    private var initialized = false

    private fun getPrefKey(context: Context): String {
        val server = NetworkHelper.getServerUrl(context)
        val user = NetworkHelper.getUsername(context).ifBlank { "default_user" }
        return "split_bill_prefs_${user}_${server.hashCode()}"
    }

    fun ensureInitialized(context: Context, defaultMe: String) {
        val activeKey = getPrefKey(context)
        if (currentProfileKey != null && currentProfileKey != activeKey) {
            // Reset session when switching user/server account
            clearReceipts()
            initialized = false
        }
        currentProfileKey = activeKey

        if (!initialized) {
            initialized = true
            val prefs = context.getSharedPreferences(activeKey, Context.MODE_PRIVATE)
            val savedJson = prefs.getString("saved_friends", "[]") ?: "[]"
            val loaded = mutableListOf(defaultMe)
            try {
                val arr = JSONArray(savedJson)
                for (i in 0 until arr.length()) {
                    val f = arr.optString(i).trim()
                    if (f.isNotBlank() && f !in loaded) {
                        loaded.add(f)
                    }
                }
            } catch (e: Exception) {
                Log.w("SplitBillDraftHolder", "Failed to parse saved_friends JSON; resetting to default", e)
            }
            members = loaded
        } else if (defaultMe !in members) {
            members = listOf(defaultMe) + members
        }
    }

    private fun persistFriends(context: Context, defaultMe: String) {
        val friends = members.filter { it != defaultMe }
        val arr = JSONArray().apply { friends.forEach { put(it) } }
        context.getSharedPreferences(getPrefKey(context), Context.MODE_PRIVATE)
            .edit()
            .putString("saved_friends", arr.toString())
            .apply()
    }

    fun addMember(context: Context, name: String, defaultMe: String) {
        val clean = name.trim()
        if (clean.isNotBlank() && clean !in members) {
            members = members + clean
            persistFriends(context, defaultMe)
        }
    }

    fun removeMember(context: Context, name: String, defaultMe: String) {
        if (name != defaultMe) {
            members = members.filter { it != name }
            persistFriends(context, defaultMe)
        }
    }

    fun enqueueReceiptAnalysis(context: Context, uri: Uri) {
        val appCtx = context.applicationContext
        val jobId = UUID.randomUUID().toString()
        val initialJob = ReceiptJobState(id = jobId, uri = uri, isAnalyzing = true)
        receiptJobs = receiptJobs + initialJob
        launchOcrForJob(appCtx, jobId, uri)
    }

    fun retryJob(context: Context, jobId: String) {
        val appCtx = context.applicationContext
        val target = receiptJobs.firstOrNull { it.id == jobId } ?: return
        val targetUri = target.uri ?: return
        activeTasks[jobId]?.cancel()
        receiptJobs = receiptJobs.map { j ->
            if (j.id == jobId) j.copy(isAnalyzing = true, errorMsg = null) else j
        }
        launchOcrForJob(appCtx, jobId, targetUri)
    }

    private fun launchOcrForJob(appCtx: Context, jobId: String, uri: Uri) {
        activeTasks[jobId]?.cancel()
        val task = ocrScope.launch {
            val res = ReceiptOcrParser.parseImageUriAsync(appCtx, uri)
            res.onSuccess { (bmp, parsed: ParsedReceiptData) ->
                val defaultAssignees = members.toSet()
                val splitItems = parsed.items.map { item ->
                    SplitItemState(
                        id = "${jobId}_${item.id}",
                        name = item.name,
                        price = item.price,
                        assignedMembers = defaultAssignees
                    )
                }
                receiptJobs = receiptJobs.map { j ->
                    if (j.id == jobId) {
                        j.copy(
                            isAnalyzing = false,
                            thumbnail = bmp,
                            merchant = parsed.merchant,
                            items = splitItems,
                            serviceCharge = parsed.detectedServiceCharge,
                            sst = parsed.detectedSst,
                            adjustment = parsed.detectedAdjustment,
                            detectedTotal = parsed.detectedTotal,
                            rawLines = parsed.rawLines,
                            errorMsg = null
                        )
                    } else j
                }
            }.onFailure { err ->
                receiptJobs = receiptJobs.map { j ->
                    if (j.id == jobId) {
                        j.copy(isAnalyzing = false, errorMsg = err.message ?: "Scan failed")
                    } else j
                }
            }
            activeTasks.remove(jobId)
        }
        activeTasks[jobId] = task
    }

    fun removeJob(jobId: String) {
        activeTasks.remove(jobId)?.cancel()
        receiptJobs = receiptJobs.filter { it.id != jobId }
        if (activeJobId == jobId) activeJobId = null
    }

    fun clearReceipts() {
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
        receiptJobs = emptyList()
        activeJobId = null
    }

    fun updateJobItems(jobId: String, updater: (List<SplitItemState>) -> List<SplitItemState>) {
        receiptJobs = receiptJobs.map { j ->
            if (j.id == jobId) j.copy(items = updater(j.items)) else j
        }
    }

    fun splitMultiQuantityItemInJob(jobId: String, itemId: String) {
        updateJobItems(jobId) { list ->
            list.flatMap { item ->
                if (item.id == itemId) SplitBillLogic.splitMultiQuantityItem(item) else listOf(item)
            }
        }
    }

    fun updateJobCharges(jobId: String, serviceCharge: Double, sst: Double, adjustment: Double) {
        receiptJobs = receiptJobs.map { j ->
            if (j.id == jobId) {
                j.copy(
                    serviceCharge = round(serviceCharge * 100.0) / 100.0,
                    sst = round(sst * 100.0) / 100.0,
                    adjustment = round(adjustment * 100.0) / 100.0
                )
            } else j
        }
    }
}
