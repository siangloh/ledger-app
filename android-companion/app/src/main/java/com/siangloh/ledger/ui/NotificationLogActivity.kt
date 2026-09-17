package com.siangloh.ledger.ui

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.entities.NotificationLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class NotificationLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private val logs = mutableListOf<NotificationLog>()
    private lateinit var adapter: LogAdapter
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notification_log)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        updateToolbarSubtitle()

        recyclerView = findViewById(R.id.logRecyclerView)
        emptyView = findViewById(R.id.emptyLogView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter()
        recyclerView.adapter = adapter

        loadLogs()
    }

    private fun updateToolbarSubtitle() {
        val current = com.siangloh.ledger.NetworkHelper.getUsername(this)
        supportActionBar?.subtitle = if (current.isEmpty()) "当前绑定：默认 admin" else "当前绑定：$current"
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menu?.add(0, 101, 0, "👤 绑定用户")?.apply {
            setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == 101) {
            showUsernameDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showUsernameDialog() {
        val current = com.siangloh.ledger.NetworkHelper.getUsername(this)
        val input = android.widget.EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = "例如：admin、user_b"
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("👤 绑定记账用户名")
            .setMessage("设置当前手机抓取通知时归属的记账账号（多端隔离记账）：")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                com.siangloh.ledger.NetworkHelper.setUsername(this, newName)
                updateToolbarSubtitle()
                android.widget.Toast.makeText(
                    this,
                    if (newName.isEmpty()) "已恢复为默认 admin 账号" else "已成功绑定账号：$newName",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("清空(默认admin)") { _, _ ->
                com.siangloh.ledger.NetworkHelper.setUsername(this, "")
                updateToolbarSubtitle()
                android.widget.Toast.makeText(this, "已恢复为默认 admin 账号", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun loadLogs() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@NotificationLogActivity)
            val result = withContext(Dispatchers.IO) {
                db.notificationLogDao().getRecentLogs()
            }
            logs.clear()
            logs.addAll(result)
            adapter.notifyDataSetChanged()

            emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    inner class LogAdapter : RecyclerView.Adapter<LogAdapter.ViewHolder>() {
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val pkg: TextView = v.findViewById(R.id.logPackage)
            val time: TextView = v.findViewById(R.id.logTime)
            val badge: TextView = v.findViewById(R.id.logOutcomeBadge)
            val verdict: TextView = v.findViewById(R.id.logVerdict)
            val rawText: TextView = v.findViewById(R.id.logRawText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_log, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount(): Int = logs.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = logs[position]
            holder.pkg.text = item.sourcePackage
            holder.time.text = timeFormat.format(Date(item.timestamp))
            holder.rawText.text = item.rawText

            when (item.outcome) {
                "synced_success" -> {
                    holder.badge.text = "✓ 已成功入账"
                    holder.badge.setTextColor(Color.parseColor("#1B5E20"))
                    holder.badge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                }
                "synced_rejected_promo" -> {
                    holder.badge.text = "🚫 营销广告已过滤 (LLM)"
                    holder.badge.setTextColor(Color.parseColor("#B71C1C"))
                    holder.badge.setBackgroundColor(Color.parseColor("#FFEBEE"))
                }
                "ignored_promo_keyword" -> {
                    holder.badge.text = "🚫 营销词过滤 (本地 Phase-1)"
                    holder.badge.setTextColor(Color.parseColor("#E65100"))
                    holder.badge.setBackgroundColor(Color.parseColor("#FFF3E0"))
                }
                "queued_offline" -> {
                    holder.badge.text = "⏳ 离线已排队"
                    holder.badge.setTextColor(Color.parseColor("#0D47A1"))
                    holder.badge.setBackgroundColor(Color.parseColor("#E3F2FD"))
                }
                "ignored_no_keywords" -> {
                    holder.badge.text = "⚪ 未识别到金额/交易"
                    holder.badge.setTextColor(Color.parseColor("#616161"))
                    holder.badge.setBackgroundColor(Color.parseColor("#EEEEEE"))
                }
                else -> {
                    holder.badge.text = item.outcome
                    holder.badge.setTextColor(Color.DKGRAY)
                    holder.badge.setBackgroundColor(Color.LTGRAY)
                }
            }

            holder.verdict.text = if (item.backendVerdict != null) "判定: ${item.backendVerdict}" else ""
        }
    }
}
