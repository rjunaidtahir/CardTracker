# Fils 2.8 release report

Branch `release/2.8` from `main` (2.7, versionName 2.7, engine 7). Not merged, not published, no rules file published.
versionName 2.8, `SmsParser.ENGINE_VERSION` 7 → 8.

Status: **Stage 1 green; stage 2 in CI** (this file is updated after each stage).

## Merged branches (section 1)

| Branch | Commit | Notes |
|---|---|---|
| `fix/read-unrecognised-sender-by-wording` | dd35e9f | Wording attribution now runs only when the `wordingBanks` lever is on (default OFF, rule 6). |
| `fix/lounge-ignore-yields-to-purchase` | d153060 | as is |
| `loop/collect-sender-and-layout` | 94ac9d8 | Layout tokens replaced by the 2.8 form (`{AMT:1,3.2}`, `{DATE:dd-MON-yy}`); `LayoutProbeTest` updated to it. |

## Where the code differed from the spec (verified, rule 2)

- `ShippedPackV22Test.packCountsAfterV22` already used minimums (`version >= 22`), not `== 22`. All shipped-pack tests were checked;
  none hard-codes a version. Added `ShippedPackBothParsersTest` (legacy 2.7 reader + 2.8 reader).
- There is no encrypted accuracy-sheet CSV in the repo. The audit reads the encrypted masked export used by `qa-real.yml`
  (`tools/qa/data/real_masked.enc`, same `QA_DATA_KEY`) and, when the owner adds it, `tools/audit/data/sheet.enc` (the sheet CSV,
  encrypted the same way; see `tools/audit/README.md`). The export carries no app build, so the "2.7+ unread with no lever" gate
  applies to sheet rows only; the READ-count gate and the ignore gate apply to both.
- `emulator.yml` runs only on pushes to `main`; on this branch it is started by hand (workflow_dispatch) for each stage.
- `BuildConfig` was not enabled in the app; enabled (`buildFeatures.buildConfig = true`) to gate the test export on `BuildConfig.DEBUG`
  (true for debug and beta, which is `initWith(debug)`).
- A 2.7 phone refuses a download larger than 262,144 bytes, so the WHOLE file (legacy + ext) must stay under that, not only the
  legacy part. `legacyLimitProblems`, `ShippedPackBothParsersTest` and the `rules.yml` validation check the whole file.
- The iPhone app has no "Help Fils learn" export or "Last shared" block, so 18.1 is Android only. Share feedback is Android only
  in this release (the spec names `Route.Feedback` / Compose screens).

## Stage 1: what changed

### Rules file `ext` section (section 4)
`RulesPack.parse` reads the legacy sections exactly as 2.7 and then `ext`. A malformed `ext` is ignored as a whole, the legacy part
still applies and `event:ext-rejected` is reported (chosen over "reject all": 2.7-level fixes keep working). Caps in `ext`: 600
ignores, 300 words per list, 100 disables, 150 wordingBanks, 100 ask, 100 notificationBanks, 50 learnedExempt; whole file
524,288 bytes for 2.8 but the shipped file must stay within 262,144 (old phones). Documented in `rules/README.md`.

