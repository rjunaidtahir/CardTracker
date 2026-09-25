# UAE Financial Tracker

An Android app that turns the SMS your UAE banks already send you into a clear picture of your spending: by category, by card and over time. It keeps track of card statements, due dates, budgets and fixed payments.

- **Works with any UAE bank.** The main banks have built-in formats, and a smart reader handles any other bank.
- **Private.** No internet permission, no account, no ads. Everything stays on the phone. One-time passwords (OTPs) are never stored.
- **Free to share.** Anyone can install it. It is not tied to one person's banks or cards.

## Install

The app is not on the Play Store. You install the APK file directly.

1. On the phone, open the **Releases** page of this repository and download the newest `UAE-Financial-Tracker-….apk`.
2. Open the downloaded file. If Android asks, allow your browser or Files app to **install unknown apps**.
3. Open **UAE Financial Tracker**. The setup walks you through the rest.
4. **Android 13 and later:** Android blocks SMS access for apps installed from a file. If the SMS permission is refused or greyed out, go to **Settings → Apps → UAE Financial Tracker → ⋮ (top right) → Allow restricted settings**, then allow SMS in the app. The setup shows the same steps and a button that opens the right screen.

Updates install over the old version and keep your data. Every build is signed with the same key, `app/debug.keystore`.

## First run

The setup takes about a minute:

1. **Allow SMS.** The app only keeps messages from bank senders.
2. **Your banks.** It scans your messages. It shows the banks it recognised and suggests other senders that look like banks, which you can tick to add.
3. **Import.** It reads your existing bank SMS into transactions.
4. **Optional:** record new messages automatically, and turn on card due-date reminders.

You can run the setup again from **More → Run the setup again**.

## Using the app

| Tab | What's there |
|---|---|
| **Home** | Total spent with a comparison to the period before, spending by category, budgets, fixed payments, payments due, 12-month history, top merchants, recurring payments and savings goals. |
| **Activity** | Every transaction, grouped by day, with search and card and category filters. Tap one to see the original SMS or change its category. The app learns from your changes. **Add** logs a cash spend by typing it, e.g. `lunch 45`. |
| **Cards** | Your cards and accounts, with available credit and utilisation, spend since the last statement, and the statement's due and paid status. **+** adds a card by hand. |
| **More** | **Needs review**, **Bank senders**, fixed payments, statement PDF check, reports (PDF or Excel), exchange rates, notifications, app lock, backup, themes and help. |

**Sync (↻):** tap it to import new bank SMS. You can also switch on **More → Record new messages automatically** to import each SMS as it arrives.

**Needs review:** bank messages that mention an amount but that the app couldn't read. **Fix** tells the app what one was. The form is pre-filled with the app's best guess, and your fix is remembered even after an update. You can also mark a message as **Not a transaction** or dismiss it.

**What counts as spending:** purchases minus refunds and cashback. These never count:

- paying off a card
- transfers between your accounts
- money coming in

Each card has a **Show & count** switch. Debit cards and bank accounts start switched off, so money isn't counted twice when you pay a card from your account.

## How messages are read

1. **Sender check.** Only SMS from bank senders are looked at. That covers the built-in list plus any senders you add in **More → Bank senders**.
2. **OTPs dropped.** OTP and verification messages are dropped without being stored.
3. **Bank rules.** Banks with verified formats are read by their rules in [`parser/BankRules.kt`](app/src/main/java/com/uaefinancial/tracker/parser/BankRules.kt). These are FAB, Emirates NBD, ADCB, Al Hilal, HSBC and Mashreq.
4. **Ignored messages.** Adverts, declines, limit changes, scheduled transfers and similar notices are recognised and ignored.
5. **Smart reader.** Anything else goes to the smart reader, [`parser/SmartParser.kt`](app/src/main/java/com/uaefinancial/tracker/parser/SmartParser.kt). It reads the message by its wording:
   - amounts and what they are (the spend, the available limit, the total due, the minimum due)
   - the card or account number
   - whether money went out or came in
   - the merchant and the date

   It covers Dubai Islamic Bank, Emirates Islamic, ADIB, RAKBANK, CBD, Citibank, Standard Chartered, NBF, Liv, Wio and any sender you add. It also catches new formats from banks that have rules. It is deliberately careful: when unsure, it sends the message to Needs review instead of guessing.
6. **Needs review.** Anything left over that contains an amount goes to Needs review.

With all bank rules switched off, the smart reader still reads 61 of the 62 real sample messages in the tests with the right type and amount.

## Building

GitHub Actions builds everything, so nothing needs to be installed on a PC.

- **Every push** runs the unit tests, builds the APK and runs Android lint. Results show on the run page as annotations.
- **Pushes to `main`** also publish the APK as a new release, which you then install from the phone.

To build locally, open the folder in Android Studio and use **Build → Build APK(s)**, or run `./gradlew assembleDebug`.

## Adding or fixing a bank format

1. Copy the SMS from **Needs review**, or use **Share these messages**.
2. Add it as a test in `app/src/test/.../parser/ParserTest.kt`, or in `SmartParserTest.kt` for the smart reader.
3. Add a `Rule` for that bank in `BankRules.kt`, or add its sender ID to the bank's `senderIds`. The comment at the top of the file explains the placeholder tokens.
4. Push. Once the release is installed, tap **More → Re-read stored** to apply the new rules to messages already on the phone.

## Project layout

| Path | Contents |
|---|---|
| `parser/` | `BankRules.kt` (bank formats and sender IDs), `SmartParser.kt` (reader for any bank), `SmsParser.kt` (the engine), `CategoryRules.kt` (categories and keywords), `ManualEntryParser.kt` (typed entries) |
| `core/` | Pure logic with unit tests: spending rules (`Spending.kt`), periods, insights, budgets, due status, the statement PDF reader (`StatementReader.kt`) |
| `data/` | Room database (`Database.kt`, schema v1), `Repository.kt` (SMS to transactions, fixes, senders), `Backup.kt`, `Prefs.kt` |
| `sms/` | Inbox reading and sender scan, sync, and the optional live SMS receiver |
| `ui/` | Jetpack Compose screens: Home, Activity, Cards, More, setup, review, senders, help |
| `notify/`, `widget/`, `report/` | Reminders and alerts, the home-screen widget, and PDF/CSV reports |

**Rules to keep:**

- All spending totals go through `core/Spending.kt`.
- Database changes need a Room migration (`data/Migrations.kt`), never a destructive fallback.
- Bank formats live only in `BankRules.kt`.
- Nothing runs in the background unless the user switches it on.
- The app has no internet permission.
