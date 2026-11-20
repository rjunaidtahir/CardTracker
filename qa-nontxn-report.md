# Probably-not-a-transaction sorter, blind (leave-one-bank-out)

Gold shapes 4 (KEEP 2, NOT 2), banks 2; threshold P(NOT) >= 0.99.

| variant | threshold | real transactions wrongly flagged (shapes) | NOT found (shapes) |
|---|---|---|---|
| A plain | 0.9 | 0 of 2 (0.00%) | 0 of 2 (0%) |
| A plain | 0.99 | 0 of 2 (0.00%) | 0 of 2 (0%) |
| A plain | 0.999 | 0 of 2 (0.00%) | 0 of 2 (0%) |
| B guard | 0.9 | 0 of 2 (0.00%) | 0 of 2 (0%) |
| B guard | 0.99 | 0 of 2 (0.00%) | 0 of 2 (0%) |
| B guard | 0.999 | 0 of 2 (0.00%) | 0 of 2 (0%) |

## Headline (threshold 0.99) per bank

| bank | KEEP shapes | NOT shapes | KEEP wrongly flagged (A) | NOT found (A) | KEEP wrongly flagged (B) | NOT found (B) |
|---|---|---|---|---|---|---|
| A | 1 | 1 | 0 | 0 | 0 | 0 |
| B | 1 | 1 | 0 | 0 | 0 | 0 |

**A plain: DOES NOT PASS the pre-set bar** (wrongly flagged 0/2 = 0.00%, NOT found 0/2 = 0%, worst bank 0 wrongly flagged; by messages: 0 real wrongly flagged, 0 not-a-transaction flagged).

**B guard: DOES NOT PASS the pre-set bar** (wrongly flagged 0/2 = 0.00%, NOT found 0/2 = 0%, worst bank 0 wrongly flagged; by messages: 0 real wrongly flagged, 0 not-a-transaction flagged).

## Real transactions wrongly flagged (A, 0.99) - masked wording

(none)

## UNREAD shapes (no gold; model trained on all gold): 0 shapes, 0 would be put under 'Probably not a transaction'.


## Probe (hand-masked, model trained without Tabby and RAKBANK)

| message | P(not a transaction) | sorted as |
|---|---|---|
| Tabby code + merchant | 0.626 | stays at top |
| Tabby limit increased | 0.166 | stays at top |
| Tabby code + merchant 2 | 0.626 | stays at top |
| RAKBANK statement, nothing due | 0.089 | stays at top |
| Control: real purchase | 0.000 | stays at top |
| Control: debit | 0.122 | stays at top |