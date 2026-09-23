# Card Tracker roadmap

A personal, sideloaded Android app (Kotlin, Compose, Room, WorkManager) for UAE card spending. Base currency is AED.

## Status

v1.0 includes every phase below. Keep adding to this file for future ideas.

## Phase 1: SMS capture and core screens (done)

- **Two capture modes, one parser and one de-duplication path:**
  - **Manual Sync:** reads the inbox from the last successful sync; the first run reads everything.
  - **Live listening:** an optional BroadcastReceiver, off by default. It is fully disabled when off.
- **De-duplication:** a unique key per SMS: normalized sender, sent time (service-centre timestamp) and SHA-256 of the body. There's also a fallback check for when a sent time is missing.
- **Filtering:** only your bank sender IDs are processed. OTP messages are dropped and never stored.
- **Parsing:** rules live in `parser/BankRules.kt`. The raw SMS is stored, and anything that can't be parsed goes to the Review tab.
- **Cards and accounts:** credit cards, debit cards and bank accounts (FAB ·8001: money in and out). Each has a "Show & count" switch: on for credit, off for debit and accounts by default. Switched-off cards are hidden from the Transactions tab and left out of totals. Card payments and transfers never count as spending. Two FAB SMS for one transfer are merged into one transaction, and transfers to your own cards show as payments received on that card.
- **Screens:** Transactions (month and card filters, add by typing), Cards, Card detail, Review, Settings.

## Phase 2: Cards and safety (done in v1.0)

| Feature | Plan / where it plugs in |
|---|---|
| Card profiles: limit, statement day, due day | `cards.creditLimitMinor`, `statementDay`, `dueDay` already exist. Extend `CardDetailScreen` into an edit form. |
| Due-date reminders | A new `reminders` table (migration v3→v4). WorkManager periodic job plus notifications, using `cards.remindersEnabled` (already exists). Runs only if reminders are on. |
| Auto-mark paid from payment SMS | `statements.paidAt` and `paidByTxnId` already exist. When a PAYMENT transaction arrives, or a TRANSFER_OUT whose `counterpartyKey` is the card (already recorded from v3), match the latest unpaid statement on the same card: amount ≥ minimum due marks it paid, ≥ balance marks it fully paid. |
| Balances side by side with utilization % | Available limit comes from `transactions.availableLimitMinor` (latest per card), limit comes from the profile. Utilization = (limit − available) / limit. |
| App lock: biometric with PIN fallback, re-lock after background timeout | `ui/AppLockGate` already wraps the whole UI. Add androidx.biometric, a PIN hash in Prefs, and a timeout measured from `ON_STOP`. |
| CSV export/import backup | Storage Access Framework (`CreateDocument` / `OpenDocument`), one CSV per table. Import de-duplicates on `sms.dedupKey` and on the transaction id. |

## Phase 3: Insights (done in v1.0)

| Feature | Plan / where it plugs in |
|---|---|
| Auto-categories from merchant, learning from corrections | New `categories` and `merchant_category_rules` tables (migration). `transactions.categoryId` and `categoryUserSet` already exist. A correction writes a rule for the normalized merchant name, and later transactions use it. |
| Spending by category chart | New `Tab.INSIGHTS`. Totals must go through `core.Spending`, so excluded cards and payments stay out. |
| Month-by-month history chart | Same Insights tab, same `core.Spending` rule. |
| Recurring payment detection | `transactions.recurringGroupId` already exists. Group by merchant, similar amount, and a roughly monthly or weekly interval. |

## Phase 4: Extras (done in v1.0)

| Feature | Plan / where it plugs in |
|---|---|
| Home screen widgets | Jetpack Glance: this month's spend and next due date. Reads the same DAO. |
| Savings goals | New `savings_goals` table (migration) and a new Route. |
| Full multi-currency reporting in AED | Every transaction already stores the original amount and currency plus an AED figure (`fxEstimated` flags approximate rates). Add a rates table with dated rates to replace the static `BankRules.fxToAed`, and per-currency breakdowns. |

## How v1.0 implemented it

- **Paid / due status** is worked out on the fly (`core/CardStatus.kt`): payments to the card after its statement date, from the card's bank SMS and from your transfers without double counting. The planned `statements.paidAt` column was not needed.
- **Reminders:** `notify/DueReminders.kt`. A WorkManager job twice a day, only while switched on, notifying 3 days before, 1 day before and on the due day while the minimum is unpaid.
- **Categories:** keyword rules in `parser/CategoryRules.kt`. Learned rules go in `merchant_rules`, and per-SMS choices in `txn_overrides` (keyed by SMS dedupKey, so they survive Re-parse and backups).
- **Charts:** plain Compose (`ui/OverviewScreen.kt`), single-hue bars, no chart library.
- **Recurring detection:** `core/Insights.kt` (3+ roughly monthly charges of a similar amount).
- **Widget:** a classic AppWidgetProvider (`widget/SummaryWidget.kt`), no Glance dependency.
- **Multi-currency:** editable rates in `fx_rates`. Saving a rate recalculates past AED amounts.
- **App lock:** PIN (salted SHA-256, 10k rounds) plus BiometricPrompt, with a re-lock timeout.
- **Backup:** a zip of CSVs via the system file picker (`data/Backup.kt`).

## Ideas for later

- A manual recurring-payments list for EMIs that don't send an SMS.
- Monthly budget per category, with a warning when close.
- Dated exchange rates (a rate per month) instead of one current rate.

## Design rules to keep

- All spending totals and charts go through `core/Spending.kt`: excluded cards and credit card payments never count.
- Schema changes from v2 onward are proper Room migrations in `data/Migrations.kt`, never destructive, so your data survives updates.
- New screens are added as a `Route` or `Tab` in `ui/Navigation.kt`.
- Nothing runs in the background unless a feature you've switched on needs it (live listening, reminders).
- The debug signing key (`app/debug.keystore`) stays the same, so each new APK installs over the old one without losing data.
