package com.siangloh.ledger

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.siangloh.ledger.parser.ReceiptOcrParser
import com.siangloh.ledger.ui.compose.SplitBillDraftHolder
import com.siangloh.ledger.ui.compose.SplitBillLogic
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class ReceiptOcrEndToEndTest {

    @Test
    fun fileProviderAndMlKitOcr_extractsRealisticMalaysianReceiptAccurately() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Render a realistic two-column thermal receipt bitmap (left dish names, right prices)
        val width = 900
        val height = 1150
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paintHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 36f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val paintLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        val paintRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }

        var y = 80f
        canvas.drawText("NANYANG KOPITIAM", width / 2f, y, paintHeader)
        y += 50f
        canvas.drawText("Tax Invoice #INV-8821", 60f, y, paintLeft)
        y += 45f
        canvas.drawText("Date: 19/11/2026 13:45", 60f, y, paintLeft)
        y += 65f

        val rows = listOf(
            "Nasi Lemak Ayam" to "18.50",
            "Char Kuey Teow" to "14.00",
            "Iced Teh Tarik" to "5.50",
            "Kaya Toast Butter" to "6.00",
            "Sub-Total" to "44.00",
            "Service Charge 10%" to "4.40",
            "SST 6%" to "2.64",
            "Rounding Adj" to "-0.04",
            "TOTAL" to "51.00"
        )
        rows.forEach { (label, amt) ->
            canvas.drawText(label, 60f, y, paintLeft)
            canvas.drawText(amt, width - 60f, y, paintRight)
            y += 68f
        }

        // Verify FileProvider path matches res/xml/file_paths.xml (receipt_photos/)
        val photosDir = File(context.cacheDir, "receipt_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "test_receipt_e2e.jpg")
        FileOutputStream(photoFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        val result = ReceiptOcrParser.parseImageUriAsync(context, uri)
        assertTrue("OCR failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val (previewBmp, parsed) = result.getOrThrow()
        assertTrue(previewBmp.width > 0 && previewBmp.height > 0)
        previewBmp.recycle()

        assertTrue(
            "Expected >= 4 items, got ${parsed.items.size}: ${parsed.rawLines}",
            parsed.items.size >= 4
        )
        assertEquals("Expected Service Charge 4.40, lines=${parsed.rawLines}", 4.40, parsed.detectedServiceCharge, 0.05)
        assertEquals("Expected SST 2.64, lines=${parsed.rawLines}", 2.64, parsed.detectedSst, 0.05)
        assertEquals("Expected Rounding -0.04, lines=${parsed.rawLines}", -0.04, parsed.detectedAdjustment, 0.05)
        assertEquals("Expected Total 51.00, lines=${parsed.rawLines}", 51.00, parsed.detectedTotal, 0.05)
        assertTrue(
            "Expected Tally OK (computed=${parsed.computedTotal}, detected=${parsed.detectedTotal}, diff=${parsed.tallyDiff}), lines=${parsed.rawLines}",
            parsed.isTallied
        )
    }

    @Test
    fun tiltedFadedBilingualReceipt_extractsChineseAndEnglishAndTalliesCleanly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Render a faded off-gray thermal receipt rotated -2.5 degrees (simulates imperfect handheld snap)
        val width = 680
        val height = 820
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(225, 225, 222)) // dim thermal paper
        canvas.save()
        canvas.rotate(-2.5f, width / 2f, height / 2f)

        val paintHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 55, 58)
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val paintLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 60, 65)
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        val paintRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(60, 60, 65)
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }

        var y = 70f
        canvas.drawText("南洋茶室 NANYANG CAFE", width / 2f, y, paintHeader)
        y += 55f

        val rows = listOf(
            "2x 海南鸡饭 Chicken Rice 12.00" to "24.00",
            "冰奶茶 Teh Ais" to "4.50",
            "烤面包 Kaya Toast" to "5.50",
            "Sub Total" to "34.00",
            "Service Charge 10%" to "3.40",
            "SST 6%" to "2.04",
            "Rounding Adj" to "-0.04",
            "GRAND TOTAL" to "RM 39.40"
        )
        rows.forEach { (label, amt) ->
            canvas.drawText(label, 42f, y, paintLeft)
            canvas.drawText(amt, width - 42f, y, paintRight)
            y += 62f
        }
        canvas.restore()

        val photosDir = File(context.cacheDir, "receipt_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "test_tilted_bilingual.jpg")
        FileOutputStream(photoFile).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        val result = ReceiptOcrParser.parseImageUriAsync(context, uri)
        assertTrue("OCR failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val (previewBmp, parsed) = result.getOrThrow()
        previewBmp.recycle()

        assertTrue(
            "Expected >= 3 items on faded tilted bilingual receipt, got ${parsed.items.size}: ${parsed.rawLines}",
            parsed.items.size >= 3
        )
        assertEquals("Expected Service Charge 3.40, lines=${parsed.rawLines}", 3.40, parsed.detectedServiceCharge, 0.05)
        assertEquals("Expected SST 2.04, lines=${parsed.rawLines}", 2.04, parsed.detectedSst, 0.05)
        assertEquals("Expected Total 39.40, lines=${parsed.rawLines}", 39.40, parsed.detectedTotal, 0.05)
        assertTrue(
            "Expected Tally OK (computed=${parsed.computedTotal}, detected=${parsed.detectedTotal}, diff=${parsed.tallyDiff}), lines=${parsed.rawLines}",
            parsed.isTallied
        )
    }

    @Test
    fun splitBillDraftHolder_persistsFriendsAndCompletesAsyncOcrWithSplitAndCharges() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SplitBillDraftHolder.clearReceipts()
        SplitBillDraftHolder.ensureInitialized(context, "Me")
        SplitBillDraftHolder.addMember(context, "Alex", "Me")
        assertTrue("Expected Alex in members", "Alex" in SplitBillDraftHolder.members)

        // Draw receipt with "2x Chicken Rice 12.00 | 24.00" + "SST 6% | 1.44" + "TOTAL | 25.44"
        val bmp = Bitmap.createBitmap(760, 560, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val pLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        val pRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("BISTRO TEST", 50f, 70f, pLeft)
        canvas.drawText("2x Chicken Rice 12.00", 50f, 160f, pLeft)
        canvas.drawText("24.00", 710f, 160f, pRight)
        canvas.drawText("SST 6%", 50f, 240f, pLeft)
        canvas.drawText("1.44", 710f, 240f, pRight)
        canvas.drawText("TOTAL", 50f, 320f, pLeft)
        canvas.drawText("25.44", 710f, 320f, pRight)

        val photosDir = File(context.cacheDir, "receipt_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "test_holder_e2e.jpg")
        FileOutputStream(photoFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        SplitBillDraftHolder.enqueueReceiptAnalysis(context, uri)

        var attempts = 0
        while (attempts < 40 && SplitBillDraftHolder.receiptJobs.any { it.isAnalyzing }) {
            delay(200)
            attempts++
        }
        val job = SplitBillDraftHolder.receiptJobs.firstOrNull()
        assertTrue("Expected completed receipt job", job != null && !job.isAnalyzing && job.errorMsg == null)
        assertTrue("Expected item parsed, got ${job!!.items}", job.items.isNotEmpty())
        assertTrue("Expected Tally OK, diff=${job.tallyDiff}", job.isTallied)

        // Test splitting multi-quantity item into individual servings + adjusting charges
        val firstItemId = job.items.first().id
        SplitBillDraftHolder.splitMultiQuantityItemInJob(job.id, firstItemId)
        val updatedJob = SplitBillDraftHolder.receiptJobs.first { it.id == job.id }
        assertTrue("Expected split into >=2 rows, got ${updatedJob.items.size}", updatedJob.items.size >= 2)

        SplitBillDraftHolder.updateJobCharges(job.id, serviceCharge = 2.40, sst = 1.44, adjustment = -0.04)
        val totals = SplitBillLogic.computeMemberTotals(SplitBillDraftHolder.receiptJobs, SplitBillDraftHolder.members)
        assertTrue("Expected positive split total for Me", (totals["Me"] ?: 0.0) > 10.0)

        SplitBillDraftHolder.clearReceipts()
    }

    @Test
    fun complexLongHotpotReceipt_withWrappedItemsDashRulesTiltAndBracketDiscountTalliesCleanly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Tall 920x2150 thermal strip rotated +3.8 deg (opposite direction tilt), dashed dividers,
        // wrapped 2-line dish, add-on modifier, 0.00 free line, bracket discount, service charge,
        // SST, rounding adjustment, and Visa/Points footer noise.
        val width = 920
        val height = 2150
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(235, 234, 230))
        canvas.save()
        canvas.rotate(3.8f, width / 2f, height / 2f)

        val pHeader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 35)
            textSize = 36f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val pLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 45)
            textSize = 27f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
        }
        val pRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 45)
            textSize = 27f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
        }

        var y = 110f
        canvas.drawText("PAVILION STEAMBOAT HOUSE", width / 2f, y, pHeader)
        y += 55f
        canvas.drawText("Tax Invoice #INV-2026-991", 70f, y, pLeft)
        y += 46f
        canvas.drawText("Table: VIP-8   Pax: 6", 70f, y, pLeft)
        y += 46f
        canvas.drawText("Date: 19/11/2026 20:15", 70f, y, pLeft)
        y += 48f
        canvas.drawText("------------------------------------", 70f, y, pLeft)
        y += 56f

        val singleRows = listOf(
            "Signature Mala Soup Base" to "",
            "2 x 32.00" to "64.00",
            "+ Sliced Pork Belly" to "28.00",
            "+ Enoki Mushroom" to "9.50",
            "+ Free Condiments" to "0.00",
            "Australian Wagyu Beef" to "118.00",
            "Handcrafted Prawn Paste" to "26.50",
            "2x Homemade Barley Jug 14.00" to "28.00",
            "White Rice" to "4.00",
            "------------------------------------" to "",
            "Sub-Total" to "278.00",
            "Total Discount" to "(20.00)",
            "Service Charge 10%" to "27.80",
            "SST 6%" to "16.68",
            "Rounding Adj" to "0.02",
            "GRAND TOTAL" to "RM 302.50",
            "------------------------------------" to "",
            "VISA **** 4321" to "302.50",
            "Member Points Earned: 302.00" to "",
            "Thank You Please Visit Again" to ""
        )

        singleRows.forEach { (left, right) ->
            if (left.isNotEmpty()) canvas.drawText(left, 70f, y, pLeft)
            if (right.isNotEmpty()) canvas.drawText(right, width - 70f, y, pRight)
            y += 72f
        }
        canvas.restore()

        val photosDir = File(context.cacheDir, "receipt_photos").apply { mkdirs() }
        val photoFile = File(photosDir, "test_complex_hotpot.jpg")
        FileOutputStream(photoFile).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photoFile)
        val result = ReceiptOcrParser.parseImageUriAsync(context, uri)
        assertTrue("OCR failed: ${result.exceptionOrNull()?.message}", result.isSuccess)

        val (previewBmp, parsed) = result.getOrThrow()
        previewBmp.recycle()

        // 7 paid items (Signature Mala Soup Base 64 + Pork Belly 28 + Enoki 9.50 + Wagyu 118 + Prawn 26.50 + 2x Barley 28 + White Rice 4)
        assertTrue("Expected 7 items, got ${parsed.items.size}: ${parsed.items} | raw=${parsed.rawLines}", parsed.items.size == 7)
        assertEquals("Expected Service Charge 27.80, lines=${parsed.rawLines}", 27.80, parsed.detectedServiceCharge, 0.05)
        assertEquals("Expected SST 16.68, lines=${parsed.rawLines}", 16.68, parsed.detectedSst, 0.05)
        assertEquals("Expected Adjustment -19.98, lines=${parsed.rawLines}", -19.98, parsed.detectedAdjustment, 0.05)
        assertEquals("Expected Grand Total 302.50, lines=${parsed.rawLines}", 302.50, parsed.detectedTotal, 0.05)
        assertTrue(
            "Expected Tally OK (computed=${parsed.computedTotal}, detected=${parsed.detectedTotal}, diff=${parsed.tallyDiff}), lines=${parsed.rawLines}",
            parsed.isTallied
        )
    }
}
