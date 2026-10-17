package com.siangloh.ledger.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.siangloh.ledger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : AppCompatActivity() {

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: Drawable?,
        var isMonitored: Boolean
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEditText: EditText
    private lateinit var loadingProgressBar: View
    private val allApps = mutableListOf<AppItem>()
    private val displayedApps = mutableListOf<AppItem>()
    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        recyclerView = findViewById(R.id.appsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter()
        recyclerView.adapter = adapter

        searchEditText = findViewById(R.id.searchEditText)
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                filterApps(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        loadingProgressBar.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            val loadedItems = withContext(Dispatchers.IO) {
                val pm = packageManager
                val monitoredSet = AppSelectionManager.getMonitoredSet(this@AppSelectionActivity)

                // 性能优化：优先只查询具有桌面图标的 App，从原本 300+ 底层系统应用中秒级提取 40~60 个常用 App
                val launcherIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                    addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                }
                val launcherResolveInfos = pm.queryIntentActivities(launcherIntent, 0)
                val appMap = mutableMapOf<String, AppItem>()

                for (info in launcherResolveInfos) {
                    val pkg = info.activityInfo.packageName
                    if (pkg == packageName) continue // 排除自己
                    try {
                        val label = info.loadLabel(pm).toString()
                        val icon = info.loadIcon(pm)
                        val isMonitored = monitoredSet.contains(pkg)
                        appMap[pkg] = AppItem(label, pkg, icon, isMonitored)
                    } catch (_: Exception) {}
                }

                // 额外检查已知银行关键字应用（以防某些银行通知仅以 Service 形式存在）
                try {
                    val allInstalled = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    for (app in allInstalled) {
                        val pkg = app.packageName
                        if (appMap.containsKey(pkg) || pkg == packageName) continue
                        val isKnown = AppSelectionManager.KNOWN_BANK_KEYWORDS.any { pkg.lowercase().contains(it) }
                        if (isKnown) {
                            try {
                                val label = pm.getApplicationLabel(app).toString()
                                val icon = pm.getApplicationIcon(app)
                                val isMonitored = monitoredSet.contains(pkg)
                                appMap[pkg] = AppItem(label, pkg, icon, isMonitored)
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}

                appMap.values.sortedWith(
                    compareByDescending<AppItem> { it.isMonitored }.thenBy { it.name.lowercase() }
                )
            }

            allApps.clear()
            allApps.addAll(loadedItems)
            displayedApps.clear()
            displayedApps.addAll(allApps)

            loadingProgressBar.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }
    }

    private fun filterApps(query: String) {
        val q = query.trim().lowercase()
        displayedApps.clear()
        if (q.isEmpty()) {
            displayedApps.addAll(allApps)
        } else {
            for (app in allApps) {
                if (app.name.lowercase().contains(q) || app.packageName.lowercase().contains(q)) {
                    displayedApps.add(app)
                }
            }
        }
        adapter.notifyDataSetChanged()
    }

    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPackage)
            val switchBtn: SwitchCompat = v.findViewById(R.id.appSwitch)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app_selection, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = displayedApps.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = displayedApps[position]
            holder.name.text = item.name
            holder.pkg.text = item.packageName
            if (item.icon != null) {
                holder.icon.setImageDrawable(item.icon)
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.switchBtn.setOnCheckedChangeListener(null)
            holder.switchBtn.isChecked = item.isMonitored
            holder.switchBtn.setOnCheckedChangeListener { _, isChecked ->
                item.isMonitored = isChecked
                AppSelectionManager.setAppMonitored(this@AppSelectionActivity, item.packageName, isChecked)
            }
        }
    }
}
