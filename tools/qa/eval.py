#!/usr/bin/env python3
"""Blind measurement of pretrained extractive question-answering models on bank SMS.

Usage: eval.py QASET.jsonl MODEL_ID [MODEL_ID ...]

The model only ever returns a span of the message (it never writes numbers). Code maps the span to an answer.
Everything below the "FIXED BEFORE RUNNING" line was committed before any result was seen and is not tuned afterwards.
Gold labels come from verified bank rules (see QaTestsetDump.kt); Tabby is two real messages labelled by hand.
Dev/blind split: sum of the bank name's character codes, even = dev, odd = blind. Nothing is tuned on either half.
"""
import json, re, sys, datetime, collections

# ---------------------------- FIXED BEFORE RUNNING ----------------------------
THRESH = 0.30  # a span counts as an answer only above this model score; below = abstain
Q = {
    "amount": "What is the amount of the transaction?",
    "action": "What happened to the money?",
    "instrument": "Which card or account was used?",
    "merchant": "Where was the money spent or paid to?",
    "total": "What is the total amount of the statement?",
    "min": "What is the minimum amount due?",
    "due": "On what date is the payment due or charged?",
}
OUT_W = re.compile(r"debit|spent|paid|purchase|withdr|deduct|charged|sent|transfer|used|payment to", re.I)
IN_W = re.compile(r"receiv|refund|deposit|revers|added|reimburs|credited|credit to|returned", re.I)
MONTHS = {m: i + 1 for i, m in enumerate(["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"])}
# -----------------------------------------------------------------------------


def num(s):
    m = re.search(r"\d[\d,]*(?:\.\d+)?", s or "")
    return float(m.group(0).replace(",", "")) if m else None


def parse_date(s):
    s = s or ""
    m = re.search(r"(\d{1,2})[\s/\-.]+(\d{1,2})[\s/\-.,]+(\d{4})", s)
    try:
        if m:
            return (datetime.date(int(m.group(3)), int(m.group(2)), int(m.group(1))) - datetime.date(1970, 1, 1)).days
        m = re.search(r"(\d{1,2})[\s/\-.]*([A-Za-z]{3})[a-z]*[\s,.\-]*(\d{4})", s)
        if m and m.group(2).lower() in MONTHS:
            return (datetime.date(int(m.group(3)), MONTHS[m.group(2).lower()], int(m.group(1))) - datetime.date(1970, 1, 1)).days
        m = re.search(r"([A-Za-z]{3})[a-z]*\s+(\d{1,2})[\s,.\-]*(\d{4})", s)
        if m and m.group(1).lower() in MONTHS:
            return (datetime.date(int(m.group(3)), MONTHS[m.group(1).lower()], int(m.group(2))) - datetime.date(1970, 1, 1)).days
    except ValueError:
        pass
    return None


def direction_of(span):
    o, i = bool(OUT_W.search(span or "")), bool(IN_W.search(span or ""))
    return "out" if o and not i else "in" if i and not o else None


def instrument_of(span):
    s = (span or "").lower()
    if re.search(r"credit card|credit", s) and not re.search(r"debit", s): return "CREDIT"
    if re.search(r"debit", s) and not re.search(r"credit", s): return "DEBIT"
    if re.search(r"account|a/c|acct|\biban\b", s): return "ACCOUNT"
    return None


def norm(s):
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def merchant_ok(span, gold):
    a, b = norm(span), norm(gold)
    return bool(a and b and (a in b or b in a))


def split(group):
    return "dev" if sum(ord(c) for c in group) % 2 == 0 else "blind"


class Stat:
    def __init__(self): self.n = self.ans = self.right = self.wrong = 0
    def add(self, answered, ok):
        self.n += 1
        if answered:
            self.ans += 1
            if ok: self.right += 1
            else: self.wrong += 1
    def row(self):
        return f"{self.right}/{self.n} right, {self.wrong} wrong, {self.n - self.ans} abstained"


