import AppIntents
import Shared

struct LogApplePayExpenseIntent: AppIntent {
    static var title: LocalizedStringResource = "Log Apple Pay Expense"
    static var description = IntentDescription("Record an Apple Pay or Wallet expense directly into Ledger.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Amount")
    var amount: Double

    @Parameter(title: "Merchant")
    var merchant: String

    @Parameter(title: "Note")
    var note: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Log RM \(\.$amount) at \(\.$merchant)") {
            \.$note
        }
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message: String = await withCheckedContinuation { continuation in
            IosLedgerBridge.shared.recordApplePayExpense(
                amount: amount,
                merchant: merchant,
                note: note ?? ""
            ) { result in
                continuation.resume(returning: result)
            }
        }
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

struct ParseBankSmsExpenseIntent: AppIntent {
    static var title: LocalizedStringResource = "Parse Bank SMS Expense"
    static var description = IntentDescription("Extract and record a bank or e-wallet transaction message into Ledger.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Message Text")
    var messageText: String

    static var parameterSummary: some ParameterSummary {
        Summary("Record transaction from \(\.$messageText)")
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let message: String = await withCheckedContinuation { continuation in
            IosLedgerBridge.shared.parseAndRecordBankMessage(rawText: messageText) { result in
                continuation.resume(returning: result)
            }
        }
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

struct LedgerShortcutsProvider: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        if #available(iOS 17.0, *) {
            return [
                AppShortcut(
                    intent: LogApplePayExpenseIntent(),
                    phrases: [
                        "Log Apple Pay expense in \(.applicationName)",
                        "Record Wallet payment in \(.applicationName)"
                    ],
                    shortTitle: "Log Apple Pay Expense",
                    systemImageName: "creditcard.fill"
                ),
                AppShortcut(
                    intent: ParseBankSmsExpenseIntent(),
                    phrases: [
                        "Parse bank message in \(.applicationName)",
                        "Record bank SMS in \(.applicationName)"
                    ],
                    shortTitle: "Parse Bank SMS",
                    systemImageName: "message.badge.filled.fill"
                )
            ]
        } else {
            return [
                AppShortcut(
                    intent: LogApplePayExpenseIntent(),
                    phrases: [
                        "Log Apple Pay expense in \(.applicationName)",
                        "Record Wallet payment in \(.applicationName)"
                    ]
                ),
                AppShortcut(
                    intent: ParseBankSmsExpenseIntent(),
                    phrases: [
                        "Parse bank message in \(.applicationName)",
                        "Record bank SMS in \(.applicationName)"
                    ]
                )
            ]
        }
    }
}
