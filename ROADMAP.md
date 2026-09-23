# Card Tracker roadmap

A personal, sideloaded Android app (Kotlin, Compose, Room, WorkManager) for UAE card spending. Base currency is AED.

## Phase 1: SMS capture and core screens (current)

- **Two capture modes, one parser and one de-duplication path:**
  - **Manual Sync:** reads the inbox from the last successful sync; the first run reads everything.
  - **Live listening:** an optional BroadcastReceiver, off by default. It is fully disabled when off.
- **De-duplication:** a unique key per SMS: normalized sender, sent time (service-centre timestamp) and SHA-256 of the body. There's also a fallback check for when a sent time is missing.
- **Filtering:** only your bank sender IDs are processed. OTP messages are dropped and never stored.
- **Parsing:** rules live in `parser/BankRules.kt`. The raw SMS is stored, and anything that can't be parsed goes to the Review tab.
- **Cards:** credit and debit. Each card has a "Count in spending" toggle: on for credit, off for debit by default. Card payments never count as spending.
- **Screens:** Transactions (month and card filters, add by typing), Cards, Card detail, Review, Settings.

## Phase 2: Cards and safety

| Feature | Plan / where it plugs in |
|---|---|
| Card profiles: limit, statement day, due day | `cards.creditLimitMinor`, `statementDay`, `dueDay` already exist. Extend `CardDetailScreen` into an edit form. |
| Due-date reminders | A new `reminders` table (migration v2→v3). WorkManager periodic job plus notifications, using `cards.remindersEnabled` (already exists). Runs only if reminders are on. |
| Auto-mark paid from payment SMS | `statements.paidAt` and `paidByTxnId` already exist. When a PAYMENT transaction arrives, match the latest unpaid statement on the same card: amount ≥ minimum due marks it paid, ≥ balance marks it fully paid. |
| Balances side by side with utilization % | Available limit comes from `transactions.availableLimitMinor` (latest per card), limit comes from the profile. Utilization = (limit − available) / limit. |
| App lock: biometric with PIN fallback, re-lock after background timeout | `ui/AppLockGate` already wraps the whole UI. Add androidx.biometric, a PIN hash in Prefs, and a timeout measured from `ON_STOP`. |
| CSV export/import backup | Storage Access Framework (`CreateDocument` / `OpenDocument`), one CSV per table. Import de-duplicates on `sms.dedupKey` and on the transaction id. |

## Phase 3: Insights

| Feature | Plan / where it plugs in |
|---|---|
| Auto-categories from merchant, learning from corrections | New `categories` and `merchant_category_rules` tables (migration). `transactions.categoryId` and `categoryUserSet` already exist. A correction writes a rule for the normalized merchant name, and later transactions use it. |
| Spending by category chart | New `Tab.INSIGHTS`. Totals must go through `core.Spending`, so excluded cards and payments stay out. |
| Month-by-month history chart | Same Insights tab, same `core.Spending` rule. |
| Recurring payment detection | `transactions.recurringGroupId` already exists. Group by merchant, similar amount, and a roughly monthly or weekly interval. |

## Phase 4: Extras

| Feature | Plan / where it plugs in |
|---|---|
| Home screen widgets | Jetpack Glance: this month's spend and next due date. Reads the same DAO. |
| Savings goals | New `savings_goals` table (migration) and a new Route. |
| Full multi-currency reporting in AED | Every transaction already stores the original amount and currency plus an AED figure (`fxEstimated` flags approximate rates). Add a rates table with dated rates to replace the static `BankRules.fxToAed`, and per-currency breakdowns. |

## Design rules to keep

- All spending totals and charts go through `core/Spending.kt`: excluded cards and credit card payments never count.
- Schema changes from v2 onward are proper Room migrations in `data/Migrations.kt`, never destructive, so your data survives updates.
- New screens are added as a `Route` or `Tab` in `ui/Navigation.kt`.
- Nothing runs in the background unless a feature you've switched on needs it (live listening, reminders).
- The debug signing key (`app/debug.keystore`) stays the same, so each new APK installs over the old one without losing data.
