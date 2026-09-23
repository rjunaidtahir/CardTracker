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

| Bank | Sender ID | Purchases | Statement | Other |
|---|---|---|---|---|
| FAB | FAB | ✅ from sample (debit format assumed) | ❌ no sample | – |
| Emirates NBD | EmiratesNBD | ✅ from sample (no date in the SMS, so arrival time is used; debit format assumed) | ❌ no sample | – |
| ADCB | ADCBAlert | ✅ from sample (debit format assumed) | ✅ from sample | – |
| Al Hilal | AlHilal | ✅ from sample | ❌ no sample | declined and limit-change SMS are ignored |
| HSBC | HSBC-UAE | ❌ no sample | ✅ from sample | payments ✅, cashback ✅ (stored as refund) |
| Mashreq | Mashreq (unverified) | generic guess | generic guess | – |

OTP, declined-transaction and limit-change messages are ignored. Any bank SMS that contains an amount but doesn't match a rule shows up on the **Review** tab with its raw text.

## How SMS get in

- **Sync (always available):** the **Sync SMS** button reads your inbox from the last successful sync onward. The first Sync imports everything already there. It then shows a result like "12 new transactions, 1 statement, 2 couldn't be parsed". It needs permission to read SMS, which it asks for the first time you tap it.
- **Live listening (optional, off by default):** turn it on in Settings to capture bank SMS as they arrive. It asks for permission to receive SMS the first time you switch it on. When it's off, the receiver is disabled at the system level and nothing runs in the background. The first time you turn it on, a tip explains how to add the app to Samsung's **Never sleeping apps**.
- **Both modes** only look at your bank sender IDs, drop OTP messages without storing them, and share one duplicate check. Each SMS gets a unique key from its sender, sent time and a hash of the text, so a message caught live is skipped by Sync and vice versa.

## Screens

- **Transactions:**
  - The Sync button, the "Add by typing" box, month arrows (tap the month name for all months) and card filter chips.
  - Transactions on cards you've excluded are still listed, marked "not counted", but are left out of the total.
  - Tap a row to see the raw SMS, or to delete it. Deleting also dismisses the SMS, so Re-parse doesn't bring it back.
- **Cards:** each card shows credit or debit, a **Count in spending** switch (on for credit, off for debit by default), this month's spend, and the latest statement. Tap a card to change its type or open its transactions.
- **Review:** bank SMS that contain an amount but didn't match a rule.
- **Settings:** live listening, sync info, "Re-read whole inbox on next Sync", and "Re-parse all stored SMS".

## Data model

- Amounts are stored in minor units (fils/cents) with their original currency. Each one also has an AED equivalent.
- SMS usually don't give an AED amount for foreign-currency spends, so the AED figure uses the approximate rates in `BankRules.fxToAed` and is flagged as an estimate.
- Spend = purchases − refunds/cashback, on cards with "Count in spending" switched on, plus typed entries. Credit card payments never count. The rules are in `core/Spending.kt`.
- The database is laid out ready for Phases 2–4; see `ROADMAP.md`.
