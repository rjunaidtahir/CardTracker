# Replay audit (2.8)

How we know nothing left in the accuracy data needs an app update.

- `AuditReplayTest` (app unit tests, `app/src/test/java/com/uaefinancial/tracker/qa/`) groups every distinct (bank, masked shape),
  fills the placeholders with fixed stand-ins, reads it with the real engine and the shipped `rules/pack.json` (legacy and
  `ext`, safe mode off) and records READ (fields), IGNORED (label hash), FAILED (stage) and UNCERTAIN (the shape has `{TEXT}`).
- Input is never committed. The `audit.yml` workflow decrypts it at run time with the `QA_DATA_KEY` secret:
  `tools/qa/data/real_masked.enc` (the masked export, tab separated) and, when present, `tools/audit/data/sheet.enc`
  (the accuracy sheet CSV: Timestamp, shape, bank, country, kind, app), encrypted the same way
  (`gzip -c sheet.csv | openssl enc -aes-256-cbc -pbkdf2 -pass env:QA_DATA_KEY -out tools/audit/data/sheet.enc`).
  Local run: `AUDIT_INPUT=/path/export.txt:/path/sheet.csv ./gradlew :app:testDebugUnitTest --tests '*AuditReplayTest*'`.
- Output: `app/build/audit-report.json`, `app/build/audit-summary.md`, `app/build/ignore-gate.md` (counts and hashes only).
- The job fails when a group seen unread on a 2.7+ build still FAILS with no lever in `known-levers.json`, when READ groups
  drop below `baseline-counts.json`, or (ignore gate, spec 8.2) when any `ignore` / `ext.ignore` entry of the shipped pack
  matches a message that reads as a transaction.
- After an accepted run, copy the `groups` and `read` counts per input from `audit-report.json` into `baseline-counts.json`.
