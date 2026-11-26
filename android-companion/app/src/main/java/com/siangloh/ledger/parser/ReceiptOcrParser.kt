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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max

object ReceiptOcrParser {

    suspend fun parseImageUriAsync(context: Context, uri: Uri): Result<Pair<Bitmap, ParsedReceiptData>> =
        withContext(Dispatchers.IO) {
            try {
                val previewBitmap = decodeOrientedBitmap(context, uri)
                    ?: return@withContext Result.failure(Exception("Cannot decode receipt image"))

                // Pass 1: Natural color/grayscale camera bitmap (handles shadows & warm lighting best)
                val firstText = recognizeText(previewBitmap)
                val firstParsed = ReceiptTextParser.parseLinesToReceiptResult(reconstructHorizontalLines(firstText))

                // Pass 2: Adaptive contrast fallback ONLY if Pass 1 had missing items or failed Tally
                val bestParsed = if (firstParsed.items.isNotEmpty() && firstParsed.isTallied) {
                    firstParsed
                } else {
                    val enhanced = createContrastEnhancedCopy(previewBitmap)
                    try {
                        val secondText = recognizeText(enhanced)
                        val secondParsed = ReceiptTextParser.parseLinesToReceiptResult(reconstructHorizontalLines(secondText))
                        ReceiptTextParser.chooseBetterParse(firstParsed, secondParsed)
                    } finally {
                        if (!enhanced.isRecycled) enhanced.recycle()
                    }
                }
                Result.success(previewBitmap to bestParsed)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun parseLinesToReceiptResult(lines: List<String>): ParsedReceiptData =
        ReceiptTextParser.parseLinesToReceiptResult(lines)

    fun chooseBetterParse(a: ParsedReceiptData, b: ParsedReceiptData): ParsedReceiptData =
        ReceiptTextParser.chooseBetterParse(a, b)

    private fun decodeOrientedBitmap(context: Context, uri: Uri): Bitmap? {
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
        return previewBmp
    }

    private fun createContrastEnhancedCopy(previewBmp: Bitmap): Bitmap {
        // Moderate grayscale + luminance-safe contrast boost for faded thermal paper fallback
        val enhanced = Bitmap.createBitmap(previewBmp.width, previewBmp.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(enhanced)
        val cm = ColorMatrix().apply {
            setSaturation(0f)
            val contrast = 1.28f
            val translate = (-0.5f * contrast + 0.5f) * 255f + 8f
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
        return enhanced
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

    private data class RowBox(
        val text: String,
        val box: android.graphics.Rect,
        val deskewedY: Double,
        val deskewedTop: Double,
        val deskewedBottom: Double
    )

    /**
     * Reconstruct horizontal receipt lines using piecewise-local tilt deskewing and vertical
     * interval overlap, so curved/bent thermal receipts and mixed bold font totals stay aligned.
     */
    private fun reconstructHorizontalLines(visionText: Text): List<String> {
        val allLines = visionText.textBlocks.flatMap { it.lines }
        if (allLines.isEmpty()) return emptyList()

        // Estimate local camera tilt slopes (dy / dx) from wide text lines (ignoring divider rules like "----")
        val slopeSamples = allLines.mapNotNull { line ->
            val pts = line.cornerPoints
            val box = line.boundingBox
            val hasAlnum = line.text.any { it.isLetterOrDigit() || it.code in 0x4E00..0x9FFF }
            if (hasAlnum && pts != null && pts.size >= 2 && box != null) {
                val dx = (pts[1].x - pts[0].x).toDouble()
                val dy = (pts[1].y - pts[0].y).toDouble()
                if (dx > max(40.0, box.height() * 1.5)) {
                    box.centerY().toDouble() to (dy / dx).coerceIn(-0.35, 0.35)
                } else null
            } else null
        }
        val globalSortedSlopes = slopeSamples.map { it.second }.sorted()
        val globalSlope = if (globalSortedSlopes.isNotEmpty()) globalSortedSlopes[globalSortedSlopes.size / 2] else 0.0

        fun localSlopeFor(cy: Double): Double {
            val local = slopeSamples.filter { abs(it.first - cy) <= 420.0 }.map { it.second }.sorted()
            return if (local.size >= 2) local[local.size / 2] else globalSlope
        }

        val withBoxes = allLines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val t = line.text.trim()
            if (t.isBlank()) return@mapNotNull null
            val slope = localSlopeFor(box.centerY().toDouble())
            val offset = box.centerX() * slope
            RowBox(
                text = t,
                box = box,
                deskewedY = box.centerY() - offset,
                deskewedTop = box.top - offset,
                deskewedBottom = box.bottom - offset
            )
        }

        if (withBoxes.isEmpty()) {
            return visionText.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        }

        val heights = withBoxes.map { it.box.height() }.sorted()
        val medianHeight = heights[heights.size / 2].coerceAtLeast(12).toDouble()
        val yTolerance = max(8.0, medianHeight * 0.65)

        val sortedByY = withBoxes.sortedBy { it.deskewedY }
        val rows = mutableListOf<MutableList<RowBox>>()

        for (entry in sortedByY) {
            val targetRow = rows.lastOrNull()?.let { lastRow ->
                // Anchor to row median/first element and cap vertical span so vertical noise cannot bridge two lines
                val anchor = lastRow.first()
                val avgY = lastRow.map { it.deskewedY }.average()
                val overlap = kotlin.math.min(entry.deskewedBottom, anchor.deskewedBottom) -
                    max(entry.deskewedTop, anchor.deskewedTop)
                val minH = kotlin.math.min(
                    entry.deskewedBottom - entry.deskewedTop,
                    anchor.deskewedBottom - anchor.deskewedTop
                ).coerceAtLeast(1.0)
                val closeCenter = abs(entry.deskewedY - avgY) <= yTolerance &&
                    abs(entry.deskewedY - anchor.deskewedY) <= medianHeight * 1.15
                val strongOverlap = overlap >= minH * 0.55 &&
                    abs(entry.deskewedY - anchor.deskewedY) <= medianHeight * 1.15
                if (closeCenter || strongOverlap) lastRow else null
            }
            if (targetRow != null) {
                targetRow.add(entry)
            } else {
                rows.add(mutableListOf(entry))
            }
        }

        return rows.map { row ->
            row.sortedBy { it.box.left }.joinToString(" ") { it.text }
        }
    }
}
