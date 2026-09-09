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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.siangloh.ledger.R

class AppSelectionActivity : AppCompatActivity() {

    data class AppItem(
        val name: String,
        val packageName: String,
        val icon: Drawable?,
        var isMonitored: Boolean
    )

    private lateinit var recyclerView: RecyclerView
    private lateinit var searchEditText: EditText
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
        val pm = packageManager
        val monitoredSet = AppSelectionManager.getMonitoredSet(this)
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        allApps.clear()
        for (app in installed) {
            // 只保留具有启动图标的应用或属于已知银行关键字的应用，过滤纯底层系统服务
            val isKnown = AppSelectionManager.KNOWN_BANK_KEYWORDS.any { app.packageName.lowercase().contains(it) }
            val hasLaunchIntent = pm.getLaunchIntentForPackage(app.packageName) != null
            if (hasLaunchIntent || isKnown) {
                val label = pm.getApplicationLabel(app).toString()
                val icon = try { pm.getApplicationIcon(app) } catch (_: Exception) { null }
                val isMonitored = monitoredSet.contains(app.packageName)
                allApps.add(AppItem(label, app.packageName, icon, isMonitored))
            }
        }

        // 优先将已开启监控的应用排在前面，其次按名称排序
        allApps.sortWith(compareByDescending<AppItem> { it.isMonitored }.thenBy { it.name.lowercase() })
        displayedApps.clear()
        displayedApps.addAll(allApps)
        adapter.notifyDataSetChanged()
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
