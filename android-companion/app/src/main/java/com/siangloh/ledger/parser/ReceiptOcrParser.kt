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
    val detectedTotal: Double,
    val computedTotal: Double,
    val isTallied: Boolean,
    val tallyDiff: Double,
    val rawLines: List<String>
)

object ReceiptOcrParser {

    private val PRICE_AT_END_REGEX = Regex("""^(.*?)\s+(?:RM|MYR)?\s*([0-9]{1,5}\.[0-9]{2})\s*[A-Za-z*#]*$""", RegexOption.IGNORE_CASE)
    private val ANY_AMOUNT_REGEX = Regex("""(?:RM|MYR)?\s*([0-9]{1,5}\.[0-9]{2})""", RegexOption.IGNORE_CASE)

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
        val maxDim = max(boundsOpts.outWidth, boundsOpts.outHeight)
        var sampleSize = 1
        while (maxDim / sampleSize > 1600) {
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

        // Scale to max 1600px if still larger
        val curMax = max(rotatedBmp.width, rotatedBmp.height)
        val previewBmp = if (curMax > 1600) {
            val scale = 1600f / curMax
            val scaled = Bitmap.createScaledBitmap(
                rotatedBmp,
                (rotatedBmp.width * scale).toInt().coerceAtLeast(1),
                (rotatedBmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== rotatedBmp) rotatedBmp.recycle()
            scaled
        } else {
            rotatedBmp
        }

        // Fast contrast boost copy for thermal receipt OCR
        val enhanced = Bitmap.createBitmap(previewBmp.width, previewBmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val cm = ColorMatrix().apply {
            setSaturation(0f) // grayscale
            val contrast = 1.35f
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

    private suspend fun recognizeText(bitmap: Bitmap): Text =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result)
                }
                .addOnFailureListener { err ->
                    if (cont.isActive) cont.resumeWithException(err)
                }
        }

    /**
     * Reconstruct horizontal receipt lines using bounding box Y-center alignment
     * so left-column item names and right-column prices stay on the same line.
     */
    private fun reconstructHorizontalLines(visionText: Text): List<String> {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return emptyList()

        val withBoxes = allLines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            Triple(line.text.trim(), box, box.centerY())
        }.filter { it.first.isNotBlank() }

        if (withBoxes.isEmpty()) {
            return visionText.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        }

        val heights = withBoxes.map { it.second.height() }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(12)
        val yTolerance = (medianHeight * 0.6f).toInt().coerceAtLeast(8)

        val sortedByY = withBoxes.sortedBy { it.third }
        val rows = mutableListOf<MutableList<Triple<String, android.graphics.Rect, Int>>>()

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

    /**
     * Pure Kotlin receipt line parser that extracts items, Service Charge, SST, and Total,
     * and verifies whether the sum of items + taxes tallies with the receipt Total.
     */
    fun parseLinesToReceiptResult(lines: List<String>): ParsedReceiptData {
        val cleaned = lines.map { it.trim() }.filter { it.isNotBlank() }
        val merchant = cleaned.firstOrNull { line ->
            val lower = line.lowercase(Locale.US)
            !lower.startsWith("tel") && !lower.startsWith("tax inv") &&
                !lower.startsWith("receipt") && line.any { it.isLetter() }
        }?.take(40) ?: "Receipt"

        var detectedTotal = 0.0
        var detectedSubtotal = 0.0
        var detectedServiceCharge = 0.0
        var detectedSst = 0.0
        val items = mutableListOf<ReceiptLineItem>()

        val skipKeywords = listOf(
            "cash", "change", "rounding", "visa", "mastercard", "duitnow",
            "ewallet", "tng", "grabpay", "tel:", "fax:", "gst reg", "sst reg",
            "invoice", "table", "pax", "cashier", "server", "qty", "thank you"
        )

        cleaned.forEachIndexed { idx, line ->
            val lower = line.lowercase(Locale.US)
            val priceMatch = PRICE_AT_END_REGEX.find(line)
            val endAmount = priceMatch?.groupValues?.get(2)?.toDoubleOrNull()
                ?: ANY_AMOUNT_REGEX.findAll(line).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()

            when {
                (lower.contains("grand total") || lower.contains("net total") ||
                    lower.contains("amount due") || lower.contains("total amount") ||
                    lower.contains("jumlah") || lower.contains("合计") ||
                    (lower.contains("total") && !lower.contains("subtotal"))) && endAmount != null -> {
                    if (endAmount > 0) detectedTotal = endAmount
                }
                lower.contains("subtotal") && endAmount != null -> {
                    if (endAmount > 0) detectedSubtotal = endAmount
                }
                (lower.contains("service") || lower.contains("svc") || lower.contains("s/c")) && endAmount != null -> {
                    if (endAmount > 0 && endAmount < 500) detectedServiceCharge += endAmount
                }
                (lower.contains("sst") || lower.contains("gst") || lower.contains("tax")) &&
                    !lower.contains("invoice") && !lower.contains("reg") && endAmount != null -> {
                    if (endAmount > 0 && endAmount < 500) detectedSst += endAmount
                }
                skipKeywords.any { lower.contains(it) } -> {
                    // Ignore non-item footer/payment metadata
                }
                priceMatch != null -> {
                    val rawName = priceMatch.groupValues[1].trim()
                        .replace(Regex("""^\d+\s*[xX*]\s*"""), "")
                        .trim()
                    val price = priceMatch.groupValues[2].toDoubleOrNull() ?: 0.0
                    if (rawName.any { it.isLetter() } && price > 0.0 && price < 10000.0) {
                        items.add(
                            ReceiptLineItem(
                                id = "item_${idx}_${items.size}",
                                name = rawName.take(48),
                                price = round2(price)
                            )
                        )
                    }
                }
            }
        }

        val itemsSum = round2(items.sumOf { it.price })
        val computedTotal = round2(itemsSum + detectedServiceCharge + detectedSst)
        val effectiveTotal = if (detectedTotal > 0.0) round2(detectedTotal) else computedTotal
        val tallyDiff = if (detectedTotal > 0.0) round2(computedTotal - detectedTotal) else 0.0
        val isTallied = detectedTotal <= 0.0 || abs(tallyDiff) <= 0.05

        return ParsedReceiptData(
            merchant = merchant,
            items = items,
            detectedSubtotal = round2(detectedSubtotal),
            detectedServiceCharge = round2(detectedServiceCharge),
            detectedSst = round2(detectedSst),
            detectedTotal = effectiveTotal,
            computedTotal = computedTotal,
            isTallied = isTallied,
            tallyDiff = tallyDiff,
            rawLines = cleaned
        )
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}

