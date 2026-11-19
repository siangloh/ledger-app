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
}
