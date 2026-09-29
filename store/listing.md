# Fils — Play Store listing text

## App title (max 30 chars)
Fils - Cards & Spend Tracker

(28 characters, comfortably under the 30-character limit)

## Short description (max 80 chars)
Card & account spending from your bank SMS. Private, on-device, any currency.

(77 characters)

## Full description (max 4000 chars)

Fils turns the SMS your bank already sends you into a clear picture of your spending — by category, by card, and over time. No linking your bank account, no login, no card numbers typed in. Just install, allow SMS access, and Fils reads what your bank already tells you.

WORKS WITH YOUR BANK, WHEREVER YOU ARE
Built for the Gulf, South Asia, the UK & Europe, and the US & Canada. A smart reader understands bank alerts in English, Arabic, Spanish, Portuguese, French, German, Italian and Dutch, in any currency and date format, and ready-made rules cover the major UAE banks (FAB, Emirates NBD, ADCB, HSBC, Mashreq, Dubai Islamic Bank, RAKBANK, Wio and more). Your home currency is set from your phone's region and can be changed any time; spends in other currencies are converted for you.

SEE WHERE YOUR MONEY GOES
• Total spending with a comparison to last month
• Spending broken down by category, with budgets you set
• 12-month history and top merchants
• Every transaction, searchable and filterable by card or category

NEVER MISS A DUE DATE
• All your cards in one place, with available credit and utilisation
• Due-date reminders and spending alerts
• Check a statement PDF against what Fils recorded — Fils verifies the arithmetic and flags any mismatch

BUDGETS, GOALS & RECURRING PAYMENTS
• Set monthly budgets by category
• Track savings goals
• Automatic detection of recurring payments like utility bills, phone plans, Netflix and gym memberships
• Log cash spending by typing, e.g. "lunch 45"

BUILT AROUND YOUR PRIVACY
• No internet permission — Fils cannot send data anywhere, even if it wanted to
• Everything is stored only in your phone's local database
• No account, no login, no ads
• One-time passcodes (OTPs) are never stored
• Only messages from recognised bank senders are read — personal messages are never touched
• Optional app lock with fingerprint or face unlock
• Local backup and restore, entirely under your control

REPORTS
Export your spending as a PDF or Excel report, by month or by card.

Fils is a personal finance tool for people who want to understand their spending without handing their bank details to another app.

## Category
Finance

## App content declarations (Play Console → Policy → App content)

Every one of these must be completed before any release (including testing tracks) can go out. Answers below
were checked against Google's own definitions on 29 September 2026.

- **Privacy policy:** `https://rjunaidtahir.github.io/fils-privacy/`
- **Ads:** No, the app does not contain ads.
- **App access:** All functionality is available without special access (no account, no login).
- **Content rating (IARC questionnaire):** category "All other app types" (utility/productivity). Answer **No** to
  every content question — violence, fear, sexuality, language, controlled substances, gambling, crude humour —
  and **No** to user-to-user interaction, sharing the user's location, digital purchases, and unrestricted web access.
- **Target audience and content:** 18 and over only. Not designed to appeal to children.
- **Data safety:** "Does your app collect or share any of the required user data types?" → **No.**
  Why this is correct (Google's definitions, Play Console Help answer 10787469):
  - *"'Collect' means transmitting data from your app off a user's device."* Fils never does — it has no internet permission.
  - *"User data accessed by your app that is only processed locally on the user's device and not sent off device
    does not need to be disclosed."*
  - Backups, PDF/Excel reports and the "share text" buttons only leave the phone when the user taps them and picks
    the destination app — Google exempts *"a specific user-initiated action, where the user reasonably expects the data to be shared."*
  - The store listing will then show "No data collected · No data shared".
- **Financial features:** "My app doesn't provide any financial features." Fils offers no loans, payments, banking,
  trading, insurance, credit reporting or financial advice — it only shows the user's own bank SMS, on the device.
- **Advertising ID:** No — the app doesn't use it (no `AD_ID` permission in the built APK, verified).
- **Government app / Health app / News app:** No / none / No.
- **Sensitive permissions → SMS and Call Log:** see the declaration section below.

## Privacy policy URL
Already live (see `store/README.md` for how it's hosted) — paste this into Play Console:
`https://rjunaidtahir.github.io/fils-privacy/`

## SMS permissions declaration — demo video script (~45–60 seconds)

Play's Permissions Declaration Form asks for a short screen recording of the feature that needs the permission.
Record this on a real phone with the Play-bound build installed (screen recorder → Fils → go):

1. **(5s)** Show the phone's home screen, open Fils.
2. **(10s)** Show **More → Bank senders**, scroll the list — makes clear only known bank senders are read.
3. **(10s)** Send yourself (or use a saved draft) a realistic bank SMS, e.g. from a saved contact named like a
   bank sender: `"AED 45.00 spent on your ADCB card ending 4471 at CARREFOUR. Avl limit AED 4,955."` Show the
   SMS arriving in the Messages app.
4. **(10s)** Switch to Fils, tap **Sync**, and show the new transaction appear in **Activity** — same amount,
   merchant and card as the SMS.
5. **(10s)** Open **More → Help & questions**, tap **"Is my data safe?"** to show the on-device-only,
   no-internet-permission answer on screen.
6. **(5s)** End on the **Home** tab showing the updated "Spent this month" total.

Narration (captions or voiceover) to include, matching the declared use case almost word for word: *"Fils reads
SMS from your bank to automatically log your spending and track your budget. It doesn't read personal messages,
doesn't store OTPs, has no internet permission, and never sends data anywhere."*

Upload the recording (unlisted YouTube link or direct file, per Play Console's current option) alongside the
declaration form, and pick **"SMS-based money management — apps that track and manage budget"** as the use case.
