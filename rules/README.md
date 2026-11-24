# The signed rules file (`rules/pack.json`)

Data only, signed by the `rules.yml` workflow from `main`, downloaded by every phone about once a day. Phones on 2.6, 2.7
and 2.8 all read the same file at the same address, so every entry must be placed where each version can live with it.

## Where an entry goes

| Entry uses... | Put it in | Why |
|---|---|---|
| only 2.7 tokens and fields (`{CUR}`, `{AMOUNT}`, `{CARD}`, `{MERCHANT}`, ... `fixedMerchant`, `pairGroup`, `describesDestination`) | the legacy sections: `rules`, `ignore`, `words`, `banks`, `sendMore`, `disable`, `disableIgnore`, `readingPolicy` | every phone reads them |
| `{ACCT}`, `{CUR}` abbreviations (Rs. SR KD $), `accountNotNamed`, `pairWindowMinutes`, `directionFromBalance`, `needsTwin`, `twinWindowMinutes`, `checkUntilPaired`, `direction` | `ext.rules` | a 2.6/2.7 phone would fail to compile the pattern and reject the WHOLE file |
| an ignore with `bank`, `unless` or `minWords` | `ext.ignore` | same |
| several-word phrases, `notMoney` words | `ext.words` | same |
| `wordingBanks`, `notificationBanks`, `ask`, `learnedExempt`, `readingPolicy.applyKeyChangeFor`, `levers`, `safeMode` | `ext` | 2.8 only |

2.6/2.7 phones ignore the unknown top-level key `ext`; 2.8 reads the legacy sections first, then applies `ext` on top.

## Limits that protect older phones

The WHOLE file (legacy and `ext` together) must stay within what a 2.7 phone accepts: at most 262,144 bytes, at most 200
legacy `ignore` entries, 100 words per legacy word list, 20 `disable` entries. `ext` has its own, larger caps (600 ignores,
300 words, 150 wordingBanks, 100 ask, 100 notificationBanks, 50 learnedExempt). `ShippedPackBothParsersTest` (app unit
tests) loads the shipped file under a frozen copy of the 2.7 reader and under 2.8 and fails if either refuses it.

## Levers (all OFF unless the file turns them on)

Every 2.8 capability is switched by `ext.levers.<name>`; `false` (or absent) behaves exactly as 2.7. Exceptions that default
ON: `hsbcLivePlusNames` (statement reader) and `reportExtras` (extra report keys); set them to `false` to switch off.
`ext.safeMode: true` is the pack-wide kill switch: every rule, ignore, word and wordingBank from the file (legacy and ext) is
ignored and the app reads with its built-in rules only.

## A bad `ext`

If `ext` is malformed, 2.8 keeps the legacy part, ignores the whole `ext` and reports `event:ext-rejected`. The legacy
fixes keep working.

## Before publishing

`AuditReplayTest` / `audit.yml` replays the accuracy data with the file and fails if an ignore hides a message that reads as
money, or if fewer shapes read than before. Never publish without the owner's go.
