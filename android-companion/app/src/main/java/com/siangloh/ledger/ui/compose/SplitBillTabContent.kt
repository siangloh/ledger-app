package com.siangloh.ledger.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.siangloh.ledger.R
import com.siangloh.ledger.ui.compose.theme.*
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.round

@Composable
fun SplitBillTabContent(
    onRecordTotalPaid: (amount: Double, note: String) -> Unit
) {
    val context = LocalContext.current
    val defaultMe = stringResource(R.string.split_me_default)

    LaunchedEffect(defaultMe) {
        SplitBillDraftHolder.ensureInitialized(context, defaultMe)
    }

    val members = SplitBillDraftHolder.members
    val receiptJobs = SplitBillDraftHolder.receiptJobs
    val activeJobId = SplitBillDraftHolder.activeJobId

    var newMemberText by remember { mutableStateOf("") }
    var showAddItemDialogForJob by remember { mutableStateOf<String?>(null) }
    var editingItemTarget by remember { mutableStateOf<Pair<String, SplitItemState>?>(null) }
    var editingChargesJob by remember { mutableStateOf<ReceiptJobState?>(null) }
    var zoomBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showRecordChoiceDialog by remember { mutableStateOf(false) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = SplitBillDraftHolder.pendingCameraUri
        SplitBillDraftHolder.pendingCameraUri = null
        if (ok && uri != null) {
            SplitBillDraftHolder.enqueueReceiptAnalysis(context, uri)
        }
    }

    fun launchCameraCapture() {
        try {
            val photosDir = File(context.cacheDir, "receipt_photos").apply { mkdirs() }
            val photoFile = File(photosDir, "receipt_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
            SplitBillDraftHolder.pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Log.w("SplitBillTabContent", "Failed to launch camera capture", e)
            Toast.makeText(context, context.getString(R.string.split_camera_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { uri -> SplitBillDraftHolder.enqueueReceiptAnalysis(context, uri) }
    }

    val memberTotals = remember(receiptJobs, members) {
        SplitBillLogic.computeMemberTotals(receiptJobs, members)
    }

    val grandTotalAll = remember(receiptJobs) {
        round(receiptJobs.filter { !it.isAnalyzing }.sumOf {
            if (it.items.isNotEmpty()) it.computedTotal else it.detectedTotal
        } * 100.0) / 100.0
    }
    val myShare = memberTotals[defaultMe] ?: 0.0
    val merchantSummary = remember(receiptJobs) {
        receiptJobs.filter { !it.isAnalyzing && it.merchant.isNotBlank() }
            .joinToString(", ") { it.merchant }
            .ifBlank { "AA Split" }
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
                onClick = { launchCameraCapture() },
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

        // 2. Background Receipt Queue (thumbnails with remove badge, spinner, tally badge)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = stringResource(R.string.split_clear_all),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate500,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { SplitBillDraftHolder.clearReceipts() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(receiptJobs, key = { it.id }) { job ->
                    ReceiptThumbnailCard(
                        job = job,
                        isSelected = activeJobId == job.id,
                        onSelectOrRetry = {
                            if (!job.isAnalyzing) {
                                if (job.errorMsg != null) {
                                    SplitBillDraftHolder.retryJob(context, job.id)
                                } else {
                                    SplitBillDraftHolder.activeJobId = job.id
                                }
                            }
                        },
                        onRemove = { SplitBillDraftHolder.removeJob(job.id) }
                    )
                }
            }
        }

        // 3. Members Management Card (saved automatically for future bills)
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
                                            SplitBillDraftHolder.removeMember(context, m, defaultMe)
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
                            if (newMemberText.isNotBlank()) {
                                SplitBillDraftHolder.addMember(context, newMemberText, defaultMe)
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
                        Column {
                            Text(stringResource(R.string.split_settlement_title), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            if (members.size > 1 && myShare > 0.0) {
                                Text(
                                    text = stringResource(R.string.split_my_share_fmt, myShare),
                                    fontSize = 11.5.sp,
                                    color = Slate500,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
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
                                if (members.size > 1 && myShare > 0.0 && abs(myShare - grandTotalAll) > 0.01) {
                                    showRecordChoiceDialog = true
                                } else {
                                    onRecordTotalPaid(grandTotalAll, merchantSummary)
                                }
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

    // 5. Receipt Detail & Per-Dish Assignment Dialog
    val activeJob = receiptJobs.firstOrNull { it.id == activeJobId }
    if (activeJob != null) {
        ReceiptDetailDialog(
            job = activeJob,
            members = members,
            onDismiss = { SplitBillDraftHolder.activeJobId = null },
            onZoomImage = { zoomBitmap = it },
            onEditCharges = { editingChargesJob = activeJob },
            onEditItem = { item -> editingItemTarget = activeJob.id to item },
            onDeleteItem = { itemId ->
                SplitBillDraftHolder.updateJobItems(activeJob.id) { list -> list.filter { it.id != itemId } }
            },
            onSplitQuantityItem = { itemId ->
                SplitBillDraftHolder.splitMultiQuantityItemInJob(activeJob.id, itemId)
            },
            onUpdateAssignees = { itemId, nextAssignees ->
                SplitBillDraftHolder.updateJobItems(activeJob.id) { list ->
                    list.map { if (it.id == itemId) it.copy(assignedMembers = nextAssignees) else it }
                }
            },
            onRequestAddItem = { showAddItemDialogForJob = activeJob.id }
        )
    }

    // Full-screen pinch-to-zoom receipt viewer
    zoomBitmap?.let { bmp ->
        ReceiptZoomDialog(bitmap = bmp, onDismiss = { zoomBitmap = null })
    }

    // Edit Service Charge / SST / Rounding Adjustment
    editingChargesJob?.let { job ->
        EditChargesDialog(
            job = job,
            onDismiss = { editingChargesJob = null },
            onConfirm = { svc, sst, adj ->
                SplitBillDraftHolder.updateJobCharges(job.id, svc, sst, adj)
                editingChargesJob = null
            }
        )
    }

    // Edit Existing Item Dialog
    editingItemTarget?.let { (targetJobId, targetItem) ->
        ReceiptItemFormDialog(
            title = stringResource(R.string.split_edit_item_btn),
            initialName = targetItem.name,
            initialPrice = String.format(Locale.US, "%.2f", targetItem.price),
            onDismiss = { editingItemTarget = null },
            onConfirm = { name, price ->
                SplitBillDraftHolder.updateJobItems(targetJobId) { list ->
                    list.map { if (it.id == targetItem.id) it.copy(name = name, price = price) else it }
                }
                editingItemTarget = null
            }
        )
    }

    // Add Missing Item Dialog
    showAddItemDialogForJob?.let { targetId ->
        ReceiptItemFormDialog(
            title = stringResource(R.string.split_add_item_btn),
            initialName = "",
            initialPrice = "",
            onDismiss = { showAddItemDialogForJob = null },
            onConfirm = { name, price ->
                SplitBillDraftHolder.updateJobItems(targetId) { list ->
                    list + SplitItemState(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        price = price,
                        assignedMembers = members.toSet()
                    )
                }
                showAddItemDialogForJob = null
            }
        )
    }

    // Choice between Recording My Own Share vs Full Advance Paid (integrates with Rebate workflow)
    if (showRecordChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showRecordChoiceDialog = false },
            containerColor = Color.White,
            title = {
                Text(stringResource(R.string.split_record_choice_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Teal50,
                        border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth().clickable {
                            showRecordChoiceDialog = false
                            onRecordTotalPaid(myShare, "$merchantSummary (AA)")
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.split_record_my_share_fmt, myShare), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = PrimaryTeal)
                            Text(stringResource(R.string.split_record_my_share_desc), fontSize = 11.5.sp, color = Slate600, lineHeight = 16.sp)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth().clickable {
                            showRecordChoiceDialog = false
                            onRecordTotalPaid(grandTotalAll, "$merchantSummary (AA垫付 · 待Rebate)")
                        }
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.split_record_advance_fmt, grandTotalAll), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            Text(stringResource(R.string.split_record_advance_desc), fontSize = 11.5.sp, color = Slate600, lineHeight = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecordChoiceDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = Slate600)
                }
            }
        )
    }
}

@Composable
private fun ReceiptThumbnailCard(
    job: ReceiptJobState,
    isSelected: Boolean,
    onSelectOrRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) PrimaryTeal else Slate200
        ),
        modifier = Modifier
            .width(156.dp)
            .clickable(enabled = !job.isAnalyzing, onClick = onSelectOrRetry)
    ) {
        Box {
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
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
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
                        job.errorMsg != null -> "Error · Retry"
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

            // Corner remove button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun ReceiptDetailDialog(
    job: ReceiptJobState,
    members: List<String>,
    onDismiss: () -> Unit,
    onZoomImage: (Bitmap) -> Unit,
    onEditCharges: () -> Unit,
    onEditItem: (SplitItemState) -> Unit,
    onDeleteItem: (String) -> Unit,
    onSplitQuantityItem: (String) -> Unit,
    onUpdateAssignees: (String, Set<String>) -> Unit,
    onRequestAddItem: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = {
            Column {
                Text(job.merchant, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
                Text(
                    text = if (job.isTallied) {
                        stringResource(R.string.split_tally_ok, "RM ${String.format(Locale.US, "%.2f", job.computedTotal)}")
                    } else {
                        stringResource(R.string.split_tally_diff, "RM ${String.format(Locale.US, "%.2f", abs(job.tallyDiff))}")
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (job.isTallied) Color(0xFF059669) else Color(0xFFD97706)
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
                job.thumbnail?.let { bmp ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onZoomImage(bmp) }
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = job.merchant,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(stringResource(R.string.split_tap_zoom), fontSize = 10.sp, color = Color.White)
                            }
                        }
                    }
                }

                // Tappable Service Charge / SST / Adjustment summary row
                val taxPart = stringResource(R.string.split_tax_summary_fmt, job.serviceCharge, job.sst)
                val adjPart = if (job.adjustment != 0.0) stringResource(R.string.split_adj_summary_fmt, job.adjustment) else ""
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Teal50.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onEditCharges)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = taxPart + adjPart,
                            fontSize = 11.5.sp,
                            color = Slate700,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(15.dp))
                    }
                }

                job.items.forEach { item ->
                    val multiQty = SplitBillLogic.extractQuantityPrefix(item.name)?.first
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
                                if (multiQty != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Teal50,
                                        border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.4f)),
                                        modifier = Modifier.clickable { onSplitQuantityItem(item.id) }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.split_qty_btn, multiQty),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryTeal,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = PrimaryTeal,
                                    modifier = Modifier.size(16.dp).clickable { onEditItem(item) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = null,
                                    tint = Slate400,
                                    modifier = Modifier.size(16.dp).clickable { onDeleteItem(item.id) }
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
                                        onUpdateAssignees(item.id, if (allSelected) emptySet() else members.toSet())
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
                                            onUpdateAssignees(item.id, next)
                                        },
                                        colors = chipColors,
                                        label = { Text(m, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                }

                TextButton(onClick = onRequestAddItem) {
                    Text(stringResource(R.string.split_add_item_btn), color = PrimaryTeal, fontWeight = FontWeight.Bold)
                }

                if (job.rawLines.isNotEmpty()) {
                    var showRawLines by remember(job.id) { mutableStateOf(false) }
                    Text(
                        text = stringResource(R.string.split_raw_lines_toggle),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate500,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showRawLines = !showRawLines }
                            .padding(vertical = 4.dp)
                    )
                    if (showRawLines) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Slate100,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = job.rawLines.joinToString("\n"),
                                fontSize = 11.sp,
                                color = Slate700,
                                lineHeight = 16.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_confirm), color = PrimaryTeal, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun ReceiptZoomDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val nextScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = nextScale
                        if (nextScale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    )
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Icon(Icons.Default.Close, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@Composable
private fun EditChargesDialog(
    job: ReceiptJobState,
    onDismiss: () -> Unit,
    onConfirm: (serviceCharge: Double, sst: Double, adjustment: Double) -> Unit
) {
    var svcText by remember(job.id) { mutableStateOf(String.format(Locale.US, "%.2f", job.serviceCharge)) }
    var sstText by remember(job.id) { mutableStateOf(String.format(Locale.US, "%.2f", job.sst)) }
    var adjText by remember(job.id) { mutableStateOf(String.format(Locale.US, "%.2f", job.adjustment)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(stringResource(R.string.split_edit_charges_btn), fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = svcText,
                    onValueChange = { svcText = it },
                    label = { Text(stringResource(R.string.split_svc_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = sstText,
                    onValueChange = { sstText = it },
                    label = { Text(stringResource(R.string.split_sst_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                OutlinedTextField(
                    value = adjText,
                    onValueChange = { adjText = it },
                    label = { Text(stringResource(R.string.split_adj_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val svc = svcText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: job.serviceCharge
                val sst = sstText.toDoubleOrNull()?.coerceAtLeast(0.0) ?: job.sst
                val adj = adjText.toDoubleOrNull() ?: job.adjustment
                onConfirm(svc, sst, adj)
            }) {
                Text(stringResource(R.string.common_confirm), color = PrimaryTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Slate600)
            }
        }
    )
}

@Composable
private fun ReceiptItemFormDialog(
    title: String,
    initialName: String,
    initialPrice: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, price: Double) -> Unit
) {
    var itemName by remember(initialName) { mutableStateOf(initialName) }
    var itemPrice by remember(initialPrice) { mutableStateOf(initialPrice) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text(stringResource(R.string.split_item_name_hint)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = itemPrice,
                    onValueChange = { itemPrice = it },
                    label = { Text(stringResource(R.string.split_item_price_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = itemPrice.toDoubleOrNull() ?: 0.0
                if (itemName.isNotBlank() && p > 0.0) {
                    onConfirm(itemName.trim(), round(p * 100.0) / 100.0)
                } else {
                    onDismiss()
                }
            }) {
                Text(stringResource(R.string.common_confirm), color = PrimaryTeal, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = Slate600)
            }
        }
    )
}
