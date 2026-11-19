package com.siangloh.ledger.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

data class ReceiptLineItem(
    val id: String,
    val name: String,
    val price: Double
)

data class ParsedReceiptData(
    val merchant: String,
    val items: List<ReceiptLineItem>,
    val detectedSubtotal: Double,
    val detectedServiceCharge: Double,
    val detectedSst: Double,
    val detectedAdjustment: Double = 0.0,
    val detectedTotal: Double,
    val computedTotal: Double,
    val isTallied: Boolean,
    val tallyDiff: Double,
    val rawLines: List<String>
)

object ReceiptOcrParser {

    // Support standard decimals (14.50), comma-thousands (1,234.50), or OCR comma-decimals (14,50)
    private val PRICE_AT_END_REGEX = Regex(
        """^(.*?)\s+(?:RM|MYR|\$)?\s*([0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{2}|[0-9]{1,5}[.,][0-9]{2})\s*[A-Za-z*#]*$""",
        RegexOption.IGNORE_CASE
    )
    private val SIGNED_AMOUNT_REGEX = Regex(
        """(\(?-?\s*(?:RM|MYR|\$)?\s*(?:[0-9]{1,3}(?:,[0-9]{3})*\.[0-9]{2}|[0-9]{1,5}[.,][0-9]{2})\)?)""",
        RegexOption.IGNORE_CASE
    )
    private val DATE_SLASH_OR_DOT_REGEX = Regex("""^\s*(?:date|tarikh|日期)?[:\s-]*\d{1,4}[/.-]\d{1,2}[/.-]\d{2,4}.*$""", RegexOption.IGNORE_CASE)
    private val TIME_ONLY_REGEX = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)?\b""", RegexOption.IGNORE_CASE)
    private val TRAILING_UNIT_PRICE_REGEX = Regex(
        """^(.*?)(?:\s+@|\s+x\s*\d+|\s+(?:RM|MYR)?\s*[0-9]{1,5}[.,][0-9]{2})+$""",
        RegexOption.IGNORE_CASE
    )
    private val LEADING_QTY_REGEX = Regex("""^(\d+)\s*[xX*]\s+(.+)$""")
    private val LEADING_BARE_QTY_REGEX = Regex("""^(\d{1,2})\s+([A-Za-z\u4E00-\u9FFF].+)$""")

    suspend fun parseImageUriAsync(context: Context, uri: Uri): Result<Pair<Bitmap, ParsedReceiptData>> =
        withContext(Dispatchers.IO) {
            try {
                val (previewBitmap, ocrBitmap) = decodeAndEnhanceBitmap(context, uri)
                    ?: return@withContext Result.failure(Exception("Cannot decode receipt image"))
                val visionText = recognizeText(ocrBitmap)
                if (ocrBitmap !== previewBitmap && !ocrBitmap.isRecycled) {
                    ocrBitmap.recycle()
                }
                val reconstructedLines = reconstructHorizontalLines(visionText)
                val parsed = parseLinesToReceiptResult(reconstructedLines)
                Result.success(previewBitmap to parsed)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun decodeAndEnhanceBitmap(context: Context, uri: Uri): Pair<Bitmap, Bitmap>? {
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            try {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (_: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
        } ?: ExifInterface.ORIENTATION_NORMAL

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val minDim = kotlin.math.min(boundsOpts.outWidth, boundsOpts.outHeight)
        val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        while (minDim / (sampleSize * 2) >= 1200 && maxDim / (sampleSize * 2) >= 2400) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val rawBmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val rotatedBmp = rotateByExif(rawBmp, orientation)
        if (rotatedBmp !== rawBmp && !rawBmp.isRecycled) {
            rawBmp.recycle()
        }

        // Preserve width sharpness on tall narrow thermal receipts (max width <= 1400px, max height <= 3200px)
        val curMin = kotlin.math.min(rotatedBmp.width, rotatedBmp.height)
        val curMax = max(rotatedBmp.width, rotatedBmp.height)
        val previewBmp = when {
            curMin > 1400 || curMax > 3200 -> {
                val scale = kotlin.math.min(1400f / curMin.coerceAtLeast(1), 3200f / curMax.coerceAtLeast(1))
                Bitmap.createScaledBitmap(
                    rotatedBmp,
                    (rotatedBmp.width * scale).toInt().coerceAtLeast(1),
                    (rotatedBmp.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== rotatedBmp) rotatedBmp.recycle() }
            }
            curMax in 1 until 1000 -> {
                val scale = (1200f / curMax).coerceAtMost(2.5f)
                Bitmap.createScaledBitmap(
                    rotatedBmp,
                    (rotatedBmp.width * scale).toInt().coerceAtLeast(1),
                    (rotatedBmp.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also { if (it !== rotatedBmp) rotatedBmp.recycle() }
            }
            else -> rotatedBmp
        }

        // Fast grayscale + contrast boost copy for faded thermal receipt OCR
        val enhanced = Bitmap.createBitmap(previewBmp.width, previewBmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val cm = ColorMatrix().apply {
            setSaturation(0f) // grayscale
            val contrast = 1.4f
            val translate = (-0.5f * contrast + 0.5f) * 255f
            val contrastMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            postConcat(contrastMatrix)
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(previewBmp, 0f, 0f, paint)
        return previewBmp to enhanced
    }

    private fun rotateByExif(bmp: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bmp
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private suspend fun recognizeText(bitmap: Bitmap): Text {
        return try {
            runRecognizer(bitmap, ChineseTextRecognizerOptions.Builder().build())
        } catch (_: Exception) {
            runRecognizer(bitmap, TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private suspend fun runRecognizer(
        bitmap: Bitmap,
        options: com.google.mlkit.vision.text.TextRecognizerOptionsInterface
    ): Text = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(options)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                if (cont.isActive) cont.resume(result)
            }
            .addOnFailureListener { err ->
                if (cont.isActive) cont.resumeWithException(err)
            }
    }

    /**
     * Reconstruct horizontal receipt lines using bounding box Y-center alignment with tilt
     * compensation (deskewing by the median line slope from ML Kit cornerPoints), so tilted
     * handheld photos still keep left-column dish names and right-column prices on one row.
     */
    private fun reconstructHorizontalLines(visionText: Text): List<String> {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return emptyList()

        // Estimate camera tilt slope (dy / dx) from wide text lines (ignoring divider rules like "----")
        val slopes = allLines.mapNotNull { line ->
            val pts = line.cornerPoints
            val box = line.boundingBox
            val hasAlnum = line.text.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
            if (hasAlnum && pts != null && pts.size >= 2 && box != null) {
                val dx = (pts[1].x - pts[0].x).toDouble()
                val dy = (pts[1].y - pts[0].y).toDouble()
                if (dx > max(40.0, box.height() * 1.5)) {
                    (dy / dx).coerceIn(-0.35, 0.35)
                } else null
            } else null
        }.sorted()
        val medianSlope = if (slopes.isNotEmpty()) slopes[slopes.size / 2] else 0.0

        val withBoxes = allLines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val deskewedY = box.centerY() - box.centerX() * medianSlope
            Triple(line.text.trim(), box, deskewedY)
        }.filter { it.first.isNotBlank() }

        if (withBoxes.isEmpty()) {
            return visionText.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        }

        val heights = withBoxes.map { it.second.height() }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(12).toDouble()
        val yTolerance = max(8.0, medianHeight * 0.65)

        val sortedByY = withBoxes.sortedBy { it.third }
        val rows = mutableListOf<MutableList<Triple<String, android.graphics.Rect, Double>>>()

        for (entry in sortedByY) {
            val targetRow = rows.lastOrNull()?.let { lastRow ->
                val avgY = lastRow.map { it.third }.average()
                if (abs(entry.third - avgY) <= yTolerance) lastRow else null
            }
            if (targetRow != null) {
                targetRow.add(entry)
            } else {
                rows.add(mutableListOf(entry))
            }
        }

        return rows.map { row ->
            row.sortedBy { it.second.left }.joinToString(" ") { it.first }
        }
    }

    private fun parseAmountToken(token: String): Double? {
        val raw = token.trim()
        val isNegative = raw.startsWith("-") || (raw.startsWith("(") && raw.endsWith(")"))
        var numeric = raw
            .replace("(", "")
            .replace(")", "")
            .replace("-", "")
            .replace(Regex("(?i)(?:RM|MYR|\\$)"), "")
            .trim()
        // If format is like 1,234.50 -> strip thousand commas
        if (numeric.contains('.') && numeric.contains(',')) {
            numeric = numeric.replace(",", "")
        } else if (Regex("""^\d{1,5},\d{2}$""").matches(numeric)) {
            // Thermal OCR often reads '.' as ',' -> e.g. 14,50 -> 14.50
            numeric = numeric.replace(',', '.')
        } else {
            numeric = numeric.replace(",", "")
        }
        val value = numeric.toDoubleOrNull() ?: return null
        return if (isNegative) -value else value
    }

    private fun cleanItemName(rawPrefix: String): String {
        var name = rawPrefix.trim()
        // Strip trailing unit prices / "@ 12.00" when a row has [Item  Qty  UnitPrice  LineTotal]
        val strippedTrailing = TRAILING_UNIT_PRICE_REGEX.find(name)?.groupValues?.get(1)?.trim()
        if (!strippedTrailing.isNullOrBlank() && strippedTrailing.any { it.isLetter() || it.code > 0x4E00 }) {
            name = strippedTrailing
        }
        // Normalize leading "2 x Soup" -> "2x Soup", "2 Soup" -> "2x Soup"
        val qtyMatch = LEADING_QTY_REGEX.find(name)
        if (qtyMatch != null) {
            val qty = qtyMatch.groupValues[1].toIntOrNull() ?: 1
            val dish = qtyMatch.groupValues[2].trim()
            name = if (qty > 1) "${qty}x $dish" else dish
        } else {
            val bareQtyMatch = LEADING_BARE_QTY_REGEX.find(name)
            if (bareQtyMatch != null) {
                val qty = bareQtyMatch.groupValues[1].toIntOrNull() ?: 1
                val dish = bareQtyMatch.groupValues[2].trim()
                name = if (qty in 2..20) "${qty}x $dish" else dish
            }
        }
        return name.trim().take(48)
    }

    /**
     * Pure Kotlin receipt line parser that extracts items, Service Charge, SST, Rounding/Discount,
     * and Total, verifying whether the sum of items + charges + adjustments tallies with Total.
     */
    fun parseLinesToReceiptResult(lines: List<String>): ParsedReceiptData {
        val cleaned = lines.map { it.trim() }.filter { it.isNotBlank() }
        val merchant = cleaned.firstOrNull { line ->
            val lower = line.lowercase(Locale.US)
            !lower.startsWith("tel") && !lower.startsWith("tax inv") &&
                !lower.startsWith("receipt") && !lower.startsWith("sst") &&
                !lower.startsWith("gst") && !DATE_SLASH_OR_DOT_REGEX.matches(line) &&
                line.any { it.isLetter() || it.code in 0x4E00..0x9FFF }
        }?.take(40) ?: "Receipt"

        var detectedTotal = 0.0
        var detectedSubtotal = 0.0
        var detectedServiceCharge = 0.0
        var detectedSst = 0.0
        var detectedAdjustment = 0.0
        var sawInclusiveTaxHint = false
        var pendingWrappedName: String? = null
        val items = mutableListOf<ReceiptLineItem>()

        val skipKeywords = listOf(
            "cash", "change", "visa", "mastercard", "master card", "amex", "duitnow",
            "ewallet", "e-wallet", "tng", "grabpay", "shopeepay", "boost",
            "tel:", "tel ", "fax:", "gst reg", "gst id", "gst no", "sst reg", "sst id", "sst no",
            "invoice", "table", "pax", "cashier", "server", "qty", "thank you", "terima kasih",
            "date:", "time:", "tarikh", "masa", "balance", "tender", "paid", "payment",
            "card no", "appr", "trace", "batch", "ref no", "order #", "bill #", "points", "member",
            "dine in", "dine-in", "take away", "takeaway", "welcome", "receipt #", "pos "
        )

        cleaned.forEachIndexed { idx, line ->
            val lower = line.lowercase(Locale.US)
            val isDateLine = DATE_SLASH_OR_DOT_REGEX.matches(line)
            val isTimeLine = TIME_ONLY_REGEX.containsMatchIn(line) && !lower.contains("total")

            val priceMatch = PRICE_AT_END_REGEX.find(line)
            val endAmount = priceMatch?.groupValues?.get(2)?.let { parseAmountToken(it) }
                ?: SIGNED_AMOUNT_REGEX.findAll(line).lastOrNull()?.groupValues?.get(1)?.let { parseAmountToken(it) }

            val isSubTotalLine = lower.contains("subtotal") ||
                lower.contains("sub-total") ||
                lower.contains("sub total") ||
                lower.contains("小计")

            val isRoundingLine = lower.contains("rounding") || lower.contains("round adj") ||
                lower.contains("四舍五入") || lower.contains("pembundaran")

            val isDiscountLine = lower.contains("discount") || lower.contains("voucher") ||
                lower.contains("promo") || lower.contains("rebate") ||
                lower.contains("less ") || lower.contains("savings") || lower.contains("折扣")

            val isTaxLine = (lower.contains("sst") || lower.contains("gst") ||
                lower.contains("service tax") || lower.contains("tax") ||
                lower.contains("cukai") || lower.contains("税")) &&
                !lower.contains("invoice") && !lower.contains("reg") &&
                !lower.contains("id") && !lower.contains("no.")

            val isServiceChargeLine = !isTaxLine &&
                (lower.contains("service") || lower.contains("svc") ||
                    lower.contains("s/c") || lower.contains("服务费")) &&
                !lower.contains("tax inv")

            val isTotalLine = !isSubTotalLine && !isDiscountLine && !isTaxLine &&
                !isServiceChargeLine && !isRoundingLine &&
                !lower.contains("total qty") && !lower.contains("total item") && (
                lower.contains("grand total") || lower.contains("net total") ||
                    lower.contains("nett total") || lower.contains("amount due") ||
                    lower.contains("total amount") || lower.contains("total due") ||
                    lower.contains("total payable") || lower.contains("jumlah besar") ||
                    lower.contains("jumlah") || lower.contains("合计") || lower.contains("总计") ||
                    Regex("""\btotal\b""").containsMatchIn(lower)
                )

            when {
                isSubTotalLine && endAmount != null -> {
                    pendingWrappedName = null
                    if (endAmount > 0) detectedSubtotal = endAmount
                }
                isRoundingLine && endAmount != null -> {
                    pendingWrappedName = null
                    if (abs(endAmount) <= 1.0) detectedAdjustment += endAmount
                }
                isDiscountLine && endAmount != null -> {
                    pendingWrappedName = null
                    // Discounts reduce total even if OCR dropped the '-' sign or parentheses
                    val adj = if (endAmount > 0 && abs(endAmount) < 10000) -endAmount else endAmount
                    if (abs(adj) < 10000) detectedAdjustment += adj
                }
                isTaxLine && endAmount != null -> {
                    pendingWrappedName = null
                    if (lower.contains("incl") || lower.contains("included")) {
                        sawInclusiveTaxHint = true
                    }
                    if (endAmount > 0 && endAmount < 2000) detectedSst += endAmount
                }
                isServiceChargeLine && endAmount != null -> {
                    pendingWrappedName = null
                    if (endAmount > 0 && endAmount < 2000) detectedServiceCharge += endAmount
                }
                isTotalLine && endAmount != null -> {
                    pendingWrappedName = null
                    if (endAmount > 0) detectedTotal = endAmount
                }
                isDateLine || isTimeLine || skipKeywords.any { lower.contains(it) } -> {
                    pendingWrappedName = null
                }
                priceMatch != null && endAmount != null -> {
                    val rawPrefix = priceMatch.groupValues[1].trim()
                    var dishName = cleanItemName(rawPrefix)
                    val isQtyOrModifierOnly = dishName.matches(Regex("""^\d+\s*[xX*@]?$""")) ||
                        dishName.startsWith("(") ||
                        dishName.none { it.isLetter() || it.code in 0x4E00..0x9FFF }
                    if (pendingWrappedName != null && (isQtyOrModifierOnly || rawPrefix.length <= 16)) {
                        val qtyOnlyMatch = Regex("""^(\d+)\s*[xX*@]?$""").find(rawPrefix.replace(Regex("""\s+\d+[.,]\d{2}$"""), "").trim())
                        dishName = if (qtyOnlyMatch != null) {
                            "${qtyOnlyMatch.groupValues[1]}x $pendingWrappedName"
                        } else if (dishName.any { it.isLetter() || it.code in 0x4E00..0x9FFF }) {
                            "$pendingWrappedName $dishName".take(48)
                        } else {
                            pendingWrappedName!!
                        }
                    }
                    pendingWrappedName = null
                    if ((dishName.any { it.isLetter() || it.code in 0x4E00..0x9FFF }) &&
                        endAmount > 0.0 && endAmount < 50000.0
                    ) {
                        items.add(
                            ReceiptLineItem(
                                id = "item_${idx}_${items.size}",
                                name = dishName.take(48),
                                price = round2(endAmount)
                            )
                        )
                    }
                }
                else -> {
                    val isMeaningfulText = idx > 1 &&
                        line != merchant &&
                        endAmount == null &&
                        line.count { it.isLetter() || it.code in 0x4E00..0x9FFF } >= 3
                    pendingWrappedName = if (isMeaningfulText) line.take(36) else null
                }
            }
        }

        val itemsSum = round2(items.sumOf { it.price })
        // If receipt printed "Incl. 6% SST" and Subtotal+Adjustment already equals Total, don't double-add inclusive SST
        val sumExclusive = round2(itemsSum + detectedServiceCharge + detectedSst + detectedAdjustment)
        val sumInclusive = round2(itemsSum + detectedServiceCharge + detectedAdjustment)
        if (detectedTotal > 0.0 && detectedSst > 0.0 &&
            (sawInclusiveTaxHint || abs(sumInclusive - detectedTotal) <= 0.05) &&
            abs(sumExclusive - detectedTotal) > 0.05
        ) {
            detectedSst = 0.0
        }

        val computedTotal = round2(itemsSum + detectedServiceCharge + detectedSst + detectedAdjustment)
        val effectiveTotal = if (detectedTotal > 0.0) round2(detectedTotal) else computedTotal
        val tallyDiff = if (detectedTotal > 0.0) round2(computedTotal - detectedTotal) else 0.0
        val isTallied = detectedTotal <= 0.0 || abs(tallyDiff) <= 0.05

        return ParsedReceiptData(
            merchant = merchant,
            items = items,
            detectedSubtotal = round2(detectedSubtotal),
            detectedServiceCharge = round2(detectedServiceCharge),
            detectedSst = round2(detectedSst),
            detectedAdjustment = round2(detectedAdjustment),
            detectedTotal = effectiveTotal,
            computedTotal = computedTotal,
            isTallied = isTallied,
            tallyDiff = tallyDiff,
            rawLines = cleaned
        )
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}