### Rule language (section 3)
- `{ACCT}` token (ext rules, lever `acctToken`): account numbers with X/*/dashes; the card key is the last 4 digits, or for a
  masked account the shortest right-hand part holding 4 digits (max 12 chars, at least 2 digits), e.g. `123-456789XXXX-01` → `89XXXX01`,
  so two accounts of one bank never merge silently.
- `{CUR}` abbreviations Rs. / SR / KD / Dhs / $ … (lever `curAbbrev`, ext rules only; built-in and legacy rules unchanged).
- New rule fields (ext): `accountNotNamed`, `pairWindowMinutes`, `directionFromBalance`, `needsTwin`, `twinWindowMinutes`, `direction`.
- Multi-word phrases (`phrases`) and `notMoney` words; ignores scoped to a bank with `unless` (`unless`); `learnedExempt`.
- Pack-rule sanity check (section 17): amount range, direction consistent with type, known currency, no credit+debit word clash
  without a named direction; a failure goes to Needs review with stage `SANITY`.

### Reading by wording from unrecognised senders (section 2)
Lever `wordingBanks`. Order: pack `wordingBanks` entry, then built-in wording, then pack rules of every bank; one bank → read;
several agreeing banks → only a card you already have settles it (`SmsParser.knownCard`, fed from your cards); otherwise
`AMBIGUOUS_BANKS` in Needs review. Pack and global ignores apply too. Parse results carry `attributedBank` / `attributedBy`.

### Masker (sections 5, 16.3)
Layout mode keeps every money-looking number as one token with its digit-group layout (`{AMT:1,3.2}`, sign kept), dates as
`{DATE:dd-MON-yy}` etc., never real digits. Ordinary masking unchanged.

### Reports (section 6)
Extra `|key=value` pairs after the unchanged kind (kill switch `ext.levers.reportExtras`, default ON as section 6 says).
New kinds: `cleared`, `event:update-result`, `event:reading-changed`, `event:health`, `event:safe-mode`, `event:ext-rejected`.
Daily cap 300, queue 200, remembered hashes raised 2,000 → 5,000, `unread-again` at most 3 per pack version; a queued unread report
of a shape that clears is dropped, so it is never sent as both. `cleared` and `event:update-result` are sent first when the cap is near.

### User-facing (section 18)
- More → Settings → Help Fils learn: "Last shared" removed (no local copy kept; the old copy is deleted on first start);
  "Export masked messages" only in debug and beta builds.
- More → Help → **Share feedback** (`Route.Feedback`, `FeedbackScreen`, `feedback/FeedbackConfig.kt` with the owner's real form
  constants). Warning at the top (`feedback_warning`), every field optional, anonymous by default, email only in email mode,
  risky digits block sending, 3 sends a day, no background retry. Strings are resources so the owner can edit them.
- **Owner action:** submit one real test from a debug build and confirm each answer lands in the right column (entry order
  Category / Message / Identity / Email / App was assumed from form order).

### Audit and gates (section 8)
`AuditReplayTest` + `tools/audit/` + `.github/workflows/audit.yml` (8.1), ignore regression gate in the same job (8.2), shipped-pack
tests under both readers (8.3), one self-test per lever (`LeverSelfTest`, 8.4).

## Stage 2: what changed (sections 7, 9, 14, 15, 16.4, 16.5; no schema change)

### Merging two messages (section 7)
- **Pack pairs** (lever `pairs`): `pairGroup` / `describesDestination` / `pairWindowMinutes` on `ext.rules` take part in the existing
  pairing (`findTransferPair`/`mergePair`, which already take rule-id lists; pack ids are `pack:<id>`). Built-in FAB pairs are
  unchanged (same rules, same 24 h window); a pack pair also needs the same currency and, when both name a real merchant, a token
  overlap of at least 50%. New rule option `checkUntilPaired`: a message counted on its own is marked Check this until its pair
  arrives (used for the CBD blocked-card notice, 16.4; a plain debit is never marked).
- **SMS + app notification** (lever `crossSourceMerge`): the 2.7 drop (same bank only) is replaced by a merge when the lever is on.
  Candidate: other source, same amount, currency and type, within 10 minutes, same bank OR one side an aggregator (`isAggregator`
  notification banks, and the existing pack bank `Wallet`), same last four when both have one, merchant overlap >= 50% when both
  are real, same destination when both have one. Exactly one candidate merges; more than one keeps all and reports
  `merged:ambiguous`. The row keeps its money; card from the bank side, merchant from a real name (the wallet's when both have
  one), balance from the side that has it, earliest time; both sources recorded (`pairedSmsId` + sms note). An empty auto-created
  wallet card left by the merge is removed. Purchase vs refund never merge (type must match); different amounts never merge.
- `merged:<TYPE>|mg=<fields of row>+<fields of new message>|how=pair|twin` is reported for every merge (sharing on only).
- No Room column was needed (no migration).

### Card key changes (section 9)
`ext.readingPolicy.applyKeyChangeFor` (lever `keyChange`): when a re-read changes ONLY the card key / last four (time and merchant
may differ) and every money field is equal (`ReadingGuard.keyChanges`), the new reading is applied; the new card takes the
settings you gave the old one (name, limit, days, look, counted-or-not, owner, order), then `mergeCards` moves the history and
writes the alias, so no duplicate card appears and later messages for the old digits land on the new card.
`event:key-change|bank=..|n=..` is reported once per re-read. A bank not in the list is held exactly as before.

### Direction from balance (section 14)
Rule option `directionFromBalance` (lever of the same name): the app keeps the balances it has seen per bank/last four/currency;
the direction is fixed only when exactly one of earlier - amount and earlier + amount equals the new balance (within 0.01), the
earlier balance is at most 30 days old and in the same currency. Otherwise the message stays in Needs review with stage
`NO_DIRECTION` (the pack rule matched, so the generic reader is not allowed to guess either). A proven direction is marked
Check this. **Difference from the spec:** the generic reader already reads SIB "Within UAE Funds Transfer" as money out in 2.7
(the word "transfer"), so SIB is not "unreadable" today; the option only applies to a pack rule that names it.

### needsTwin (16.5)
A `needsTwin` rule counts only when it joins a transfer-out of the same bank, amount and currency within `twinWindowMinutes`
(the nearest unpaired one); otherwise it waits in Needs review (stage `NEEDS_TWIN` in the report). A transfer that arrives later
picks up the waiting message. Re-reads end the same way. `SmsParser.holdsTwins` is set only by the Android repository, so the
iPhone build (which has no hold logic) never uses such a rule.

### Wallet money-in (section 15)
`accountNotNamed: true` + `cardType: ACCOUNT` puts the money on the wallet's own account, or exactly one "<Bank> ·????" account
(never one per message); accounts are not counted as spending by default. The audit now warns about a tracked bank with no
ignore entry of its own (`RulesPack.trackedWithoutIgnore`).

### Stage 2 tests
Database tests on the emulator: `PackPairDbTest` (CBD notice in all three orders, pairs lever off, different merchants, FAB pair
with every lever on, needsTwin: twin present / later / never / two equal transfers), `WalletMergeDbTest` (both orders, re-read,
different merchants, purchase vs refund, an hour apart, different amounts, ambiguous, lever off, wallet money-in on one account),
`KeyChangeDbTest` (money unchanged, history and settings kept, unlisted bank held, lever off held). Unit tests:
`ReadingGuardTest.keyChangeOnlyWhenMoneyIsEqual`, `DirectionFromBalanceTest` (7 cases from section 14),
`LeverSelfTest.leverNeedsTwinReadsOnlyWhereTheAppHoldsIt`, `LeverSelfTest.aTrackedWalletWithItsIgnoresDoesNotFloodReview...`.

## Lever table

| lever | pack field | test | default | kill switch |
|---|---|---|---|---|
| wordingBanks | `ext.levers.wordingBanks`, `ext.wordingBanks` | `UnrecognisedSenderWordingTest`, `LeverSelfTest.leverWordingBanks` | OFF | lever false / safeMode |
| acctToken | `ext.rules` with `{ACCT}` | `LeverSelfTest.leverAcctToken`, `CapabilityByBankTest.dibDrawnFromAccountByReason` | OFF | lever false / remove rule |
| curAbbrev | `ext.rules` with `{CUR}` | `LeverSelfTest.leverCurAbbrev` | OFF | lever false |
| phrases | `ext.words.debit/credit/transfer` | `LeverSelfTest.leverPhrases` | OFF | lever false |
| notMoney | `ext.words.notMoney` | `LeverSelfTest.leverNotMoney` | OFF | lever false |
| unless | `ext.ignore[].bank/unless` | `LeverSelfTest.leverUnlessWithAScopedIgnore` | OFF | lever false / remove entry |
| pairs | `ext.rules[].pairGroup/describesDestination/pairWindowMinutes/checkUntilPaired` | `LeverSelfTest.leverPairsDeclaresAPairGroupOnAPackRule`, `PackPairDbTest` | OFF | lever false |
| notificationBanks | `ext.notificationBanks` | `LeverSelfTest.leverNotificationBanks` | OFF | lever false |
| keyChange | `ext.readingPolicy.applyKeyChangeFor` | `LeverSelfTest.leverKeyChange`, `KeyChangeDbTest` | OFF | lever false / remove bank |
| ask | `ext.ask` | `LeverSelfTest.leverAsk`, `ReportFieldsTest.askEntriesMatchByBankSenderOrHashAndExpire` | OFF | lever false / `until` |
| learnedExempt | `ext.learnedExempt` | `LeverSelfTest.leverLearnedExempt` | OFF | lever false |
| reportExtras | `ext.levers.reportExtras` | `ReportFieldsTest` | ON | lever false |
| safeMode | `ext.safeMode` | `LeverSelfTest.safeModeIsThePackWideKillSwitch` | off | is the kill switch |
| crossSourceMerge | `ext.levers.crossSourceMerge` | `WalletMergeDbTest` | OFF | lever false (2.7 drop of same-bank twins returns) |
| directionFromBalance | `ext.rules[].directionFromBalance` | `DirectionFromBalanceTest` | OFF | lever false (such rules are dropped) |
| needsTwin | `ext.rules[].needsTwin/twinWindowMinutes` | `LeverSelfTest.leverNeedsTwinReadsOnlyWhereTheAppHoldsIt`, `PackPairDbTest` | OFF | lever false (such rules are dropped) |
| hsbcLivePlusNames | stage 3 | stage 3 | ON | lever false |

## Data safety field list (section 20, draft; final after stage 3)

Message-shape reports (only when sharing is ON; masked shape, bank, country, kind, app version, unchanged form fields). New keys
inside `kind`: `sid` (shape hash), `pv` (rules version), `ev` (engine version), `st` (why unread, e.g. `NO_DIRECTION`), `fr`
(letters of fields read, e.g. `ACK`), `rule` (rule id or ignore label hash), `sc`/`len`/`nb` (sender class such as
`USER_NAMED_CHAT`, length bucket such as `7-10`, built-in bank name the sender spells; never a contact or chat name), `ab`
(bank named by wording), ask-only `amt` (amount layout such as `3.2;1,3.2-`), `w`/`wc` (wallet name, card name shape such as
`Visa_Signature:4`). New kinds (counts only): `cleared|sid=..|pv=..|rule=..|n=3`, `event:update-result|pv=27|cleared=4|still=2|held=0|hidden=1|merged=0`,
`event:reading-changed|pv=27|n=1`, `event:health|day=2026-11-24|seen=12|confident=10|review=1|held=0|hidden=1|merged=0|byBank=ADCB:5,HSBC:3`,
`event:safe-mode|pv=27`, `event:ext-rejected|pv=27|why=..`, `event:key-change|bank=ADCB|n=3`, `merged:<TYPE>|mg=ACKMB+ACKMT|how=twin`
(field letters only), `merged:ambiguous|n=2`.

Feedback (new data collection, user-initiated): user-generated content (feedback text, optional), email address (optional, only in
email mode; purpose: replying), category, "anonymous"/"email", app version + engine version. **Owner action:** update the in-app
privacy text and the Play Data safety form (rule A10).

## NOT TESTED (phone-only paths)

- Feedback POST to the real form from a phone (owner to send one test from a debug build).
- `cleared` / `event:update-result` / `event:health` sending on a real phone with sharing on (logic unit-tested; the flow needs a phone and a rules update).
- Notification banks on a real wallet app notification.
- A real wallet notification and bank SMS arriving for one purchase (merge logic tested on the emulator with invented wordings).
- A real key change after a rules update on a phone that already has the old card (tested on the emulator by changing the stored text).

## Audit (first run, stage 1 head 2e4a5d7)

Export input: 908 rows, 902 groups: READ 733, IGNORED 70, FAILED 99 (all FAILED groups are "uncertain": the masked shape holds
`{TEXT}` or a masked decisive word). Failed stages: NO_DIRECTION 86 (lever directionFromBalance), NO_AMOUNT 6, NO_CARD 3 (acctToken),
NO_BANK 3 (wordingBanks), UNKNOWN 1. No 2.7+ unread group without a lever. Ignore gate: 47 pack ignores against 722 groups that
read as a transaction: none hidden. Baseline recorded in `tools/audit/baseline-counts.json` (export: 902 groups, 733 READ).

## CI

Stage 1 (head 2e4a5d7): [Build APK](https://github.com/rjunaidtahir/CardTracker/actions/runs/45388955372) green,
[Replay audit](https://github.com/rjunaidtahir/CardTracker/actions/runs/45389863041) green,
[Emulator](https://github.com/rjunaidtahir/CardTracker/actions/runs/45389872608), [iPhone](https://github.com/rjunaidtahir/CardTracker/actions/runs/45389911181).
