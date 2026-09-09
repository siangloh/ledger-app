package com.siangloh.ledger.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.siangloh.ledger.R
import com.siangloh.ledger.data.AppDatabase
import com.siangloh.ledger.data.entities.PendingTransaction
import com.siangloh.ledger.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class QuickAddActivity : AppCompatActivity() {

    private lateinit var typeRadioGroup: RadioGroup
    private lateinit var amountEditText: EditText
    private lateinit var categorySpinner: Spinner
    private lateinit var dateEditText: EditText
    private lateinit var noteEditText: EditText
    private lateinit var saveButton: Button

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val defaultExpenseCategories = listOf("餐饮", "交通", "购物", "娱乐", "居住", "医疗", "其他")
    private val defaultIncomeCategories = listOf("工资", "奖金", "理财", "副业", "其他")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick_add)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        typeRadioGroup = findViewById(R.id.typeRadioGroup)
        amountEditText = findViewById(R.id.amountEditText)
        categorySpinner = findViewById(R.id.categorySpinner)
        dateEditText = findViewById(R.id.dateEditText)
        noteEditText = findViewById(R.id.noteEditText)
        saveButton = findViewById(R.id.saveButton)

        // 初始化日期
        dateEditText.setText(dateFormat.format(calendar.time))
        dateEditText.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    calendar.set(year, month, dayOfMonth)
                    dateEditText.setText(dateFormat.format(calendar.time))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        typeRadioGroup.setOnCheckedChangeListener { _, _ ->
            loadCategories()
        }

        loadCategories()

        saveButton.setOnClickListener {
            saveTransaction()
        }
    }

    private fun loadCategories() {
        val isExpense = typeRadioGroup.checkedRadioButtonId == R.id.radioExpense
        val currentType = if (isExpense) "expense" else "income"

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@QuickAddActivity)
            val cached = withContext(Dispatchers.IO) {
                db.cachedCategoryDao().getCategoriesByType(currentType)
            }

            val categoryNames = if (cached.isNotEmpty()) {
                cached.map { it.name }
            } else {
                if (isExpense) defaultExpenseCategories else defaultIncomeCategories
            }

            val adapter = ArrayAdapter(
                this@QuickAddActivity,
                android.R.layout.simple_spinner_dropdown_item,
                categoryNames
            )
            categorySpinner.adapter = adapter
        }
    }

    private fun saveTransaction() {
        val amountStr = amountEditText.text.toString().trim()
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            Toast.makeText(this, "请输入大于 0 的有效金额", Toast.LENGTH_SHORT).show()
            return
        }

        val isExpense = typeRadioGroup.checkedRadioButtonId == R.id.radioExpense
        val type = if (isExpense) "expense" else "income"
        val category = categorySpinner.selectedItem?.toString() ?: "其他"
        val date = dateEditText.text.toString().trim()
        val note = noteEditText.text.toString().trim().ifEmpty { category }

        saveButton.isEnabled = false

        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@QuickAddActivity)
            withContext(Dispatchers.IO) {
                db.pendingTransactionDao().insert(
                    PendingTransaction(
                        date = date,
                        type = type,
                        group_name = "personal",
                        category = category,
                        amount = amount,
                        note = note,
                        source = "offline_manual",
                        sync_status = "pending"
                    )
                )
            }

            // 触发 WorkManager 在有网络时同步
            SyncWorker.enqueueSync(this@QuickAddActivity)

            Toast.makeText(this@QuickAddActivity, "✓ 已保存到本地，联网后将自动同步到账本！", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
