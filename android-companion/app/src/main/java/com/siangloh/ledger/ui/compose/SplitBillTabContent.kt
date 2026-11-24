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
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
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
import androidx.compose.ui.text.style.TextAlign
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
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Camera & Multi-Photo Upload Actions (non-blocking background queue)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { launchCameraCapture() },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                shape = RoundedCornerShape(14.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp),
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.split_take_photo), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }

            OutlinedButton(
                onClick = { galleryLauncher.launch("image/*") },
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.2.dp, PrimaryTeal.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Teal50,
                    contentColor = PrimaryTeal
                ),
                modifier = Modifier.weight(1f).height(50.dp)
            ) {
                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.split_pick_photos), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }

        // 2. Background Receipt Queue (empty state or horizontal thumbnail cards)
        if (receiptJobs.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 0.5.dp,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Teal50,
                        border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.2f)),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                                contentDescription = null,
                                tint = PrimaryTeal,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.split_empty_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.split_empty_hint),
                        fontSize = 12.5.sp,
                        color = Slate500,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.split_queue_header_fmt, receiptJobs.size),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Slate100,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { SplitBillDraftHolder.clearReceipts() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.split_clear_all),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate600
                            )
                        }
                    }
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
        }

        // 3. Members Management Card (saved automatically for future bills)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 0.5.dp,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = null,
                            tint = PrimaryTeal,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = stringResource(R.string.split_members_title),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Teal50
                    ) {
                        Text(
                            text = "${members.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryTeal,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    members.forEach { m ->
                        val isMe = m == defaultMe
                        val initial = m.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isMe) PrimaryTeal else Teal50,
                            border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = if (isMe) 1f else 0.35f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 5.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isMe) Color.White.copy(alpha = 0.22f) else PrimaryTeal,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = initial,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = m,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isMe) Color.White else PrimaryTeal
                                )
                                if (!isMe) {
                                    Spacer(Modifier.width(5.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = null,
                                        tint = PrimaryTeal.copy(alpha = 0.8f),
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .clickable {
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
                        placeholder = { Text(stringResource(R.string.split_add_member_hint), fontSize = 12.5.sp, color = Slate400) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.5.sp, color = Slate900),
                        colors = ledgerInputColors(containerColor = Slate50)
                    )
                    Button(
                        onClick = {
                            if (newMemberText.isNotBlank()) {
                                SplitBillDraftHolder.addMember(context, newMemberText, defaultMe)
                                newMemberText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(50.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }

        // 4. Settlement Summary Card
        if (receiptJobs.any { !it.isAnalyzing && it.items.isNotEmpty() }) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.dp,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Top highlighted summary header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Teal50)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.split_settlement_title),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Slate900
                                )
                                if (members.size > 1 && myShare > 0.0) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color.White,
                                        border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.25f))
                                    ) {
                                        Text(
                                            text = stringResource(R.string.split_my_share_fmt, myShare),
                                            fontSize = 11.sp,
                                            color = PrimaryTeal,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "RM ${String.format(Locale.US, "%.2f", grandTotalAll)}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = PrimaryTeal,
                                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                            )
                        }
                    }

                    HorizontalDivider(color = PrimaryTeal.copy(alpha = 0.12f), thickness = 1.dp)

                    // Per-member breakdown rows
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val entries = memberTotals.entries.toList()
                        entries.forEachIndexed { idx, (member, total) ->
                            val isMe = member == defaultMe
                            val initial = member.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isMe) PrimaryTeal else Slate200,
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = initial,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isMe) Color.White else Slate700
                                            )
                                        }
                                    }
                                    Text(
                                        text = member,
                                        fontSize = 13.5.sp,
                                        color = if (isMe) Slate900 else Slate700,
                                        fontWeight = if (isMe) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "RM ${String.format(Locale.US, "%.2f", total)}",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isMe) PrimaryTeal else Slate900,
                                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                                )
                            }
                            if (idx < entries.lastIndex) {
                                HorizontalDivider(color = Slate100, thickness = 1.dp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.2.dp, PrimaryTeal),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryTeal),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = stringResource(R.string.split_copy_whatsapp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
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
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.weight(1f).height(46.dp)
                            ) {
                                Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = stringResource(R.string.split_record_expense),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            title = {
                Text(stringResource(R.string.split_record_choice_title), fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = Slate900)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Teal50,
                        border = BorderStroke(1.2.dp, PrimaryTeal.copy(alpha = 0.45f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                showRecordChoiceDialog = false
                                onRecordTotalPaid(myShare, "$merchantSummary (AA)")
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.split_record_my_share_fmt, myShare), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryTeal)
                            Text(stringResource(R.string.split_record_my_share_desc), fontSize = 12.sp, color = Slate600, lineHeight = 17.sp)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                showRecordChoiceDialog = false
                                onRecordTotalPaid(grandTotalAll, "$merchantSummary (AA垫付 · 待Rebate)")
                            }
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.split_record_advance_fmt, grandTotalAll), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Slate900)
                            Text(stringResource(R.string.split_record_advance_desc), fontSize = 12.sp, color = Slate600, lineHeight = 17.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRecordChoiceDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = Slate600, fontWeight = FontWeight.SemiBold)
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
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = if (isSelected) 3.dp else 1.dp,
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) PrimaryTeal else Slate200
        ),
        modifier = Modifier
            .width(164.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = !job.isAnalyzing, onClick = onSelectOrRetry)
    ) {
        Box {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(86.dp)
                        .clip(RoundedCornerShape(10.dp))
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
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            tint = Slate300,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    if (job.isAnalyzing) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
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

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (job.isAnalyzing) stringResource(R.string.split_analyzing) else job.merchant,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!job.isAnalyzing && job.errorMsg == null) {
                        Text(
                            text = stringResource(R.string.split_items_count_fmt, job.items.size),
                            fontSize = 10.5.sp,
                            color = Slate500,
                            maxLines = 1
                        )
                    }
                }

                if (!job.isAnalyzing) {
                    val badgeBg = when {
                        job.errorMsg != null -> Rose50
                        job.isTallied -> Emerald50
                        else -> Amber50
                    }
                    val badgeFg = when {
                        job.errorMsg != null -> ExpenseRed
                        job.isTallied -> Emerald700
                        else -> Amber700
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = badgeText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = badgeFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Corner remove button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 640.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Sticky Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = job.merchant,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Slate900,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (job.isTallied) Emerald50 else Amber50
                        ) {
                            Text(
                                text = if (job.isTallied) {
                                    stringResource(R.string.split_tally_ok, "RM ${String.format(Locale.US, "%.2f", job.computedTotal)}")
                                } else {
                                    stringResource(R.string.split_tally_diff, "RM ${String.format(Locale.US, "%.2f", abs(job.tallyDiff))}")
                                },
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (job.isTallied) Emerald700 else Amber700,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Slate100)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            tint = Slate600,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = Slate100, thickness = 1.dp)

                // Scrollable body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    job.thumbnail?.let { bmp ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 156.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, Slate200, RoundedCornerShape(12.dp))
                                .clickable { onZoomImage(bmp) }
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = job.merchant,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color.Black.copy(alpha = 0.65f),
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.ZoomIn, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.split_tap_zoom), fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                            }
                        }
                    }

                    // Tappable Service Charge / SST / Adjustment summary row
                    val taxPart = stringResource(R.string.split_tax_summary_fmt, job.serviceCharge, job.sst)
                    val adjPart = if (job.adjustment != 0.0) stringResource(R.string.split_adj_summary_fmt, job.adjustment) else ""
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Teal50,
                        border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onEditCharges)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = taxPart + adjPart,
                                fontSize = 12.sp,
                                color = Slate800,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = CircleShape,
                                color = Color.White,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Dish item cards
                    job.items.forEach { item ->
                        val multiQty = SplitBillLogic.extractQuantityPrefix(item.name)?.first
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = item.name,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate900,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "RM ${String.format(Locale.US, "%.2f", item.price)}",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = PrimaryTeal,
                                        style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                                    )
                                }

                                // Compact action row + member selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val allSelected = members.all { it in item.assignedMembers }
                                        AssigneePill(
                                            label = stringResource(R.string.split_all_btn),
                                            selected = allSelected,
                                            onClick = {
                                                onUpdateAssignees(item.id, if (allSelected) emptySet() else members.toSet())
                                            }
                                        )
                                        members.forEach { m ->
                                            val isAssigned = m in item.assignedMembers
                                            AssigneePill(
                                                label = m,
                                                selected = isAssigned,
                                                onClick = {
                                                    val next = if (isAssigned) item.assignedMembers - m else item.assignedMembers + m
                                                    onUpdateAssignees(item.id, next)
                                                }
                                            )
                                        }
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (multiQty != null) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = Teal50,
                                                border = BorderStroke(1.dp, PrimaryTeal.copy(alpha = 0.45f)),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable { onSplitQuantityItem(item.id) }
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.split_qty_btn, multiQty),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PrimaryTeal,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White,
                                            border = BorderStroke(1.dp, Slate200),
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable { onEditItem(item) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(14.dp))
                                            }
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = Color.White,
                                            border = BorderStroke(1.dp, Slate200),
                                            modifier = Modifier
                                                .size(28.dp)
                                                .clip(CircleShape)
                                                .clickable { onDeleteItem(item.id) }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = Slate400, modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (job.rawLines.isNotEmpty()) {
                        var showRawLines by remember(job.id) { mutableStateOf(false) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showRawLines = !showRawLines }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.split_raw_lines_toggle),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Slate500
                            )
                            Icon(
                                imageVector = if (showRawLines) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Slate500,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (showRawLines) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
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

                HorizontalDivider(color = Slate100, thickness = 1.dp)

                // Sticky Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onRequestAddItem,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryTeal, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.split_add_item_btn),
                            color = PrimaryTeal,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryTeal),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_confirm),
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssigneePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) PrimaryTeal else Color.White,
        border = BorderStroke(1.dp, if (selected) PrimaryTeal else Slate200),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) Color.White else Slate700,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
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
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = { Text(stringResource(R.string.split_edit_charges_btn), fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = Slate900) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = svcText,
                    onValueChange = { svcText = it },
                    label = { Text(stringResource(R.string.split_svc_label), fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = ledgerInputColors(containerColor = Slate50)
                )
                OutlinedTextField(
                    value = sstText,
                    onValueChange = { sstText = it },
                    label = { Text(stringResource(R.string.split_sst_label), fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = ledgerInputColors(containerColor = Slate50)
                )
                OutlinedTextField(
                    value = adjText,
                    onValueChange = { adjText = it },
                    label = { Text(stringResource(R.string.split_adj_label), fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = ledgerInputColors(containerColor = Slate50)
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
        shape = RoundedCornerShape(20.dp),
        containerColor = Color.White,
        title = { Text(title, fontSize = 16.5.sp, fontWeight = FontWeight.ExtraBold, color = Slate900) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = itemName,
                    onValueChange = { itemName = it },
                    label = { Text(stringResource(R.string.split_item_name_hint), fontSize = 12.sp) },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = ledgerInputColors(containerColor = Slate50)
                )
                OutlinedTextField(
                    value = itemPrice,
                    onValueChange = { itemPrice = it },
                    label = { Text(stringResource(R.string.split_item_price_hint), fontSize = 12.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = ledgerInputColors(containerColor = Slate50)
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
