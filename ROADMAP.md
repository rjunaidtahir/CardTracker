# Roadmap

UAE Financial Tracker: a sideloaded Android app (Kotlin, Jetpack Compose, Room, WorkManager) that turns UAE bank SMS into spending, statements and due dates. Base currency AED. No internet permission.

## v2.0: for everyone, any UAE bank (current)

- **New identity.** The app is now called UAE Financial Tracker, with app ID `com.uaefinancial.tracker` and database schema v1. It installs alongside the older personal build, so that build can be uninstalled.
- **No personal data in the code.** The hard-coded accounts, transfer destinations, card looks and card names chosen by last 4 digits are gone.
  - Transfers to your own cards are recognised from the cards in the app. The match has to be unambiguous.
  - If an SMS names no account, the app uses your only account at that bank.
- **Any bank.**
  - There are 29 UAE banks in `BankRules.kt`. Six have verified formats: FAB, Emirates NBD, ADCB, Al Hilal, HSBC and Mashreq. The rest have sender IDs only.
  - The smart reader (`parser/SmartParser.kt`) handles any wording. With the rules bypassed, it reads 61 of 62 real sample messages with the right type and amount, and 6 of 6 statement SMS.
  - You can add bank senders yourself (`bank_senders` table), and a scan of the inbox suggests them (`InboxReader.scanSenders`).
- **Fix by hand.** Needs review → Fix stores your reading of an SMS in `sms_fixes`, keyed by the SMS, so it survives "Re-read stored" and backups. "Not a transaction" is stored the same way.
- **First-run setup** (`ui/Onboarding.kt`):
  1. SMS permission, with help for Android's "restricted settings"
  2. Bank scan
  3. First import
  4. Optional extras
- **Clearer layout.** There are four tabs: Home, Activity, Cards and More. Sync (↻) sits in the top bar. More holds the tools and settings in plain groups. There's a Help screen, and Cards has an "Add card or account" button.
- **Fixes:**
  - Due-reminder "already sent" markers no longer repeat after a re-read, and they're pruned.
  - Typed "paid rent 3000" is a spend, not a card payment.
  - Lower-case words aren't read as currency codes.
  - An absurd amount can't break a re-read.
  - Spends on a family card get the Family category from SMS too.
  - Cloud backup of financial data is excluded (`data_extraction_rules.xml`), and the internet permission is removed outright.
- **CI.** Every push runs the unit tests, the build and lint, and reports them as annotations. Pushes to `main` publish a release.

## Earlier (personal builds, v1.0 to v1.6)

- SMS capture:
  - Manual Sync, plus optional live listening, with one duplicate check between them.
  - Two-SMS transfers are merged.
- Spending:
  - Categories that learn from corrections, budgets, fixed payments and recurring detection.
  - Alerts, due reminders, reports, app lock, backup, widget.
- Statements:
  - Layout-aware statement PDF reader (`core/StatementReader.kt`) with a totals self-check.
  - Usage since the last statement.
- Looks: themes, card looks and your own card pictures.

## Ideas for later

- Dated exchange rates (a rate per month) instead of one current rate.
- Rules contributed from real SMS for the banks that only use the smart reader today (DIB, Emirates Islamic, ADIB, RAKBANK, CBD and others). Add each sample to the tests first.
- Editable date in the "Fix" form (it uses the day the SMS arrived for now).
- Arabic-language bank SMS.

## Design rules to keep

- All spending totals and charts go through `core/Spending.kt`: excluded cards and credit card payments never count.
- Every schema change is a Room migration in `data/Migrations.kt`, never destructive.
- Bank formats live in `parser/BankRules.kt`. When there's no matching rule, the smart reader takes over, and anything still unread goes to Needs review. Never guess silently.
- Nothing runs in the background unless a feature the user switched on needs it (live listening, reminders).
- No internet permission.
- The signing key (`app/debug.keystore`) stays the same, so each new APK installs over the previous one without losing data.
