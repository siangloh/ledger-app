# Cashflow analysis

Web: `/analytics/cashflow`, linked from the monthly dashboard. Android: Overview → Cashflow analysis (a secondary native screen; no extra bottom tab). Web and native use `services/cashflow_analysis_service.py` through session-authenticated `/api/analytics/cashflow` and the existing native authentication resolver at `/api/native/cashflow-analysis`.

## Accounting contract

- Explicit calendar-month bounds, independently of the existing configurable budget billing cycle.
- Actual income/expenses exclude internal transfer types/categories. Expenses include `from_savings`; savings-funded consumption is not hidden.
- Auto-generated `source=recurring` rows are scheduled, not evidence of payment. They are excluded from actuals until explicitly confirmed or linked to a bill. The previous dashboard's ledger-entry totals may therefore differ. The analysis lists these unconfirmed rows separately.
- Net = income − actual expense. Reserved = current-month bill balances after valid payments/refunds. Projected available = net − reserved. It is not a bank balance or guaranteed safe allowance.
- Historical recorded overdue balances are shown separately and do not silently reduce the current month's reserve. Unsnapshotted historical obligations cannot be proven from mutable current plans.
- Report each currency separately; never use the legacy mock FX provider. A transaction's currency comes from its owned account, otherwise the ledger default. Foreign bills require same-currency payments. Unknown currency provenance cannot be reconstructed retroactively.
- Ratios use positive income only; deficits and percentages above 100% remain visible. Decimal arithmetic rounds at monetary boundaries.

## Bill evidence

Schedules are reconstructed with the existing subscription cycle/anchor utility, installment tenure and monthly rules. Weekly subscriptions may have multiple occurrences; annual subscriptions only appear in their actual due month. Source counters, note text, generated rows and date advancement never prove payment.

Explicit links persist snapshots keyed by `(user, source kind, source ID, due date)` in `cashflow_bills`. Each transaction can link to at most one bill using a database uniqueness constraint. Multiple transactions support partial payments. Deleted/changed transactions are re-evaluated; explicit income-refund links reduce expense instead of inflating income. Unlinking, amount correction, bill cancellation/restoration and explicit same-month duplicate marking are supported. Financial rows are not deleted by these operations.

Existing subscription/installment matches with real transactions create bill evidence before renewal. Repeating a transaction does not advance the legacy counters again. Partial payments do not advance them. Legacy foreign-currency matches without verifiable denomination may still advance their existing schedule, but are logged and never treated as settled cashflow bills.

All mutations persist data versions through `bump_data_version`. GET reports do not create or mark bills paid. Current schedules without snapshots remain conservative estimates, visibly labeled. They may overlap until users explicitly mark a duplicate. A single aggregate transaction cannot be split across multiple bills in this implementation.

## Validation

Use isolated databases. Tests in `tests/test_cashflow_analysis.py` cover savings-funded spending, currency separation, user isolation, partial payment, idempotence, duplicate claims, deleted payments, refunds, schedule advancement, weekly/yearly billing, prior balances, deficits, generated-entry confirmation and explicit matching bridges. All three native locale resources and Web cashflow labels are provided. Native cached reports are scoped to server/account/key/month/currency and visibly marked; writes require an online report.

Do not claim historical accuracy without source evidence, mixed-currency consolidation, or full-device UI coverage from compilation alone.
