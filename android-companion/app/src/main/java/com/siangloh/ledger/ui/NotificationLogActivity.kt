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

        recyclerView = findViewById(R.id.logRecyclerView)
        emptyView = findViewById(R.id.emptyLogView)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter()
        recyclerView.adapter = adapter

        loadLogs()
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
