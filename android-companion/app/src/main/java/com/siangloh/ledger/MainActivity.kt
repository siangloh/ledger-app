package com.siangloh.ledger

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.siangloh.ledger.sync.SyncWorker
import com.siangloh.ledger.ui.AppSelectionActivity
import com.siangloh.ledger.ui.NotificationLogActivity
import com.siangloh.ledger.ui.QuickAddActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var offlineContainer: LinearLayout
    private lateinit var batteryBanner: LinearLayout
    private lateinit var fabQuickAdd: FloatingActionButton

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val LEDGER_URL = "https://ledger-app-l3hc.onrender.com"
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
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
            webView.loadUrl(LEDGER_URL)
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
        val options = arrayOf("⚡ 离线快速记账", "⚙️ 监测的应用设置", "📋 查看通知与同步日志", "🔄 手动同步")
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
                }
            }
            .show()
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
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileChooserCallback != null) {
                val results = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
                fileChooserCallback?.onReceiveValue(results)
                fileChooserCallback = null
            }
        }
    }
}