def run_model(name, rows, ask):
    stats = collections.defaultdict(Stat)
    errors = []
    for r in rows:
        body, sp = r["body"], split(r["group"])
        tabby = r["group"] == "Tabby"
        a = {k: ask(q, body) for k, q in Q.items()}  # (text, score)
        def got(k):
            t, s = a[k]
            return t if t and s >= THRESH else None
        def rec(task, answered, ok, detail=""):
            for tag in ([sp] + (["tabby"] if tabby else [])):
                stats[(task, tag)].add(answered, ok)
            if answered and not ok: errors.append({"model": name, "task": task, "group": r["group"], "body": body, "detail": detail})
        # 1. message type: statement / transaction / not a transaction
        stmt_sig = got("min") is not None and num(got("min")) is not None
        txn_sig = got("amount") is not None and num(got("amount")) is not None
        pred = "STATEMENT" if stmt_sig else "TRANSACTION" if txn_sig else "NOT_TXN"
        gold = "STATEMENT" if r["kind"] == "STATEMENT" else "NOT_TXN" if r["kind"] == "NOT_TXN" else "TRANSACTION"
        rec("type (statement/transaction/not)", True, pred == gold, f"gold={gold} pred={pred}")
        if gold == "NOT_TXN":
            rec("false alarm: not-a-transaction read as money", True, pred == "NOT_TXN", f"pred={pred}")
        if r["kind"] not in ("STATEMENT", "NOT_TXN"):
            v = num(got("amount"))
            rec("transaction amount", v is not None, v is not None and abs(v - float(r["amount"])) < 0.005, f"{got('amount')} vs {r['amount']}")
            if r["direction"]:
                d = direction_of(got("action"))
                rec("debit vs credit (money out vs in)", d is not None, d == r["direction"], f"{got('action')} vs {r['direction']}")
            if r["instrument"]:
                i = instrument_of(got("instrument"))
                rec("credit card vs debit card vs account", i is not None, i == r["instrument"], f"{got('instrument')} vs {r['instrument']}")
            if r["merchant"]:
                m = got("merchant")
                rec("merchant", m is not None, m is not None and merchant_ok(m, r["merchant"]), f"{m} vs {r['merchant']}")
        if r["kind"] == "STATEMENT":
            for k, f in (("total", "total"), ("min", "min")):
                if r[f] is not None:
                    v = num(got(k))
                    rec(f"statement {k}", v is not None, v is not None and abs(v - float(r[f])) < 0.005, f"{got(k)} vs {r[f]}")
            if r["due"] is not None:
                d = parse_date(got("due"))
                rec("statement due date", d is not None, d == r["due"], f"{got('due')} vs {r['due']}")
    return stats, errors


def keyword_baseline(rows):
    """Plain keyword matching on the whole message with the same word lists, for comparison."""
    stats = collections.defaultdict(Stat)
    for r in rows:
        sp = split(r["group"]); tags = [sp] + (["tabby"] if r["group"] == "Tabby" else [])
        if r["kind"] not in ("STATEMENT", "NOT_TXN"):
            if r["direction"]:
                d = direction_of(r["body"])
                for t in tags: stats[("debit vs credit (money out vs in)", t)].add(d is not None, d == r["direction"])
            if r["instrument"]:
                i = instrument_of(r["body"])
                for t in tags: stats[("credit card vs debit card vs account", t)].add(i is not None, i == r["instrument"])
    return stats


def report(title, stats, out):
    out.append(f"\n### {title}\n")
    out.append("| task | dev | blind | Tabby |")
    out.append("|---|---|---|---|")
    tasks = sorted({k[0] for k in stats})
    for t in tasks:
        cells = [stats[(t, tag)].row() if (t, tag) in stats else "-" for tag in ("dev", "blind", "tabby")]
        out.append(f"| {t} | " + " | ".join(cells) + " |")


def main():
    rows = [json.loads(l[len("QASET "):]) for l in open(sys.argv[1]) if l.startswith("QASET {")]
    out = [f"## QA measurement: {len(rows)} labelled messages (dev {sum(split(r['group'])=='dev' for r in rows)}, blind {sum(split(r['group'])=='blind' for r in rows)}, Tabby {sum(r['group']=='Tabby' for r in rows)}); answer threshold {THRESH}"]
    out.append("kinds: " + ", ".join(f"{k}={v}" for k, v in collections.Counter(r["kind"] for r in rows).items()))
    report("Baseline: plain keywords on the whole message (same word lists, no model)", keyword_baseline(rows), out)
    all_errors = []
    from transformers import pipeline
    for mid in sys.argv[2:]:
        try:
            pipe = pipeline("question-answering", model=mid, device=-1)
        except Exception as e:  # a model that will not load is reported, not hidden
            out.append(f"\n### {mid}: could not be loaded ({type(e).__name__}: {str(e)[:200]})")
            continue
        def ask(q, body):
            try:
                a = pipe(question=q, context=body, handle_impossible_answer=True)
                return a["answer"], float(a["score"])
            except Exception:
                return "", 0.0
        stats, errs = run_model(mid, rows, ask)
        report(mid, stats, out)
        all_errors += errs
    text = "\n".join(out)
    print(text)
    open("qa-report.md", "w").write(text)
    json.dump(all_errors, open("qa-errors.json", "w"), indent=1)


if __name__ == "__main__":
    main()
