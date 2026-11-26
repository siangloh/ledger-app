package com.siangloh.ledger.shared

import com.siangloh.ledger.core.format.formatMoney
import com.siangloh.ledger.core.format.todayDateString
import com.siangloh.ledger.data.SharedLedgerEngine
import com.siangloh.ledger.parser.LocalNotificationParser
import com.siangloh.ledger.platform.PlatformToastBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

object IosLedgerBridge {
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun recordApplePayExpense(
        amount: Double,
        merchant: String,
        note: String,
        onDone: (String) -> Unit
    ) {
        if (!amount.isFinite() || amount <= 0.0) {
            dispatchMain { onDone("Please provide an amount greater than 0.") }
            return
        }

        bridgeScope.launch {
            try {
                SharedLedgerEngine.initDefaultsIfNeeded()
                val cleanMerchant = merchant.trim()
                val cleanNote = note.trim()
                val resolvedNote = cleanMerchant.ifBlank { cleanNote }
                val predicted = LocalNotificationParser.predictCategory(
                    merchant = cleanMerchant,
                    fullTextLower = "$cleanMerchant $cleanNote".lowercase()
                )
                val category = if (predicted == "其他") "餐饮" else predicted

                SharedLedgerEngine.recordTransaction(
                    date = todayDateString(),
                    type = "expense",
                    category = category,
                    amount = amount,
                    note = resolvedNote,
                    source = "ios_shortcut_apple_pay",
                    channel = "Apple Pay"
                )

                val displayMerchant = resolvedNote.ifBlank { "Apple Pay" }
                val summary = "Recorded RM ${formatMoney(amount)} ($displayMerchant)"
                PlatformToastBus.emit(summary)
                dispatchMain { onDone(summary) }
            } catch (e: Throwable) {
                println("IosLedgerBridge.recordApplePayExpense failed: ${e.message ?: "unknown"}")
                dispatchMain { onDone("Could not save expense. Please try again.") }
            }
        }
    }

    fun parseAndRecordBankMessage(
        rawText: String,
        onDone: (String) -> Unit
    ) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            dispatchMain { onDone("No bank message text provided.") }
            return
        }

        bridgeScope.launch {
            try {
                val parsed = LocalNotificationParser.parse(trimmed, "ios.shortcuts")
                if (parsed == null) {
                    dispatchMain { onDone("No transaction detected in this message.") }
                    return@launch
                }
                if (parsed.isPromo) {
                    dispatchMain { onDone("Skipped promotional message.") }
                    return@launch
                }
                if (parsed.isInternalTransfer) {
                    dispatchMain { onDone("Skipped internal wallet transfer.") }
                    return@launch
                }
                if (parsed.amount <= 0.0) {
                    dispatchMain { onDone("No valid transaction amount found.") }
                    return@launch
                }

                SharedLedgerEngine.initDefaultsIfNeeded()
                SharedLedgerEngine.recordTransaction(
                    date = parsed.date.ifBlank { todayDateString() },
                    type = parsed.type,
                    category = parsed.category,
                    amount = parsed.amount,
                    note = parsed.note.ifBlank { parsed.merchant },
                    source = "ios_shortcut_bank_sms",
                    channel = parsed.channel
                )

                val prefix = if (parsed.type == "income") "Recorded income" else "Recorded"
                val summary = "$prefix RM ${formatMoney(parsed.amount)} (${parsed.category})"
                PlatformToastBus.emit(summary)
                dispatchMain { onDone(summary) }
            } catch (e: Throwable) {
                println("IosLedgerBridge.parseAndRecordBankMessage failed: ${e.message ?: "unknown"}")
                dispatchMain { onDone("Could not process bank message. Please try again.") }
            }
        }
    }

    private fun dispatchMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            block()
        }
    }
}
