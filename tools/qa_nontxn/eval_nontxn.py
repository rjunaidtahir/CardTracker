#!/usr/bin/env python3
"""Blind test of a tiny "probably not a transaction" sorter on REAL masked messages (owner's export). Stdlib only.

Usage: eval_nontxn.py EXPORT.txt

PRE-REGISTERED (written and pushed before the first run; nothing tuned afterwards):
- Rows: same gold as eval_real.py (verified rule / signed pack / user fix, plus NOT_TXN rows decided by ignore rules). UNREAD excluded.
- Classes: NOT = kind NOT_TXN. KEEP = every other gold kind (purchase, refund, payment, transfers, STATEMENT: a statement is never "not a transaction").
- Model: multinomial naive Bayes over word uni+bigrams of the masked shape (presence), add-one smoothing, equal priors. Small enough to
  train at app start-up from sample messages (no model file).
- Blind: leave-one-bank-out (every bank held out in turn; the model never sees a message of the bank it predicts).
- Headline threshold: flag only when P(NOT) >= 0.99. Variant B adds a guard: never flag a shape that has an amount, a card/account token AND a
  money verb (debited, credited, spent, purchase, used, paid, withdrawn, transfer, refund).
- BAR ("safe to use as sorting only, nothing hidden"): real transactions wrongly flagged <= 0.5% of KEEP shapes AND >= 50% of NOT shapes flagged,
  on the headline threshold; the worst single bank must also have no more than 2 wrongly flagged KEEP shapes.
- RUN HISTORY: run 1 had a scoring bug (unseen words counted towards NOT: 44% of real transactions flagged); fixed to the plain multinomial NB above
  with the bar, threshold and variants unchanged. Both runs are reported.
- Probe: the 4 messages from the owner's screenshot (20 Nov), shapes written by hand in the Masker's style (approximate), model trained
  WITHOUT Tabby and RAKBANK. Informational, not part of the bar.
"""
import sys, math, collections, re

THRESH = 0.99
KEEP_KINDS = {"PURCHASE", "REFUND", "PAYMENT", "TRANSFER_OUT", "TRANSFER_IN", "STATEMENT"}
VERBS = re.compile(r"\b(debited|credited|spent|purchase|purchased|used|paid|withdrawn|transfer|transferred|refund|refunded)\b")

def load(path):
    rows, unread = [], []
    for l in open(path, encoding="utf-8"):
        if l.startswith("#") or not l.strip(): continue
        n, bank, kind, direction, instr, source, shape = l.rstrip("\n").split("\t")
        r = dict(n=int(n), bank=bank, kind=kind, shape=shape)
        if kind == "UNREAD": unread.append(r); continue
        if not (source in ("rule", "pack", "user-fix") or (kind == "NOT_TXN" and source == "none")): continue
        rows.append(r)
    return rows, unread

TOK = re.compile(r"\{[A-Z]+(?::\d+)?\}|[a-z]+(?:'[a-z]+)?")
def feats(shape):
    w = [t if t.startswith("{") else t for t in TOK.findall(shape.replace("{", " {").lower().replace("{amount", "{AMOUNT").replace("{cur", "{CUR").replace("{card", "{CARD").replace("{text", "{TEXT").replace("{date", "{DATE").replace("{time", "{TIME"))]
    return set(w) | {a + " " + b for a, b in zip(w, w[1:])}

def train(rows):
    cnt = {"NOT": collections.Counter(), "KEEP": collections.Counter()}
    for r in rows:
        c = "NOT" if r["kind"] == "NOT_TXN" else "KEEP"
        for f in feats(r["shape"]): cnt[c][f] += 1
    vocab = set(cnt["NOT"]) | set(cnt["KEEP"])
    tot = {c: sum(cnt[c].values()) for c in cnt}
    return cnt, tot, vocab

def p_not(model, shape):
    """Standard multinomial naive Bayes, add-one smoothing over the training vocabulary, equal priors; words never seen in training are ignored."""
    cnt, tot, vocab = model
    V = len(vocab); lo = 0.0
    for f in feats(shape):
        if f not in vocab: continue
        lo += math.log((cnt["NOT"][f] + 1) / (tot["NOT"] + V)) - math.log((cnt["KEEP"][f] + 1) / (tot["KEEP"] + V))
    lo = max(-50, min(50, lo))
    return 1 / (1 + math.exp(-lo))

def guarded(shape):
    s = shape.lower()
    return "{amount}" in s and ("{card" in s or "account" in s) and bool(VERBS.search(s))

