# Roadmap

UAE Financial Tracker turns UAE bank SMS into spending, statements and due dates, with AED as the base currency. It comes in two apps:

- a sideloaded Android app (Kotlin, Jetpack Compose, Room, WorkManager), with no internet permission
- an iPhone app (SwiftUI, SwiftData)

Both apps use one shared engine written in Kotlin Multiplatform.

## iPhone app 1.0 (in progress)

- **Shared engine** (`shared/`). Message reading, the statement reader, the spending rules and the duplicate key moved out of the Android app into a Kotlin Multiplatform module that uses only the Kotlin standard library. It has its own date, decimal and SHA-256 code, each checked against Java on thousands of cases. Its 103 tests pass on the JVM and on the iPhone simulator. `bridge/Bridge.kt` gives Swift a flat API.
- **Getting messages in on an iPhone.** iOS doesn't let apps read SMS, so the app offers:
  - The Shortcuts action "Add Bank Message", fed by a Message automation, with the setup steps in the app. It runs in the background.
  - Paste, one message or many.
  - Android "SMS Backup & Restore" XML files.
  - Statement PDFs opened from other apps, including password-protected ones.

  When the sender isn't known (pasted text), the app tries each bank's own formats first, then a bank named in the text, then the smart reader.
- **Screens:**
  - Home: spent this period with a comparison, payments due, spending by category, top merchants
  - Activity: search, detail with the original SMS, category learning, typed spends
  - Cards: card tiles with limits and statements, and card settings
  - More: import options, Needs review with Fix, bank senders, exchange rates, re-read, help and first-run setup
- **Privacy.** Data is not synced to iCloud and there is no network code. There's a privacy manifest, and OTPs are never stored.
- **CI.** Every change to `ios/` or `shared/` runs on a Mac runner:
  1. the shared tests on the simulator
  2. the app's tests, including a PDF drawn, then read back through PDFKit
  3. a build for a real iPhone
  4. screenshots with sample data

  `ios-release.yml` uploads to TestFlight once the Apple account secrets are added.

**Next for iPhone:**

- Screenshots of the Messages app, read with text recognition (Vision). This includes times like "Yesterday 21:05" and flags messages with no visible date.
- A Share extension, so a message's text can be shared from any app.
- Importing the Android app's backup.
- Budgets, fixed payments, due-date reminders, reports and app lock.
- Merging two-SMS transfers, as Android does.
- App Store assets.

## Android v2.1: statements from any bank

- **Open a statement from anywhere.** "Open with" from a Gmail attachment or My Files, or Share, goes straight into the statement check. It asks for the password if needed, then matches the card by its last 4 digits or lets you pick one.
- **Statement reader for unseen layouts** (`core/StatementReader.kt`):
  - Labels are recognised by word concepts with their abbreviations (Stmt Dt, Tot. Amt. Due, Min. Amt., Avl. Cr. Limit, Prev. Bal.).
  - Figures are found beside their label, below it, above it (summary tiles), just before it, or in a sentence ("pay at least AED 31.75 by 4 October").
  - "Balance at start / end" is recognised on account statements.
  - Rows of summary figures are never read as transactions.
- **Tested blind on real PDFs.** Three rounds, 22 statements, each written and scored before the reader was changed for it: 4/8, then 6/8, then 5/6. All pass now. Also 26 text layouts and your 7 real statements. The generators are in `tools/`.

## v2.0: for everyone, any UAE bank

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

- Message and statement reading lives only in `shared/`, so both apps read the same way. Its tests run on both platforms.
- All spending totals and charts go through `core/Spending.kt`: excluded cards and credit card payments never count.
- Every schema change is a Room migration in `data/Migrations.kt`, never destructive.
- Bank formats live in `parser/BankRules.kt`. When there's no matching rule, the smart reader takes over, and anything still unread goes to Needs review. Never guess silently.
- Nothing runs in the background unless a feature the user switched on needs it (live listening, reminders).
- No internet permission.
- The signing key (`app/debug.keystore`) stays the same, so each new APK installs over the previous one without losing data.
