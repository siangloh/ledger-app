package com.siangloh.ledger.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.siangloh.ledger.R
import com.siangloh.ledger.parser.ParsedReceiptData
import com.siangloh.ledger.parser.ReceiptOcrParser
import com.siangloh.ledger.ui.compose.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
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
    val uri: Uri,
    val isAnalyzing: Boolean = true,
    val thumbnail: Bitmap? = null,
    val merchant: String = "Receipt",
    val items: List<SplitItemState> = emptyList(),
    val serviceCharge: Double = 0.0,
    val sst: Double = 0.0,
    val detectedTotal: Double = 0.0,
    val errorMsg: String? = null
) {
    val itemsSubtotal: Double
        get() = round(items.sumOf { it.price } * 100.0) / 100.0

    val computedTotal: Double
        get() = round((itemsSubtotal + serviceCharge + sst) * 100.0) / 100.0

    val tallyDiff: Double
        get() = if (detectedTotal > 0.0) round((computedTotal - detectedTotal) * 100.0) / 100.0 else 0.0

    val isTallied: Boolean
        get() = detectedTotal <= 0.0 || abs(tallyDiff) <= 0.05
}

@Composable
fun SplitBillTabContent(
    onRecordTotalPaid: (amount: Double, note: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val defaultMe = stringResource(R.string.split_me_default)

    var members by remember { mutableStateOf(listOf(defaultMe)) }
    var newMemberText by remember { mutableStateOf("") }
    var receiptJobs by remember { mutableStateOf<List<ReceiptJobState>>(emptyList()) }
    var activeJobId by remember { mutableStateOf<String?>(null) }
    var showAddItemDialogForJob by remember { mutableStateOf<String?>(null) }
    var newItemName by remember { mutableStateOf("") }
    var newItemPrice by remember { mutableStateOf("") }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun enqueueReceiptAnalysis(uri: Uri) {
        val jobId = UUID.randomUUID().toString()
        val initialJob = ReceiptJobState(id = jobId, uri = uri, isAnalyzing = true)
        receiptJobs = receiptJobs + initialJob
        scope.launch {
            val res = ReceiptOcrParser.parseImageUriAsync(context, uri)
            res.onSuccess { (bmp, parsed: ParsedReceiptData) ->
                val splitItems = parsed.items.map { item ->
                    SplitItemState(
                        id = "${jobId}_${item.id}",
                        name = item.name,
                        price = item.price,
                        assignedMembers = members.toSet()
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
                            detectedTotal = parsed.detectedTotal
                        )
                    } else j
                }
            }.onFailure { err ->
                receiptJobs = receiptJobs.map { j ->
                    if (j.id == jobId) {
                        j.copy(isAnalyzing = false, errorMsg = err.message ?: "OCR failed")
                    } else j
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            enqueueReceiptAnalysis(uri)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri -> enqueueReceiptAnalysis(uri) }
    }

    // Calculate per-member totals across all completed receipts
    val memberTotals = remember(receiptJobs, members) {
        val map = linkedMapOf<String, Double>()
        members.forEach { map[it] = 0.0 }
        receiptJobs.filter { !it.isAnalyzing }.forEach { job ->
            val jobItemsSub = job.itemsSubtotal
            val jobTax = job.serviceCharge + job.sst
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
                (assignedSubTotal + jobTax * (assignedSubTotal / jobItemsSub)) / assignedSubTotal
            } else 1.0
            jobPersonSub.forEach { (m, sub) ->
                map[m] = (map[m] ?: 0.0) + sub * taxFactor
            }
        }
        map.mapValues { (_, v) -> round(v * 100.0) / 100.0 }
    }

    val grandTotalAll = remember(receiptJobs) {
        round(receiptJobs.filter { !it.isAnalyzing }.sumOf {
            if (it.detectedTotal > 0.0) it.detectedTotal else it.computedTotal
        } * 100.0) / 100.0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Camera & Multi-Photo Upload Actions (non-blocking background queue)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    val photoFile = File(context.cacheDir, "receipt_${System.currentTimeMillis()}.jpg")
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
                    pendingCameraUri = uri
                    cameraLauncher.launch(uri)
                },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.split_take_photo), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, PrimaryTeal),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryTeal),
                modifier = Modifier.weight(1f).height(46.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.split_pick_photos), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 2. Background Receipt Queue (thumbnails with progress spinner / tally badge)
        if (receiptJobs.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.split_empty_hint),
                    fontSize = 12.5.sp,
                    color = Slate500,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(receiptJobs, key = { it.id }) { job ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White,
                        border = BorderStroke(
                            width = if (activeJobId == job.id) 2.dp else 1.dp,
                            color = if (activeJobId == job.id) PrimaryTeal else Slate200
                        ),
                        modifier = Modifier
                            .width(156.dp)
                            .clickable(enabled = !job.isAnalyzing) { activeJobId = job.id }
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(78.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Slate100),
                                contentAlignment = Alignment.Center
                            ) {
                                if (job.thumbnail != null) {
                                    Image(
                                        bitmap = job.thumbnail.asImageBitmap(),
                                        contentDescription = job.merchant,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                if (job.isAnalyzing) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.35f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 2.5.dp,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }

                            Text(
                                text = if (job.isAnalyzing) stringResource(R.string.split_analyzing) else job.merchant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (!job.isAnalyzing) {
                                val badgeBg = when {
                                    job.errorMsg != null -> Color(0xFFFEE2E2)
                                    job.isTallied -> Color(0xFFD1FAE5)
                                    else -> Color(0xFFFEF3C7)
                                }
                                val badgeFg = when {
                                    job.errorMsg != null -> Color(0xFFDC2626)
                                    job.isTallied -> Color(0xFF059669)
                                    else -> Color(0xFFD97706)
                                }
                                val badgeText = when {
                                    job.errorMsg != null -> "Error"
                                    job.isTallied -> stringResource(
                                        R.string.split_tally_ok,
                                        "RM ${String.format(Locale.US, "%.2f", job.computedTotal)}"
                                    )
                                    else -> stringResource(
                                        R.string.split_tally_diff,
                                        "RM ${String.format(Locale.US, "%.2f", abs(job.tallyDiff))}"
                                    )
                                }
                                Surface(shape = RoundedCornerShape(6.dp), color = badgeBg) {
                                    Text(
                                        text = badgeText,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeFg,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Members Management Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.White,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.split_members_title), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate800)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    members.forEach { m ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Teal50,
                            border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.35f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(m, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PrimaryTeal)
                                if (m != defaultMe) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = PrimaryTeal,
                                        modifier = Modifier.size(13.dp).clickable {
                                            members = members.filter { it != m }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newMemberText,
                        onValueChange = { newMemberText = it },
                        placeholder = { Text(stringResource(R.string.split_add_member_hint), fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryTeal,
                            unfocusedBorderColor = Slate200
                        )
                    )
                    Button(
                        onClick = {
                            val name = newMemberText.trim()
                            if (name.isNotBlank() && name !in members) {
                                members = members + name
                                newMemberText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 4. Settlement Summary Card
        if (receiptJobs.any { !it.isAnalyzing && it.items.isNotEmpty() }) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.split_settlement_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        Text(
                            text = "RM ${String.format(Locale.US, "%.2f", grandTotalAll)}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = PrimaryTeal
                        )
                    }
                    memberTotals.forEach { (member, total) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(member, fontSize = 13.sp, color = Slate700, fontWeight = FontWeight.Medium)
                            Text("RM ${String.format(Locale.US, "%.2f", total)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val sb = StringBuilder("🧾 AA Split Summary (Total RM ${String.format(Locale.US, "%.2f", grandTotalAll)})\n")
                                memberTotals.forEach { (m, amt) ->
                                    sb.append("• $m: RM ${String.format(Locale.US, "%.2f", amt)}\n")
                                }
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("AA Split", sb.toString().trim()))
                                Toast.makeText(context, context.getString(R.string.split_copied_toast), Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, PrimaryTeal),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryTeal),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.split_copy_whatsapp), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                val merchants = receiptJobs.filter { !it.isAnalyzing }.joinToString(", ") { it.merchant }.ifBlank { "AA Split" }
                                onRecordTotalPaid(grandTotalAll, merchants)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.split_record_expense), fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 5. Receipt Detail & Per-Dish Assignment Dialog (when user clicks any analyzed thumbnail)
    val activeJob = receiptJobs.firstOrNull { it.id == activeJobId }
    if (activeJob != null) {
        AlertDialog(
            onDismissRequest = { activeJobId = null },
            containerColor = Color.White,
            title = {
                Column {
                    Text(activeJob.merchant, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
                    Text(
                        text = if (activeJob.isTallied) {
                            stringResource(R.string.split_tally_ok, "RM ${String.format(Locale.US, "%.2f", activeJob.computedTotal)}")
                        } else {
                            stringResource(R.string.split_tally_diff, "RM ${String.format(Locale.US, "%.2f", abs(activeJob.tallyDiff))}")
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (activeJob.isTallied) Color(0xFF059669) else Color(0xFFD97706)
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (activeJob.thumbnail != null) {
                        Image(
                            bitmap = activeJob.thumbnail.asImageBitmap(),
                            contentDescription = activeJob.merchant,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                                .clip(RoundedCornerShape(10.dp))
                        )
                    }

                    if (activeJob.serviceCharge > 0 || activeJob.sst > 0) {
                        Text(
                            text = stringResource(R.string.split_tax_summary_fmt, activeJob.serviceCharge, activeJob.sst),
                            fontSize = 11.5.sp,
                            color = Slate600,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    activeJob.items.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Slate900, modifier = Modifier.weight(1f))
                                    Text("RM ${String.format(Locale.US, "%.2f", item.price)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Slate900)
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = Slate400,
                                        modifier = Modifier.size(16.dp).clickable {
                                            receiptJobs = receiptJobs.map { j ->
                                                if (j.id == activeJob.id) j.copy(items = j.items.filter { it.id != item.id }) else j
                                            }
                                        }
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val allSelected = members.all { it in item.assignedMembers }
                                    val chipColors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Teal50,
                                        selectedLabelColor = PrimaryTeal,
                                        containerColor = Color.White,
                                        labelColor = Slate700
                                    )
                                    FilterChip(
                                        selected = allSelected,
                                        onClick = {
                                            val next = if (allSelected) emptySet() else members.toSet()
                                            receiptJobs = receiptJobs.map { j ->
                                                if (j.id == activeJob.id) {
                                                    j.copy(items = j.items.map { it2 -> if (it2.id == item.id) it2.copy(assignedMembers = next) else it2 })
                                                } else j
                                            }
                                        },
                                        colors = chipColors,
                                        label = { Text(stringResource(R.string.split_all_btn), fontSize = 11.sp) }
                                    )
                                    members.forEach { m ->
                                        val isAssigned = m in item.assignedMembers
                                        FilterChip(
                                            selected = isAssigned,
                                            onClick = {
                                                val next = if (isAssigned) item.assignedMembers - m else item.assignedMembers + m
                                                receiptJobs = receiptJobs.map { j ->
                                                    if (j.id == activeJob.id) {
                                                        j.copy(items = j.items.map { it2 -> if (it2.id == item.id) it2.copy(assignedMembers = next) else it2 })
                                                    } else j
                                                }
                                            },
                                            colors = chipColors,
                                            label = { Text(m, fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    TextButton(onClick = {
                        newItemName = ""
                        newItemPrice = ""
                        showAddItemDialogForJob = activeJob.id
                    }) {
                        Text(stringResource(R.string.split_add_item_btn), color = PrimaryTeal, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeJobId = null }) {
                    Text(stringResource(R.string.common_confirm), color = PrimaryTeal, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showAddItemDialogForJob != null) {
        AlertDialog(
            onDismissRequest = { showAddItemDialogForJob = null },
            containerColor = Color.White,
            title = { Text(stringResource(R.string.split_add_item_btn), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text(stringResource(R.string.split_item_name_hint)) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = newItemPrice,
                        onValueChange = { newItemPrice = it },
                        label = { Text(stringResource(R.string.split_item_price_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val p = newItemPrice.toDoubleOrNull() ?: 0.0
                    val targetId = showAddItemDialogForJob
                    if (newItemName.isNotBlank() && p > 0.0 && targetId != null) {
                        receiptJobs = receiptJobs.map { j ->
                            if (j.id == targetId) {
                                j.copy(
                                    items = j.items + SplitItemState(
                                        id = UUID.randomUUID().toString(),
                                        name = newItemName.trim(),
                                        price = round(p * 100.0) / 100.0,
                                        assignedMembers = members.toSet()
                                    )
                                )
                            } else j
                        }
                    }
                    showAddItemDialogForJob = null
                }) {
                    Text(stringResource(R.string.common_confirm), color = PrimaryTeal, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddItemDialogForJob = null }) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}