def main(path):
    rows, unread = load(path)
    banks = sorted({r["bank"] for r in rows})
    out = []
    P = lambda s="": out.append(s)
    nk = sum(r["kind"] != "NOT_TXN" for r in rows); nn = len(rows) - nk
    P(f"# Probably-not-a-transaction sorter, blind (leave-one-bank-out)\n\nGold shapes {len(rows)} (KEEP {nk}, NOT {nn}), banks {len(banks)}; threshold P(NOT) >= {THRESH}.\n")
    pred = {}
    for b in banks:
        m = train([r for r in rows if r["bank"] != b])
        for i, r in enumerate(rows):
            if r["bank"] == b: pred[i] = p_not(m, r["shape"])
    def tally(th, use_guard):
        keep_flag = not_flag = 0; per = collections.defaultdict(lambda: [0, 0, 0, 0]); mk = mn = 0
        for i, r in enumerate(rows):
            flag = pred[i] >= th and not (use_guard and guarded(r["shape"]))
            isnot = r["kind"] == "NOT_TXN"; p = per[r["bank"]]
            p[1 if isnot else 0] += 1
            if flag:
                if isnot: not_flag += 1; p[3] += 1; mn += r["n"]
                else: keep_flag += 1; p[2] += 1; mk += r["n"]
        return keep_flag, not_flag, per, mk, mn
    P("| variant | threshold | real transactions wrongly flagged (shapes) | NOT found (shapes) |\n|---|---|---|---|")
    for g in (False, True):
        for th in (0.9, 0.99, 0.999):
            kf, nf, _, mk, mn = tally(th, g)
            P(f"| {'B guard' if g else 'A plain'} | {th} | {kf} of {nk} ({100*kf/nk:.2f}%) | {nf} of {nn} ({100*nf/max(nn,1):.0f}%) |")
    P("\n## Headline (threshold 0.99) per bank\n\n| bank | KEEP shapes | NOT shapes | KEEP wrongly flagged (A) | NOT found (A) | KEEP wrongly flagged (B) | NOT found (B) |\n|---|---|---|---|---|---|---|")
    _, _, perA, mkA, mnA = tally(THRESH, False); _, _, perB, mkB, mnB = tally(THRESH, True)
    worstA = worstB = 0
    for b in banks:
        a, bb = perA[b], perB[b]; worstA = max(worstA, a[2]); worstB = max(worstB, bb[2])
        P(f"| {b} | {a[0]} | {a[1]} | {a[2]} | {a[3]} | {bb[2]} | {bb[3]} |")
    for name, g, worst in (("A plain", False, worstA), ("B guard", True, worstB)):
        kf, nf, _, mk, mn = tally(THRESH, g)
        ok = kf <= 0.005 * nk and nf >= 0.5 * nn and worst <= 2
        P(f"\n**{name}: {'PASSES' if ok else 'DOES NOT PASS'} the pre-set bar** (wrongly flagged {kf}/{nk} = {100*kf/nk:.2f}%, NOT found {nf}/{nn} = {100*nf/max(nn,1):.0f}%, worst bank {worst} wrongly flagged; by messages: {mk} real wrongly flagged, {mn} not-a-transaction flagged).")
    # which real transactions get wrongly flagged (headline, A)
    P("\n## Real transactions wrongly flagged (A, 0.99) - masked wording\n")
    shown = 0
    for i, r in enumerate(rows):
        if r["kind"] != "NOT_TXN" and pred[i] >= THRESH:
            P(f"- {r['bank']} / {r['kind']} / {r['n']} msg: {r['shape'][:160]}"); shown += 1
            if shown >= 25: break
    if not shown: P("(none)")
    # unread shapes
    full = train(rows)
    fl = [r for r in unread if p_not(full, r["shape"]) >= THRESH]
    P(f"\n## UNREAD shapes (no gold; model trained on all gold): {len(unread)} shapes, {len(fl)} would be put under 'Probably not a transaction'.\n")
    for r in fl[:15]: P(f"- {r['bank']} / {r['n']} msg: {r['shape'][:160]}")
    # probe
    pm = train([r for r in rows if r["bank"] not in ("Tabby", "RAKBANK")])
    probes = [
        ("Tabby code + merchant", "Do not tell anyone this code: {AMOUNT}. {TEXT}. {CUR} {AMOUNT}"),
        ("Tabby limit increased", "Your Tabby Card total limit is now increased to {CUR} {AMOUNT}"),
        ("Tabby code + merchant 2", "Do not tell anyone this code: {AMOUNT}. {TEXT}. {CUR} {AMOUNT}"),
        ("RAKBANK statement, nothing due", "Your CC Statement for {CARD} is generated; CC has a credit balance of {CUR} {AMOUNT} with Min Due {CUR} {AMOUNT}"),
        ("Control: real purchase", "Your credit card ending {CARD} was used for {CUR} {AMOUNT} at {TEXT} on {DATE}. Available limit is {CUR} {AMOUNT}"),
        ("Control: debit", "{CUR} {AMOUNT} was debited from your account {CARD} on {DATE}"),
    ]
    P("\n## Probe (hand-masked, model trained without Tabby and RAKBANK)\n\n| message | P(not a transaction) | sorted as |\n|---|---|---|")
    for name, s in probes:
        p = p_not(pm, s); P(f"| {name} | {p:.3f} | {'Probably not a transaction' if p >= THRESH and not guarded(s) else 'stays at top'} |")
    txt = "\n".join(out); print(txt); open("qa-nontxn-report.md", "w").write(txt)

if __name__ == "__main__":
    main(sys.argv[1])
