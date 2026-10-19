package com.siangloh.ledger.ui.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.siangloh.ledger.R
import com.siangloh.ledger.data.CashflowRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.rows(key: String) = optJSONArray(key)?.objects() ?: emptyList()
private fun amount(data: JSONObject, key: String) = data.optString("currency") + " " + DecimalFormat("#,##0.00").format(data.optDouble(key, 0.0))
private fun ratio(data: JSONObject, key: String) = if (data.isNull(key)) "—" else DecimalFormat("0.0").format(data.optDouble(key)) + "%"
private fun reportTime(raw: String, locale: Locale): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    val time = parser.parse(raw.take(19))
    if (time == null) "—" else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, locale).format(time)
} catch (error: java.text.ParseException) {
    android.util.Log.w("Cashflow", "Invalid report timestamp", error)
    "—"
}

@Composable
private fun CashflowFigures(data: JSONObject, compact: Boolean = false) {
    val fields = listOf("income" to R.string.cf_income, "expense" to R.string.cf_expense,
        "net" to R.string.cf_net, "reserved" to R.string.cf_reserved, "available" to R.string.cf_available)
    if (compact) {
        fields.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (key, label) -> Column(Modifier.weight(1f)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                    Text(amount(data, key), style = MaterialTheme.typography.titleMedium)
                } }
            }
            Spacer(Modifier.height(8.dp))
        }
    } else fields.forEach { (key, label) ->
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Text(amount(data, key), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
    }
    Text(stringResource(R.string.cf_usage), style = MaterialTheme.typography.titleMedium)
    if (data.optDouble("income") > 0) {
        // Actual values/ratios stay visible even when the visual indicator is capped.
        val spent = data.optDouble("expense").coerceAtLeast(0.0)
        val reserved = data.optDouble("reserved").coerceAtLeast(0.0)
        val available = data.optDouble("available").coerceAtLeast(0.0)
        Row(Modifier.fillMaxWidth().height(16.dp)) {
            listOf(spent to Color(0xFF0F766E), reserved to Color(0xFFD97706), available to Color(0xFF94A3B8)).forEach { (value, color) ->
                if (value > 0) Box(Modifier.weight(value.toFloat()).fillMaxHeight().background(color))
            }
        }
        Text(stringResource(R.string.cf_expense) + ": " + ratio(data, "expense_percent"))
        Text(stringResource(R.string.cf_reserved) + ": " + ratio(data, "reserve_percent"))
        Text(stringResource(R.string.cf_available) + ": " + ratio(data, "available_percent"))
    } else Text(stringResource(R.string.cf_no_ratio))
    if (data.optDouble("deficit") > 0) Text(stringResource(R.string.cf_deficit) + ": " + amount(data, "deficit"), color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.cf_fixed), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.cf_planned) + ": " + amount(data, "planned"))
    Text(stringResource(R.string.cf_paid) + ": " + amount(data, "paid") + " · " + ratio(data, "payment_percent"))
    Text(stringResource(R.string.cf_pending) + ": " + amount(data, "reserved"))
    LinearProgressIndicator(progress = { (data.optDouble("payment_percent", 0.0) / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.cf_disclaimer), style = MaterialTheme.typography.bodySmall)
    if (data.optBoolean("schedule_estimated")) Text(stringResource(R.string.cf_estimate), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.cf_updated) + ": " + reportTime(data.optString("updated_at"), LocalContext.current.resources.configuration.locales[0]), style = MaterialTheme.typography.bodySmall)
}

