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
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import android.app.NotificationManager
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.content.pm.PackageManager
import androidx.lifecycle.lifecycleScope
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.AppSelectionActivity
import com.siangloh.ledger.ui.NotificationLogActivity
import com.siangloh.ledger.ui.QuickAddActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineContainer: LinearLayout
    private lateinit var batteryBanner: LinearLayout
    private lateinit var fabQuickAdd: FloatingActionButton
    private lateinit var composeView: androidx.compose.ui.platform.ComposeView

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingReceiptPhotoUri: Uri? = null
    private var pendingScannedReceiptText: String? = null
    private var isWebViewInitialized = false
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "LedgerMainActivity"
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val RECEIPT_CAMERA_REQUEST_CODE = 1002
        private const val RECEIPT_GALLERY_REQUEST_CODE = 1003
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.siangloh.ledger.util.AppLanguageManager.wrapContext(newBase))
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
        composeView = findViewById(R.id.composeView)

        initViews()
        // 关键启动性能优化：默认进入原生 Jetpack Compose 界面，延迟初始化 Chromium WebView 引擎
        // 避免在冷启动主线程同步唤醒重量级 Webview 渲染内核，冷启动耗时立降 ~800-1000ms
        initComposeOverview()
        checkNotificationPermission()
        checkBatteryOptimization()
        cleanLegacyDrivingState()
        setupBackNavigation()

        val sp = getSharedPreferences("ledger_network_prefs", Context.MODE_PRIVATE)
        sp.edit().putString("default_view_mode", "compose").apply()
        showNativeComposeMode()
        handleIncomingIntent(intent)
        registerNetworkCallback()
    }

    private fun ensureWebViewInitialized() {
        if (!isWebViewInitialized) {
            initWebView()
            isWebViewInitialized = true
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getStringExtra("action") == "open_web_add") {
            startActivity(Intent(this, QuickAddActivity::class.java))
        }
    }

    private fun initComposeOverview() {
        composeView.setContent {
            com.siangloh.ledger.ui.compose.theme.LedgerTheme {
                com.siangloh.ledger.ui.compose.MainAppScreen(
                    onScanReceipt = { showReceiptScanChooser() },
                    onOpenSettings = { showAppSettingsDialog() },
                    onOpenWebUrl = { relativeOrAbsoluteUrl ->
                        showWebViewMode()
                        if (relativeOrAbsoluteUrl.startsWith("http")) {
                            webView.loadUrl(relativeOrAbsoluteUrl)
                        } else {
                            val serverUrl = NetworkHelper.getServerUrl(this).trimEnd('/')
                            webView.loadUrl("$serverUrl$relativeOrAbsoluteUrl")
                        }
                    }
                )
            }
        }
    }

    fun showWebViewMode() {
        ensureWebViewInitialized()
        composeView.visibility = View.GONE
        swipeRefreshLayout.visibility = View.VISIBLE
        fabQuickAdd.visibility = View.VISIBLE
        offlineContainer.visibility = View.GONE
        if (webView.url.isNullOrEmpty()) {
            loadContent()
        }
    }

    fun showNativeComposeMode() {
        swipeRefreshLayout.visibility = View.GONE
        fabQuickAdd.visibility = View.GONE
        offlineContainer.visibility = View.GONE
        composeView.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        // 关键省电与内存修复：仅当 WebView 已初始化时挂起定时器与引擎，杜绝后台偷跑 CPU
        if (isWebViewInitialized) {
            webView.onPause()
            webView.pauseTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isWebViewInitialized) {
            webView.onResume()
            webView.resumeTimers()
        }
        tryReconnectNotificationListener()
        // 智能节电同步：避免每次切前台都无脑拉起 WorkManager 唤醒网络
        checkAndEnqueueSyncIfNeeded()
    }

    override fun onDestroy() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
        if (isWebViewInitialized) {
            try {
                webView.stopLoading()
                webView.destroy()
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    @Volatile
    private var lastAutoSyncTriggerTime: Long = 0L

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Log.d(TAG, "Network or WiFi became available, checking for pending sync...")
                    triggerAutoSyncIfPending()
                }

                override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
                    val now = System.currentTimeMillis()
                    if (capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                        capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        (now - lastAutoSyncTriggerTime > 20_000L)) {
                        triggerAutoSyncIfPending()
                    }
                }
            }
            networkCallback = callback
            val request = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    private fun triggerAutoSyncIfPending() {
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncTriggerTime < 15_000L) return
        lastAutoSyncTriggerTime = now
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (!NetworkHelper.canSyncNow(this@MainActivity)) return@launch
                val db = com.siangloh.ledger.data.AppDatabase.getInstance(this@MainActivity)
                val hasPendingTxs = db.pendingTransactionDao().getSyncableTransactions().isNotEmpty()
                val hasPendingNotifs = db.pendingNotificationDao().getPendingNotifications().isNotEmpty()
                if (hasPendingTxs || hasPendingNotifs) {
                    Log.i(TAG, "WiFi/Network connected with pending items. Triggering SyncWorker...")
                    SyncWorker.enqueueSync(this@MainActivity)
                }
            } catch (e: Exception) {
                Log.w(TAG, "triggerAutoSyncIfPending error: ${e.message}")
            }
        }
    }

    private fun checkAndEnqueueSyncIfNeeded() {
        if (!NetworkHelper.isOnline(this)) return
        val sp = getSharedPreferences("ledger_sync_prefs", Context.MODE_PRIVATE)
        val lastSync = sp.getLong("last_auto_sync_time", 0L)
        val now = System.currentTimeMillis()
        // 只有距离上次同步超过 15 分钟才进行定期同步
        if (now - lastSync > 15 * 60 * 1000L) {
            sp.edit().putLong("last_auto_sync_time", now).apply()
            SyncWorker.enqueueSync(this)
        } else {
            // 短时间反复切入前台：仅当本地确实有待同步离线数据时才拉起后台任务
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val db = com.siangloh.ledger.data.AppDatabase.getInstance(this@MainActivity)
                    val hasPendingTxs = db.pendingTransactionDao().getPendingTransactions().isNotEmpty()
                    val hasPendingNotifs = db.pendingNotificationDao().getPendingNotifications().isNotEmpty()
                    if (hasPendingTxs || hasPendingNotifs) {
                        sp.edit().putLong("last_auto_sync_time", now).apply()
                        SyncWorker.enqueueSync(this@MainActivity)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun initViews() {
        // 电池优化横幅按键
        findViewById<Button>(R.id.btnFixBattery).setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
        findViewById<ImageButton>(R.id.btnCloseBatteryBanner).setOnClickListener {
            batteryBanner.visibility = View.GONE
            getSharedPreferences("ledger_overview_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("battery_banner_dismissed", true).apply()
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
        findViewById<Button>(R.id.btnOfflineOverview)?.setOnClickListener {
            showNativeComposeMode()
        }
        findViewById<Button>(R.id.btnOfflineRetry).setOnClickListener {
            loadContent()
        }

        // FAB 快捷键与平滑防误触随手拖拽
        fabQuickAdd.setOnClickListener {
            startActivity(Intent(this, QuickAddActivity::class.java))
        }
        fabQuickAdd.setOnLongClickListener {
            showQuickMenu()
            true
        }
        setupDraggableFab()
        fabQuickAdd.visibility = View.VISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggableFab() {
        var dX = 0f
        var dY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        fabQuickAdd.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = Math.abs(event.rawX - startX)
                    val deltaY = Math.abs(event.rawY - startY)
                    if (deltaX > touchSlop || deltaY > touchSlop) {
                        isDragging = true
                        val parent = view.parent as? View ?: return@setOnTouchListener false
                        val newX = (event.rawX + dX).coerceIn(0f, (parent.width - view.width).toFloat())
                        val newY = (event.rawY + dY).coerceIn(0f, (parent.height - view.height).toFloat())
                        view.x = newX
                        view.y = newY
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        val parent = view.parent as? View
                        if (parent != null) {
                            val targetX = if (view.x + view.width / 2 > parent.width / 2) {
                                (parent.width - view.width - 32).toFloat()
                            } else {
                                32f
                            }
                            view.animate().x(targetX).setDuration(180).start()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun loadContent() {
        if (NetworkHelper.isOnline(this)) {
            offlineContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(NetworkHelper.getServerUrl(this))
            checkAndEnqueueSyncIfNeeded()
        } else {
            offlineContainer.visibility = View.VISIBLE
            webView.visibility = View.GONE
            Toast.makeText(this, "当前处于离线模式，可使用快速记账", Toast.LENGTH_SHORT).show()
        }
        fabQuickAdd.visibility = View.VISIBLE
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.userAgentString = "${settings.userAgentString} LedgerAppNative/${BuildConfig.VERSION_NAME}"
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.allowFileAccess = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        val currentAppLang = com.siangloh.ledger.util.AppLanguageManager.getLanguage(this)
        cookieManager.setCookie(NetworkHelper.getServerUrl(this), "lang=$currentAppLang; path=/")

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun isNativeApp(): Boolean = true
            @JavascriptInterface
            fun getVersion(): String = BuildConfig.VERSION_NAME
            @JavascriptInterface
            fun setCurrentUser(username: String) {
                if (username.isNotBlank()) {
                    NetworkHelper.setUsername(this@MainActivity, username)
                    android.util.Log.i("LedgerNativeBridge", "Bound native user to: $username")
                }
            }
            // 供 Web 端在需要时呼起原生设置与通知记录（无需常驻悬浮 + 按钮遮挡屏幕）
            @JavascriptInterface
            fun openQuickMenu() {
                runOnUiThread { showQuickMenu() }
            }
            @JavascriptInterface
            fun openNotificationLogs() {
                runOnUiThread { startActivity(Intent(this@MainActivity, NotificationLogActivity::class.java)) }
            }
            @JavascriptInterface
            fun openSettings() {
                runOnUiThread { startActivity(Intent(this@MainActivity, AppSelectionActivity::class.java)) }
            }
            @JavascriptInterface
            fun openQuickAdd() {
                runOnUiThread { startActivity(Intent(this@MainActivity, QuickAddActivity::class.java)) }
            }
            @JavascriptInterface
            fun switchToNativeOverview() {
                runOnUiThread { showNativeComposeMode() }
            }
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

                // 主动从当前页面嗅探已登录用户名并同步给原生常驻服务，确保多设备/多账户 100% 独立绑定
                view?.evaluateJavascript("(function() { return document.querySelector('meta[name=\"current-username\"]')?.getAttribute('content') || ''; })()") { rawResult ->
                    val user = rawResult?.replace("\"", "")?.trim() ?: ""
                    if (user.isNotBlank() && user != "null" && user != "None") {
                        val current = NetworkHelper.getUsername(this@MainActivity)
                        if (current != user) {
                            NetworkHelper.setUsername(this@MainActivity, user)
                            Log.i("LedgerNativeBridge", "Auto-synced native user from page: $user (was: $current)")
                        }
                    }
                }

                // U1: 若有在途未送出的小票识别文本，且当前页面已加载完毕 /split-bill，即刻推送到页面
                if (pendingScannedReceiptText != null && (url?.contains("/split-bill") == true)) {
                    val textToSend = pendingScannedReceiptText!!
                    pendingScannedReceiptText = null
                    val jsSafeText = JSONObject.quote(textToSend)
                    val js = "javascript:(function(){ " +
                        "if (typeof receiveScannedReceiptText === 'function') { " +
                        "  receiveScannedReceiptText($jsSafeText); " +
                        "} else { " +
                        "  window._pendingScannedReceiptText = $jsSafeText; " +
                        "} " +
                        "})();"
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.w("LedgerWebView", "WebView load error: $errorCode - $description on $failingUrl")
                view?.stopLoading()
                showNativeComposeMode()
                Toast.makeText(this@MainActivity, "云端连接较慢，已为你切换至本地原生极速模式", Toast.LENGTH_SHORT).show()
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.w("LedgerWebView", "Main frame load error: ${error?.description}")
                    view?.stopLoading()
                    showNativeComposeMode()
                    Toast.makeText(this@MainActivity, "云端连接较慢，已为你切换至本地原生极速模式", Toast.LENGTH_SHORT).show()
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
            val sp = getSharedPreferences("ledger_overview_prefs", Context.MODE_PRIVATE)
            val dismissed = sp.getBoolean("battery_banner_dismissed", false)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (!dismissed && pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
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

    fun showAppSettingsDialog() {
        val currentBound = NetworkHelper.getUsername(this).ifEmpty { "未绑定(默认admin)" }
        val currentServer = NetworkHelper.getServerUrl(this)
        val currentLang = com.siangloh.ledger.util.AppLanguageManager.getLanguage(this)
        val langLabel = when (currentLang) {
            "en" -> "English"
            "ms" -> "Bahasa Melayu"
            else -> "中文 (简体)"
        }
        val sp = getSharedPreferences("ledger_network_prefs", Context.MODE_PRIVATE)
        val defaultMode = sp.getString("default_view_mode", "compose") ?: "compose"
        val modeText = if (defaultMode == "web") "🏛️ 全功能账本大厅" else "📊 极简原生视图"
        val isWifiOnly = NetworkHelper.isWifiOnlySync(this)
        val syncScopeLabel = if (isWifiOnly) "仅在 WiFi 下自动同步" else "任何网络均可自动同步"
        val options = arrayOf(
            "🏛️ 切换至全功能账本大厅",
            "📊 切换至极简原生视图",
            "📱 默认启动视图设置 (当前: $modeText)",
            "🌐 界面语言设置 (当前: $langLabel)",
            "📶 自动同步网络设置 (当前: $syncScopeLabel)",
            "⚡ 离线快速记账",
            "👤 绑定记账用户 (当前: $currentBound)",
            "🌐 服务器地址设置 (当前: $currentServer)",
            "🔑 自动记账 API Key 设置",
            "⚙️ 监测的应用设置",
            "📋 查看通知与同步日志",
            "🔄 立即执行后台同步"
        )
        AlertDialog.Builder(this)
            .setTitle("⚙️ 应用设置与快捷操作")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showWebViewMode()
                    1 -> showNativeComposeMode()
                    2 -> showDefaultViewModeDialog()
                    3 -> showLanguageDialog()
                    4 -> {
                        val newSetting = !isWifiOnly
                        NetworkHelper.setWifiOnlySync(this, newSetting)
                        SyncWorker.enqueueSync(this)
                        val notice = if (newSetting) "已开启【仅在 WiFi 下自动同步】\n离线或移动网络下将安全保存在手机，连上 WiFi 时自动上传。" else "已切换为【任何网络均可自动同步】"
                        Toast.makeText(this, notice, Toast.LENGTH_LONG).show()
                    }
                    5 -> startActivity(Intent(this, QuickAddActivity::class.java))
                    6 -> showUsernameDialog()
                    7 -> showServerUrlDialog()
                    8 -> showApiKeyDialog()
                    9 -> startActivity(Intent(this, AppSelectionActivity::class.java))
                    10 -> startActivity(Intent(this, NotificationLogActivity::class.java))
                    11 -> {
                        SyncWorker.enqueueSync(this)
                        Toast.makeText(this, "已发起后台同步请求", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showLanguageDialog() {
        val currentLang = com.siangloh.ledger.util.AppLanguageManager.getLanguage(this)
        val langs = arrayOf("🇨🇳 中文 (简体)", "🇬🇧 English", "🇲🇾 Bahasa Melayu")
        val codes = arrayOf(com.siangloh.ledger.util.AppLanguageManager.LANG_ZH, com.siangloh.ledger.util.AppLanguageManager.LANG_EN, com.siangloh.ledger.util.AppLanguageManager.LANG_MS)
        val currentIdx = codes.indexOf(currentLang).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.language_select_dialog_title)
            .setSingleChoiceItems(langs, currentIdx) { dialog, which ->
                val chosen = codes[which]
                com.siangloh.ledger.util.AppLanguageManager.setLanguage(this, chosen)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showQuickMenu() = showAppSettingsDialog()

    private fun showDefaultViewModeDialog() {
        val sp = getSharedPreferences("ledger_network_prefs", Context.MODE_PRIVATE)
        val current = sp.getString("default_view_mode", "compose") ?: "compose"
        val modes = arrayOf("📊 极简原生视图 (推荐)", "🏛️ 全功能账本大厅")
        val currentIdx = if (current == "web") 1 else 0

        AlertDialog.Builder(this)
            .setTitle("📱 选择打开应用时的默认视图")
            .setSingleChoiceItems(modes, currentIdx) { dialog, which ->
                val chosen = if (which == 1) "web" else "compose"
                sp.edit().putString("default_view_mode", chosen).apply()
                Toast.makeText(this, "默认启动视图已设置为：${modes[which]}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun cleanLegacyDrivingState() {
        try {
            getSharedPreferences("ledger_driving_prefs", Context.MODE_PRIVATE).edit().clear().apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.deleteNotificationChannel("channel_driving_tracker")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Legacy driving state cleanup: ${e.message}")
        }
    }

    private fun showUsernameDialog() {
        val current = NetworkHelper.getUsername(this)
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = "例如：admin、user_a"
        }
        AlertDialog.Builder(this)
            .setTitle("👤 绑定记账用户名")
            .setMessage("设置当前手机抓取通知时归属的记账用户（在 Web 端登录时会自动同步，也可在此手动指定）：")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                NetworkHelper.setUsername(this, newName)
                Toast.makeText(this, if (newName.isEmpty()) "已清空绑定，将使用默认用户" else "已绑定记账用户：$newName", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空绑定") { _, _ ->
                NetworkHelper.setUsername(this, "")
                Toast.makeText(this, "已恢复为默认用户", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showServerUrlDialog() {
        val currentUrl = NetworkHelper.getServerUrl(this)
        val presets = arrayOf(
            "☁️ 云端生产服务器 (Render)\nhttps://ledger-app-l3hc.onrender.com",
            "🏠 本地局域网 (Wi-Fi 局域网)\nhttp://192.168.100.85:5000",
            "🔌 USB 反向代理 (端口映射)\nhttp://127.0.0.1:5000",
            "✏️ 自定义输入服务器地址..."
        )
        AlertDialog.Builder(this)
            .setTitle("🌐 服务器地址切换与设置\n(当前: $currentUrl)")
            .setItems(presets) { _, which ->
                when (which) {
                    0 -> {
                        NetworkHelper.setServerUrl(this, "https://ledger-app-l3hc.onrender.com")
                        Toast.makeText(this, "已切换为云端服务器并重新加载", Toast.LENGTH_SHORT).show()
                        loadContent()
                    }
                    1 -> {
                        NetworkHelper.setServerUrl(this, "http://192.168.100.85:5000")
                        Toast.makeText(this, "已切换为局域网服务器并重新加载", Toast.LENGTH_SHORT).show()
                        loadContent()
                    }
                    2 -> {
                        NetworkHelper.setServerUrl(this, "http://127.0.0.1:5000")
                        Toast.makeText(this, "已切换为 USB 代理服务器并重新加载", Toast.LENGTH_SHORT).show()
                        loadContent()
                    }
                    3 -> showCustomServerUrlInput(currentUrl)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomServerUrlInput(currentUrl: String) {
        val input = android.widget.EditText(this).apply {
            setText(currentUrl)
            setSelection(text.length)
            hint = NetworkHelper.DEFAULT_BASE_URL
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ 自定义服务器地址")
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

    private fun showApiKeyDialog() {
        val input = android.widget.EditText(this).apply {
            setText(NetworkHelper.getApiKey(this@MainActivity))
            setSelection(text.length)
            hint = NetworkHelper.DEFAULT_API_KEY
        }
        AlertDialog.Builder(this)
            .setTitle("🔑 自动记账 API Key 设置")
            .setMessage("请确保与账本后台「Auto Track 自动记账」页面显示的 API Key 一致：")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newKey = input.text.toString().trim()
                NetworkHelper.setApiKey(this, newKey)
                Toast.makeText(this, "API Key 已更新", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("恢复默认") { _, _ ->
                NetworkHelper.setApiKey(this, "")
                Toast.makeText(this, "已恢复为默认 API Key", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun tryReconnectNotificationListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isNotificationServiceEnabled()) {
            try {
                android.service.notification.NotificationListenerService.requestRebind(
                    ComponentName(this, NotificationService::class.java)
                )
            } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------------
    // 小票拍照识别 (Google ML Kit，完全离线，本地处理，不上传图片)
    // ---------------------------------------------------------------------

    private fun showReceiptScanChooser() {
        val options = arrayOf("📷 拍照识别小票", "🖼️ 从相册选择小票照片")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
                    showReceiptResultDialog(visionText.text)
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "识别失败：${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showReceiptResultDialog(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val preview = lines.take(6).joinToString("\n")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🧾 小票识别完成")
            .setMessage("已识别 ${lines.size} 行小票内容：\n\n$preview\n${if (lines.size > 6) "..." else ""}")
            .setPositiveButton("✨ 前往 AA 分账") { _, _ ->
                sendRecognizedTextToWebView(text)
            }
            .setNeutralButton("⚡ 快速记一笔") { _, _ ->
                val amountRegex = Regex("""(?:RM|MYR|TOTAL|AMOUNT|SUBTOTAL)?\s*([0-9]+\.[0-9]{2})""", RegexOption.IGNORE_CASE)
                val detectedAmount = lines.asReversed().mapNotNull { line ->
                    amountRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                }.firstOrNull() ?: 0.0

                val note = lines.firstOrNull()?.take(30) ?: "小票消费"
                val intent = Intent(this, QuickAddActivity::class.java).apply {
                    if (detectedAmount > 0) putExtra("extra_amount", detectedAmount)
                    putExtra("extra_note", note)
                }
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sendRecognizedTextToWebView(text: String) {
        showWebViewMode()
        val curUrl = webView.url ?: ""
        val splitBillUrl = NetworkHelper.getServerUrl(this).trimEnd('/') + "/split-bill"

        if (!curUrl.contains("/split-bill")) {
            pendingScannedReceiptText = text
            webView.loadUrl(splitBillUrl)
            return
        }

        // JSONObject.quote() 会把字符串安全地转成带引号、已转义的 JS 字符串字面量，
        // 避免小票文字里如果含有引号/换行导致注入到页面的 JS 语法出错。
        val jsSafeText = JSONObject.quote(text)
        val js = "javascript:(function(){ " +
            "if (typeof receiveScannedReceiptText === 'function') { " +
            "  receiveScannedReceiptText($jsSafeText); " +
            "} else { " +
            "  window._pendingScannedReceiptText = $jsSafeText; " +
            "} " +
            "})();"
        webView.evaluateJavascript(js, null)
    }

    private var lastBackPressedTime = 0L

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (composeView.visibility != View.VISIBLE) {
                    if (isWebViewInitialized && webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        showNativeComposeMode()
                    }
                    return
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastBackPressedTime < 2000L) {
                    finish()
                } else {
                    lastBackPressedTime = currentTime
                    Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
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
