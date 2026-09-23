# Card Tracker: Phase 1

A personal Android app that reads your UAE bank SMS and turns them into credit card transactions and statements. Everything stays on the phone in a local Room database.

## Build the APK on GitHub (no installs on the PC)

GitHub builds the APK for free, and you download it straight onto the phone.

1. **Create a private repo.** Sign in at github.com (a free personal account is fine) and choose **New repository**. Name it `CardTracker`, set it to **Private**, and leave "Add a README" **unticked**.
2. **Upload the files.** On the empty repo page, click **uploading an existing file**. Open this `CardTracker` folder in File Explorer, select **everything inside it**, drag it onto the page, and click **Commit changes**.
3. **Add the build script.** In the repo, choose **Add file → Create new file**. Type the name `.github/workflows/build.yml` (the slashes create the folders). Open `build-workflow.yml` from this folder in Notepad, copy all of it, paste it in, and click **Commit changes**.
4. **Wait for the build.** Open the **Actions** tab and wait about 5 minutes for the green tick. A red cross means something failed: open it and send Claude the error.
5. **On the phone:** open the repo in the browser (signed in) and go to **Releases**. Tap `CardTracker-buildN.apk` to download it, then open it to install. Allow "Install unknown apps" for your browser when asked.
6. **Allow SMS access.** Android 15 blocks SMS permissions for apps installed from a file. Go to **Settings → Apps → Card Tracker → ⋮ (top right) → Allow restricted settings**. Then open the app and tap **Sync SMS**; it asks for read access the first time.

**Updating later:** upload the changed files (for example `BankRules.kt`) to the same path in the repo. Actions builds a new release, and you install it over the old app. Your data is kept because every build is signed with the same key, `app/debug.keystore`.

## Alternative: build on a PC with Android Studio

Android Studio (the .zip version runs without admin rights): File → Open this folder → **Build → Build APK(s)**. Then copy `app\build\outputs\apk\debug\app-debug.apk` to the phone and follow steps 5–6 above.

## Tests

`ParserTest.kt` covers the bank formats. `CoreTest.kt` covers the duplicate-check key, the spending rules and the Sync summary text. The GitHub build runs both on every upload (Actions tab → test-report). In Android Studio, run them with `gradlew testDebugUnitTest`.

## Editing parsing rules

All bank formats live in **`app/src/main/java/com/junaid/cardtracker/parser/BankRules.kt`**. The comment at the top of that file lists the placeholder tokens you can use.

1. Copy the new SMS from the Review tab (you can select and copy the text).
2. Add a test with that SMS to `ParserTest.kt`, then add or adjust a `Rule` in `BankRules.kt` until the test passes.
3. Upload the changed files to GitHub, install the new release, then use ⋮ → **Re-parse all SMS**. Every stored raw SMS is parsed again with the new rules.

## Status of each bank

| Bank | Sender ID | Card SMS | Statements | Accounts / other |
|---|---|---|---|---|
| FAB | FAB | purchases (·0831), cashback, payments to card (·3115) | ·3115 statements | ·8001/·8003/·8005: remittances, salary, deposits, credits, debits (EMIs), ATM, transfers, bills, rewards |
| Emirates NBD | EmiratesNBD | "Purchase of…" and "Payment of … to …", refunds, payments received | Mini Stmt | – |
| ADCB | ADCBAlert | both "was used for" formats, foreign-fee format, reversals, payments | Billing alert | account ·0001 credits/debits |
| Al Hilal | AlHilal | purchases, cashback | – (no sample yet) | – |
| HSBC | HSBC-UAE | purchases, reversals, cashback, payments | statements | – |
| Mashreq | Mashreq | **no purchase sample yet** (generic guess) | – | accounts ·7639/·8902 in/out, card payments ·4680 |

OTP and auth-code messages are dropped. Adverts, payment reminders, card-setting notices, approval prompts, transfer requests, scheduled transfers, declined transactions and limit changes are ignored.

**Bank account and transfers.** FAB account 8001 is tracked as a *bank account*, with both money in and money out, and "Count in spending" is off by default. FAB often sends two SMS for one transfer ("Outward Remittance Debit" and "funds transfer … processed"). The app merges those into one transaction.