@Composable
fun CashflowSummaryCard(month: String, revision: Int = 0, onOpen: () -> Unit) {
    val context = LocalContext.current
    var data by remember(month) { mutableStateOf<CashflowRepository.Result?>(null) }
    var failed by remember(month) { mutableStateOf(false) }
    LaunchedEffect(month, revision) {
        data = null
        failed = false
        try { data = CashflowRepository.load(context, month) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { android.util.Log.w("Cashflow", "Summary unavailable", error); failed = true }
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.cf_title) + " · " + month, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onOpen, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) { Text(stringResource(R.string.cf_open)) }
            data?.let { result ->
                if (result.cached) Text(stringResource(R.string.cf_cached))
                CashflowFigures(result.data, compact = true)
            } ?: Text(stringResource(if (failed) R.string.cf_error else R.string.cf_loading))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CashflowAnalysisScreen(initialMonth: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var month by rememberSaveable { mutableStateOf(initialMonth) }
    var currency by rememberSaveable { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<CashflowRepository.Result?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var selectedIds by remember { mutableStateOf<List<Int>?>(null) }
    var selectedBill by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var correctedAmount by remember { mutableStateOf("") }
    LaunchedEffect(month, currency, refresh) {
        loading = true; failed = false; result = null; selectedIds = null
        try { result = CashflowRepository.load(context, month, currency) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { android.util.Log.w("Cashflow", "Analysis unavailable", error); failed = true }
        finally { loading = false }
    }
    fun changeMonth(delta: Int) {
        val format = SimpleDateFormat("yyyy-MM", Locale.US)
        val calendar = Calendar.getInstance().apply { time = format.parse(month)!!; add(Calendar.MONTH, delta) }
        month = format.format(calendar.time)
    }
    fun update(payload: JSONObject) {
        scope.launch {
            busy = true
            try { CashflowRepository.update(context, payload.put("month", month)); selectedBill = null; refresh++ }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { android.util.Log.w("Cashflow", "Payment update rejected", error); failed = true }
            finally { busy = false }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onClick = onBack) { Text("← " + stringResource(R.string.cf_back)) }
                Text(stringResource(R.string.cf_title), style = MaterialTheme.typography.headlineSmall)
                FlowRow {
                    TextButton(onClick = { changeMonth(-1) }) { Text("← " + stringResource(R.string.prev_month)) }
                    Text(month, Modifier.padding(12.dp))
                    TextButton(onClick = { changeMonth(1) }) { Text(stringResource(R.string.next_month) + " →") }
                }
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text(stringResource(R.string.cf_refresh)) }
                if (loading) Text(stringResource(R.string.cf_loading))
                if (failed) Text(stringResource(R.string.cf_error), color = MaterialTheme.colorScheme.error)
            }
            result?.let { report ->
                val data = report.data
                item {
                    if (report.cached) Text(stringResource(R.string.cf_cached))
                    FlowRow { data.optJSONArray("currencies")?.let { codes ->
                        for (index in 0 until codes.length()) TextButton(onClick = { currency = codes.getString(index) }) { Text(codes.getString(index)) }
                    } }
                    CashflowFigures(data)
                }
                for ((key, title) in listOf("income_platforms" to R.string.cf_income_platforms,
                        "expense_categories" to R.string.cf_expense_categories, "expense_platforms" to R.string.cf_expense_platforms)) {
                    item { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
                    if (data.rows(key).isEmpty()) item { Text(stringResource(R.string.cf_empty)) }
                    data.rows(key).forEach { group -> item {
                        Card(Modifier.fillMaxWidth().clickable {
                            selectedIds = group.optJSONArray("transaction_ids")?.let { ids -> (0 until ids.length()).map { ids.getInt(it) } }
                        }) { Column(Modifier.padding(12.dp)) {
                            val label = group.optString("label").takeUnless { it.isBlank() || it == "null" }
                            Text(if (key == "expense_categories" && label != null) CategoryUiHelper.getLocalizedCategoryName(context, label) else label ?: stringResource(R.string.cf_unknown))
                            Text(amount(JSONObject().put("currency", data.optString("currency")).put("amount", group.optDouble("amount")), "amount"))
                            Text(stringResource(R.string.cf_total_percent) + " " + ratio(group, "total_percent"))
                            Text(stringResource(R.string.cf_income_percent) + " " + ratio(group, "income_percent"))
                        } }
                    } }
                }
                for ((key, title) in listOf("bills" to R.string.cf_fixed, "prior_overdue" to R.string.cf_prior)) {
                    item { Text(stringResource(title), style = MaterialTheme.typography.titleLarge) }
                    data.rows(key).forEach { bill -> item {
                        Card(Modifier.fillMaxWidth().clickable(enabled = !busy && !report.cached) { selectedBill = bill; correctedAmount = bill.optString("amount") }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(bill.optString("title") + " · " + bill.optString("due_date"))
                                Text(stringResource(R.string.cf_paid) + ": " + data.optString("currency") + " " + bill.optString("paid"))
                                Text(stringResource(R.string.cf_pending) + ": " + data.optString("currency") + " " + bill.optString("pending"))
                            }
                        }
                    } }
                }
                if (data.rows("inactive_bills").isNotEmpty()) {
                    item { Text(stringResource(R.string.cf_inactive)) }
                    data.rows("inactive_bills").forEach { bill -> item {
                        Text(bill.optString("title") + " · " + bill.optString("due_date"))
                        TextButton(enabled = !busy && !report.cached, onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key"))) }) { Text(stringResource(R.string.cf_restore)) }
                    } }
                }
                if (data.rows("scheduled_transactions").isNotEmpty()) {
                    item { Text(stringResource(R.string.cf_scheduled)) }
                    data.rows("scheduled_transactions").forEach { tx -> item {
                        Text("#${tx.optInt("id")} · ${tx.optString("date")} · ${tx.optString("note")} · ${tx.optString("amount")}")
                        TextButton(enabled = !busy && !report.cached, onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id"))) }) { Text(stringResource(R.string.cf_confirm)) }
                    } }
                }
                data.rows("confirmed_transactions").forEach { tx -> item {
                    Text("#${tx.optInt("id")} · ${tx.optString("note")}")
                    TextButton(enabled = !busy && !report.cached, onClick = { update(JSONObject().put("action", "confirm").put("transaction_id", tx.optInt("id")).put("undo", true)) }) { Text(stringResource(R.string.cf_undo_confirm)) }
                } }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    selectedIds?.let { ids ->
        AlertDialog(onDismissRequest = { selectedIds = null }, title = { Text(stringResource(R.string.cf_transactions)) },
            text = { LazyColumn { result?.data?.rows("transactions")?.filter { it.optInt("id") in ids }?.forEach { tx -> item {
                Text("#${tx.optInt("id")} · ${tx.optString("date")}\n${tx.optString("note")} · ${tx.optString("amount")}", Modifier.padding(vertical = 8.dp))
            } } } }, confirmButton = { TextButton(onClick = { selectedIds = null }) { Text(stringResource(R.string.cf_back)) } })
    }
    selectedBill?.let { bill ->
        AlertDialog(onDismissRequest = { if (!busy) selectedBill = null }, title = { Text(bill.optString("title")) },
            text = { LazyColumn {
                if (failed) item { Text(stringResource(R.string.cf_error), color = MaterialTheme.colorScheme.error) }
                item { Text(stringResource(R.string.cf_link)) }
                ((result?.data?.rows("transactions") ?: emptyList()) + (result?.data?.rows("scheduled_transactions") ?: emptyList())).filter { it.optString("type") == "expense" } .forEach { tx -> item {
                    TextButton(enabled = !busy, onClick = { update(JSONObject().put("bill_key", bill.optString("bill_key")).put("transaction_id", tx.optInt("id"))) }) {
                        Text("#${tx.optInt("id")} · ${tx.optString("date")} · ${tx.optString("note")} · ${tx.optString("amount")}")
                    }
                } }
                result?.data?.rows("payment_links")?.filter { it.optString("bill_key") == bill.optString("bill_key") }?.forEach { link -> item {
                    TextButton(enabled = !busy, onClick = { update(JSONObject().put("bill_key", bill.optString("bill_key")).put("transaction_id", link.optInt("transaction_id")).put("unlink", true)) }) { Text(stringResource(R.string.cf_unlink) + " #" + link.optInt("transaction_id")) }
                } }
                item { Text(stringResource(R.string.cf_refund)) }
                result?.data?.rows("transactions")?.filter { it.optString("type") == "income" && it.optDouble("amount") > 0 }?.forEach { tx -> item {
                    TextButton(enabled = !busy, onClick = { update(JSONObject().put("bill_key", bill.optString("bill_key")).put("transaction_id", tx.optInt("id")).put("refund", true)) }) {
                        Text("#${tx.optInt("id")} · ${tx.optString("note")} · ${tx.optString("amount")}")
                    }
                } }
                item {
                    OutlinedTextField(value = correctedAmount, onValueChange = { correctedAmount = it }, label = { Text(stringResource(R.string.cf_edit_amount)) }, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !busy && correctedAmount.toDoubleOrNull()?.let { it >= 0 && it.isFinite() } == true,
                        onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key")).put("amount", correctedAmount)) }) { Text(stringResource(R.string.cf_edit_amount)) }
                    Text(stringResource(R.string.cf_duplicate))
                }
                result?.data?.rows("bills")?.filter { it.optString("bill_key") != bill.optString("bill_key") }?.forEach { other -> item {
                    TextButton(enabled = !busy, onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key")).put("duplicate_of", other.optString("bill_key"))) }) { Text(other.optString("title") + " · " + other.optString("due_date")) }
                } }
            } }, confirmButton = { TextButton(enabled = !busy, onClick = { selectedBill = null }) { Text(stringResource(R.string.cf_back)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { update(JSONObject().put("action", "bill").put("bill_key", bill.optString("bill_key")).put("cancelled", true)) }) { Text(stringResource(R.string.cf_cancel)) } })
    }
}
