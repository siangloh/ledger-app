package com.siangloh.ledger

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.AppSelectionActivity
import com.siangloh.ledger.ui.NotificationLogActivity
import com.siangloh.ledger.ui.QuickAddActivity
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineContainer: LinearLayout
    private lateinit var batteryBanner: LinearLayout
    private lateinit var fabQuickAdd: FloatingActionButton

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingReceiptPhotoUri: Uri? = null

    companion object {
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val RECEIPT_CAMERA_REQUEST_CODE = 1002
        private const val RECEIPT_GALLERY_REQUEST_CODE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        offlineContainer = findViewById(R.id.offlineContainer)
        batteryBanner = findViewById(R.id.batteryBanner)
        fabQuickAdd = findViewById(R.id.fabQuickAdd)

        initViews()
        initWebView()
        checkNotificationPermission()
        checkBatteryOptimization()
        setupBackNavigation()

        loadContent()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时，如果有网络则触发一次后台同步
        if (NetworkHelper.isOnline(this)) {
            SyncWorker.enqueueSync(this)
        }
    }

    private fun initViews() {
        // 电池优化横幅按键
        findViewById<Button>(R.id.btnFixBattery).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
        findViewById<ImageButton>(R.id.btnCloseBatteryBanner).setOnClickListener {
            batteryBanner.visibility = View.GONE
        }

        // 离线视图按键
        findViewById<Button>(R.id.btnOfflineQuickAdd).setOnClickListener {
            startActivity(Intent(this, QuickAddActivity::class.java))
        }
        findViewById<Button>(R.id.btnOfflineLogs).setOnClickListener {
            startActivity(Intent(this, NotificationLogActivity::class.java))
        }
        findViewById<Button>(R.id.btnOfflineSettings).setOnClickListener {
            startActivity(Intent(this, AppSelectionActivity::class.java))
        }
        findViewById<Button>(R.id.btnOfflineRetry).setOnClickListener {
            loadContent()
        }

        // FAB 快捷键
        fabQuickAdd.setOnClickListener {
            startActivity(Intent(this, QuickAddActivity::class.java))
        }
        fabQuickAdd.setOnLongClickListener {
            showQuickMenu()
            true
        }
    }

    private fun loadContent() {
        if (NetworkHelper.isOnline(this)) {
            offlineContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            fabQuickAdd.visibility = View.VISIBLE
            webView.loadUrl(NetworkHelper.getServerUrl(this))
            SyncWorker.enqueueSync(this)
        } else {
            offlineContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            fabQuickAdd.visibility = View.GONE
            Toast.makeText(this, "当前处于离线模式，可使用快速记账", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "${settings.userAgentString} LedgerAppNative/1.0"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun isNativeApp(): Boolean = true
            @JavascriptInterface
            fun getVersion(): String = "1.0.0"
            // 小票拍照识别入口：由 split_bill.html 侦测到自己跑在原生 App 内时调用。
            // 识别完全在手机本地用 ML Kit 完成，不会把照片传去任何服务器。
            @JavascriptInterface
            fun scanReceipt() {
                runOnUiThread { showReceiptScanChooser() }
            }
        }, "LedgerNativeBridge")

        swipeRefreshLayout.setColorSchemeResources(R.color.gold_accent, R.color.navy_primary)
        swipeRefreshLayout.setOnRefreshListener {
            if (NetworkHelper.isOnline(this)) {
                webView.reload()
            } else {
                swipeRefreshLayout.isRefreshing = false
                loadContent()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefreshLayout.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                if (!NetworkHelper.isOnline(this@MainActivity)) {
                    offlineContainer.visibility = View.VISIBLE
                    webView.visibility = View.GONE
                    fabQuickAdd.visibility = View.GONE
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: Exception) {
                    fileChooserCallback = null
                    return false
                }
                return true
            }
        }
    }

    private fun checkNotificationPermission() {
        if (!isNotificationServiceEnabled()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_dialog_title)
                .setMessage(R.string.permission_dialog_msg)
                .setPositiveButton(R.string.permission_dialog_ok) { _, _ ->
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    startActivity(intent)
                }
                .setNegativeButton(R.string.permission_dialog_cancel, null)
                .show()
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            val names = flat.split(":").map { it.trim() }
            val cn = ComponentName(this, NotificationService::class.java).flattenToString()
            return names.any { it.equals(cn, ignoreCase = true) || it.contains(pkgName) }
        }
        return false
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                batteryBanner.visibility = View.VISIBLE
            } else {
                batteryBanner.visibility = View.GONE
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(this, "请在系统设置 -> 应用管理 -> 电池优化中允许后台运行", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showQuickMenu() {
        val options = arrayOf("⚡ 离线快速记账", "⚙️ 监测的应用设置", "📋 查看通知与同步日志", "🔄 手动同步", "🌐 服务器地址设置")
        AlertDialog.Builder(this)
            .setTitle("快捷菜单")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, QuickAddActivity::class.java))
                    1 -> startActivity(Intent(this, AppSelectionActivity::class.java))
                    2 -> startActivity(Intent(this, NotificationLogActivity::class.java))
                    3 -> {
                        SyncWorker.enqueueSync(this)
                        Toast.makeText(this, "已发起后台同步请求", Toast.LENGTH_SHORT).show()
                    }
                    4 -> showServerUrlDialog()
                }
            }
            .show()
    }

    private fun showServerUrlDialog() {
        val input = android.widget.EditText(this).apply {
            setText(NetworkHelper.getServerUrl(this@MainActivity))
            setSelection(text.length)
            hint = NetworkHelper.DEFAULT_BASE_URL
        }
        AlertDialog.Builder(this)
            .setTitle("🌐 服务器地址设置")
            .setMessage("如云端服务器域名变更，可在此修改服务器地址：")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newUrl = input.text.toString().trim()
                NetworkHelper.setServerUrl(this, newUrl)
                Toast.makeText(this, "服务器地址已更新并重新加载", Toast.LENGTH_SHORT).show()
                loadContent()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                NetworkHelper.setServerUrl(this, "")
                Toast.makeText(this, "已恢复为默认服务器地址", Toast.LENGTH_SHORT).show()
                loadContent()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------------------------------------------------------------
    // 小票拍照识别 (Google ML Kit，完全离线，本地处理，不上传图片)
    // ---------------------------------------------------------------------

    private fun showReceiptScanChooser() {
        val options = arrayOf("📷 拍照识别小票", "🖼️ 从相册选择小票照片")
        AlertDialog.Builder(this)
            .setTitle("小票拍照识别")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        if (intent.resolveActivity(packageManager) != null) {
                            try {
                                val photoFile = createTempReceiptPhotoFile()
                                val photoUri = FileProvider.getUriForFile(
                                    this,
                                    "$packageName.fileprovider",
                                    photoFile
                                )
                                pendingReceiptPhotoUri = photoUri
                                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                                // 授权相机 App 写入这个 Uri（否则某些相机 App 会因为没权限写入而拍照失败）
                                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                startActivityForResult(intent, RECEIPT_CAMERA_REQUEST_CODE)
                            } catch (e: Exception) {
                                Toast.makeText(this, "无法准备拍照文件：${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this, "未找到可用的相机应用", Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                        startActivityForResult(intent, RECEIPT_GALLERY_REQUEST_CODE)
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 在 cache/receipt_photos/ 底下建一个临时文件，给相机 App 写入全尺寸照片用。 */
    private fun createTempReceiptPhotoFile(): File {
        val dir = File(cacheDir, "receipt_photos").apply { mkdirs() }
        return File(dir, "receipt_${System.currentTimeMillis()}.jpg")
    }

    /**
     * 从 Uri 读取图片，并依照 EXIF 方向自动纠正旋转，同时把长边限制在 maxDimension 以内，
     * 避免现代手机相机动辄 4000万像素的全尺寸照片直接整张解码，造成 OOM 或识别耗时过久。
     */
    private fun loadOrientedDownsampledBitmap(uri: Uri, maxDimension: Int = 1600): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }
            var sampleSize = 1
            while (boundsOptions.outWidth / sampleSize > maxDimension || boundsOptions.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            val rotationDegrees = try {
                contentResolver.openInputStream(uri)?.use { input ->
                    when (ExifInterface(input).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                } ?: 0
            } catch (_: Exception) {
                0
            }

            if (rotationDegrees == 0) {
                rawBitmap
            } else {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun recognizeReceiptText(bitmap: Bitmap) {
        Toast.makeText(this, "正在本地识别小票文字...", Toast.LENGTH_SHORT).show()
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                if (visionText.text.isBlank()) {
                    Toast.makeText(this, "未识别到文字，请换一张更清晰的照片再试", Toast.LENGTH_LONG).show()
                } else {
                    sendRecognizedTextToWebView(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "识别失败：${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun sendRecognizedTextToWebView(text: String) {
        // JSONObject.quote() 会把字符串安全地转成带引号、已转义的 JS 字符串字面量，
        // 避免小票文字里如果含有引号/换行导致注入到页面的 JS 语法出错。
        val jsSafeText = JSONObject.quote(text)
        val js = "javascript:(function(){ " +
            "if (typeof receiveScannedReceiptText === 'function') { receiveScannedReceiptText($jsSafeText); } " +
            "})();"
        webView.evaluateJavascript(js, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            FILE_CHOOSER_REQUEST_CODE -> {
                if (fileChooserCallback != null) {
                    val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                    fileChooserCallback?.onReceiveValue(results)
                    fileChooserCallback = null
                }
            }
            RECEIPT_CAMERA_REQUEST_CODE -> {
                val photoUri = pendingReceiptPhotoUri
                pendingReceiptPhotoUri = null
                if (resultCode == Activity.RESULT_OK && photoUri != null) {
                    // 这里读的是相机 App 写进 FileProvider Uri 的全尺寸照片，
                    // 不再是 data extras 里那张画质很差的缩略图。
                    val bitmap = loadOrientedDownsampledBitmap(photoUri)
                    if (bitmap != null) {
                        recognizeReceiptText(bitmap)
                    } else {
                        Toast.makeText(this, "拍照失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                    // 清理临时照片文件，不留在手机存储里
                    try {
                        contentResolver.delete(photoUri, null, null)
                    } catch (_: Exception) {
                    }
                } else if (resultCode != Activity.RESULT_OK) {
                    // 用户取消拍照，同样清掉预先建好的临时文件
                    photoUri?.let {
                        try { contentResolver.delete(it, null, null) } catch (_: Exception) {}
                    }
                }
            }
            RECEIPT_GALLERY_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) {
                        val bitmap = loadOrientedDownsampledBitmap(uri)
                        if (bitmap != null) {
                            recognizeReceiptText(bitmap)
                        } else {
                            Toast.makeText(this, "读取图片失败，请换一张照片再试", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
}