Transfers to your own cards (FAB ·0831, ENBD ·9940, Al Hilal ·3976, or any other credit card the app already knows) show as **payments received** on that card. Transfers to your wife's ENBD account ·7701 and Emirates Islamic card ·6901 are just money out. None of these count as spending. You can edit this list in `BankRules.knownAccounts`.

**EMIs and other account payments.** FAB "An amount of AED … has been debited from your FAB account" SMS (EMIs, direct debits), bill payments and ATM withdrawals are recorded as *purchases* on the account. Switch the account **on** (Show & count) on the Cards tab to include them in spending. Money coming in and transfers are never counted. If a debit SMS turns out to be the same money as a transfer SMS (same amount, within a day), the two are merged and it becomes a transfer instead. Any bank SMS that contains an amount but doesn't match a rule shows up on the **Review** tab with its raw text.

## How SMS get in

- **Sync (always available):** the **Sync SMS** button reads your inbox from the last successful sync onward. The first Sync imports everything already there. It then shows a result like "12 new transactions, 1 statement, 2 couldn't be parsed". It needs permission to read SMS, which it asks for the first time you tap it.
- **Live listening (optional, off by default):** turn it on in Settings to capture bank SMS as they arrive. It asks for permission to receive SMS the first time you switch it on. When it's off, the receiver is disabled at the system level and nothing runs in the background. The first time you turn it on, a tip explains how to add the app to Samsung's **Never sleeping apps**.
- **Both modes** only look at your bank sender IDs, drop OTP messages without storing them, and share one duplicate check. Each SMS gets a unique key from its sender, sent time and a hash of the text, so a message caught live is skipped by Sync and vice versa.

## Screens

- **Overview (home):**
  - Month picker and total spent, compared with last month.
  - **Card payments due:** amount still to pay, due date, and paid / minimum paid / overdue.
  - **Spending by category:** tap a category to see its transactions.
  - **Last 12 months:** bar chart; tap a bar to switch month.
  - **By card.**
  - **Recurring payments:** subscriptions and EMIs, with the next expected date.
  - **Foreign-currency spending in AED.**
  - **Savings goals:** add a goal, add money, see the monthly amount needed to reach the target date.
- **Transactions:**
  - Sync button, "Add by typing", month and card filters.
  - When a bank account is selected, **Money in / Money out / Net** at the top.
  - Each row shows its category. Tap a row, then **Change** to re-categorise; "Apply to all" teaches the app that merchant for the past and future.
  - Tap a row to see the raw SMS or delete it.
- **Cards:**
  - **Balances:** available, limit and % used side by side, once you've entered limits.
  - Each card shows this month's spend, payments received, and the latest statement with its paid/due status.
  - Tap a card for its profile: nickname, credit limit, statement day, due day, per-card reminders, utilisation, payments to the card, card type and **Show & count**.
- **Review:** unparsed SMS, with **Share unparsed SMS**.
- **Settings:**
  - Live listening and **due-date reminders**.
  - **App lock:** PIN plus fingerprint/face, re-lock after immediately / 1 / 5 / 15 min.
  - Sync, re-parse, **backup export/restore** (zip of CSVs) and **exchange rates**.
- **Home-screen widget:** long-press the home screen → Widgets → Card Tracker. Shows this month's spending and the next payment due.

## First steps after installing v1.0

1. **Re-parse all SMS** (Review or Settings) so every transaction gets a category.
2. On the Cards tab, open each credit card and enter its **credit limit** (optional: statement day and due day).
3. In Settings, switch on **Due-date reminders** and allow notifications.
4. Optionally switch on **App lock**, and **Export backup** now and then (save it to Google Drive or Files).

## Data model

- Amounts are stored in minor units (fils/cents) with their original currency. Each one also has an AED equivalent.
- SMS usually don't give an AED amount for foreign-currency spends, so the AED figure uses the approximate rates in `BankRules.fxToAed` and is flagged as an estimate.
- Spend = purchases − refunds/cashback, on cards with "Show & count" switched on, plus typed entries. Credit card payments never count. The rules are in `core/Spending.kt`.
- Categories: `parser/CategoryRules.kt` holds the default categories and the keywords that auto-assign them. Your corrections take priority over the keywords.
- See `ROADMAP.md` for how each feature is built and ideas for later.
