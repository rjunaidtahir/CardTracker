#!/usr/bin/env python3
"""Field-reading test: can a small PRETRAINED model find the merchant words in a bank message, on banks it never saw?

Usage: eval_field.py MSET.jsonl     (lines produced by MerchantSetDump: group, text, label, gold, tag, margin, smart)

PROTOCOL, fixed before the first run (not tuned afterwards):
* Data: 255 fixture messages from 22 banks, labelled by the app's verified rules. "gold" = the merchant the rule read, only
  when it is written in the message (128 rows); the other 127 rows have no merchant and are used as all-"no merchant" examples.
  These are reconstructed test fixtures, not real phone messages (real exports hide merchants), so the result is indicative only.
* Folds: whole banks, greedy balance, 5 folds. A model is fine-tuned WITHOUT any message of the bank it is tested on.
* Training: token tagging O / B / I on the merchant, 15 epochs, batch 8, lr per model below, seed 0; each training merchant is
  also swapped for 4 other names (names from the training folds + a fixed list) so the model learns the wording around it.
* Prediction: best merchant span; confidence = mean probability over the span. Headline threshold 0.90 (a sweep is printed for
  information only). Span cleaned by clean_lite (cut at sentence end, before card digits, before a comma).
* Scoring: exact match, case-insensitive. Amount, currency and card digits are NOT tested here: code already reads them
  (round 2: amount 55/55) and the model is never allowed to supply a number.
* Bar for "useful": wrong among answered <= 10% AND answered >= 40% of the 128 gold rows. Baselines printed from the same rows:
  the app's pattern reader and its existing small tagger (leave-one-bank-out, trained the same way as in the app).
* Probe: after the folds, each model is trained on everything and shown three InvestBank-style messages (InvestBank is not
  in the data).
Nothing here is shipped in the app.
"""
import sys, json, re, random, collections, time

SEED = 0
FOLDS = 5
EPOCHS = 15
BATCH = 8
THRESH = 0.90
MODELS = [
    ("google/bert_uncased_L-2_H-128_A-2", 2e-4),
    ("google/bert_uncased_L-4_H-256_A-4", 1e-4),
    ("sentence-transformers/all-MiniLM-L6-v2", 8e-5),
    ("microsoft/MiniLM-L12-H384-uncased", 8e-5),
    ("microsoft/deberta-v3-xsmall", 5e-5),
]
EXTRA_NAMES = ["LULU HYPERMARKET", "SPINNEYS", "NOON.COM", "AMAZON", "TALABAT", "SHELL PETROL", "EMARAT", "ARAMEX", "COSTA COFFEE", "STARBUCKS",
               "DUBAI MALL", "MAJID AL FUTTAIM", "SHARAF DG", "ACE HARDWARE", "IKEA", "H&M", "ZARA HOME", "NETFLIX", "SPOTIFY", "UBER",
               "AL FUTTAIM AUTOMALL", "DR SULAIMAN CLINIC", "MEDICLINIC CITY HOSPITAL", "RAK HOSPITAL", "THE SPA", "MCDONALDS", "SUBWAY",
               "PIZZA HUT", "KFC", "BURGER KING", "ADNOC DISTRIBUTION", "ENOC", "SALIK", "RTA", "DU TELECOM", "ETISALAT", "EMIRATES AIRLINE",
               "FLYDUBAI", "BOOKING.COM", "AIRBNB"]
O, B, I = 0, 1, 2


def clean_lite(s):
    s = s.split(". ")[0]
    s = re.sub(r"\s+[Xx*]{2,}\d*.*$", "", s)
    s = s.split(",")[0] if len(re.sub(r"[^A-Za-z]", "", s.split(",")[0])) >= 2 else s
    s = re.sub(r"\s+", " ", s).strip(" .,-:")
    return s[:50].strip()


def load(path):
    rows = []
    for l in open(path, encoding="utf-8"):
        l = l.strip()
        if l.startswith("MSET {"): rows.append(json.loads(l[5:]))
    return rows


def folds_of(rows):
    size = collections.Counter(r["group"] for r in rows)
    bins = [[] for _ in range(FOLDS)]; load_ = [0] * FOLDS
    for b, c in sorted(size.items(), key=lambda x: -x[1]):
        i = load_.index(min(load_)); bins[i].append(b); load_[i] += c
    return {b: i for i, bs in enumerate(bins) for b in bs}


def make_train(rows, rng):
    names = [r["gold"] for r in rows if r["gold"]] + EXTRA_NAMES
    out = []
    for r in rows:
        out.append((r["text"], r["gold"]))
        if r["gold"]:
            idx = r["text"].lower().find(r["gold"].lower())
            for _ in range(4):
                n = rng.choice(names)
                out.append((r["text"][:idx] + n + r["text"][idx + len(r["gold"]):], n))
    return out


class Tagger:
    def __init__(self, mid, lr):
        import torch
        from transformers import AutoTokenizer, AutoModelForTokenClassification
        self.torch = torch
        torch.manual_seed(SEED)
        self.tok = AutoTokenizer.from_pretrained(mid)
        self.model = AutoModelForTokenClassification.from_pretrained(mid, num_labels=3)
        self.lr = lr

    def _enc(self, text, gold=None):
        e = self.tok(text, truncation=True, max_length=128, return_offsets_mapping=True)
        labels = []
        s0 = text.lower().find(gold.lower()) if gold else -1
        s1 = s0 + len(gold) if gold and s0 >= 0 else -1
        started = False
        for a, b in e["offset_mapping"]:
            if a == b: labels.append(-100); continue
            if s0 >= 0 and a < s1 and b > s0:
                labels.append(I if started else B); started = True
            else: labels.append(O)
        return e, labels

    def fit(self, pairs, rng):
        torch = self.torch
        opt = torch.optim.AdamW(self.model.parameters(), lr=self.lr, weight_decay=0.01)
        data = [self._enc(t, g) for t, g in pairs]
        self.model.train()
        for ep in range(EPOCHS):
            rng.shuffle(data)
            for i in range(0, len(data), BATCH):
                chunk = data[i:i + BATCH]
                L = max(len(e["input_ids"]) for e, _ in chunk)
                ids = torch.tensor([e["input_ids"] + [self.tok.pad_token_id] * (L - len(e["input_ids"])) for e, _ in chunk])
                att = torch.tensor([[1] * len(e["input_ids"]) + [0] * (L - len(e["input_ids"])) for e, _ in chunk])
                lab = torch.tensor([l + [-100] * (L - len(l)) for _, l in chunk])
                loss = self.model(input_ids=ids, attention_mask=att, labels=lab).loss
                loss.backward(); opt.step(); opt.zero_grad()

    def predict(self, text):
        """(span text or '', mean probability of the best span)."""
        torch = self.torch
        self.model.eval()
        e = self.tok(text, truncation=True, max_length=128, return_offsets_mapping=True, return_tensors="pt")
        offs = e.pop("offset_mapping")[0].tolist()
        with torch.no_grad():
            p = torch.softmax(self.model(**e).logits[0], -1)
        tags = p.argmax(-1).tolist()
        best = ("", 0.0); cur = None
        def close():
            nonlocal best, cur
            if cur:
                conf = sum(cur["p"]) / len(cur["p"])
                if conf > best[1]: best = (text[cur["s"]:cur["e"]].strip(), conf)
            cur = None
        for k, (a, b) in enumerate(offs):
            if a == b: continue
            t = tags[k]
            if t == B: close(); cur = {"s": a, "e": b, "p": [float(p[k][B])]}
            elif t == I and cur: cur["e"] = b; cur["p"].append(float(p[k][I]))
            elif t == I: cur = {"s": a, "e": b, "p": [float(p[k][I])]}
            else: close()
        close()
        return best


def ok(gold, got): return bool(got) and got.strip().lower() == gold.strip().lower()


def score(items, label, out):
    """items: list of (gold, answer) for gold rows; answer '' = unanswered."""
    n = len(items); ans = [(g, a) for g, a in items if a]
    right = sum(ok(g, a) for g, a in ans)
    wr = 100 * (len(ans) - right) / len(ans) if ans else 0
    out.append(f"- {label}: answered {len(ans)}/{n}; right {right}, wrong {len(ans) - right}; wrong among answered {wr:.1f}%")


def run_model(mid, lr, rows, fold_of, out):
    t0 = time.time()
    out.append(f"\n### {mid}")
    preds = {}
    for f in range(FOLDS):
        rng = random.Random(SEED)
        train = [r for r in rows if fold_of[r["group"]] != f]
        test = [i for i, r in enumerate(rows) if fold_of[r["group"]] == f]
        m = Tagger(mid, lr)
        m.fit(make_train(train, rng), rng)
        for i in test: preds[i] = m.predict(rows[i]["text"])
        print(f"{mid} fold {f} done {time.time() - t0:.0f}s", flush=True)
    gold_idx = [i for i, r in enumerate(rows) if r["gold"]]
    none_idx = [i for i, r in enumerate(rows) if not r["gold"]]
    for th in (THRESH, 0.5, 0.99):
        items = []
        for i in gold_idx:
            span, c = preds[i]
            a = clean_lite(span) if span and c >= th else ""
            items.append((rows[i]["gold"], a))
        spur = sum(1 for i in none_idx if preds[i][0] and preds[i][1] >= th and clean_lite(preds[i][0]))
        score(items, f"threshold {th}{' (headline)' if th == THRESH else ''}", out)
        out.append(f"  - proposes a merchant on {spur}/{len(none_idx)} messages that have none")
        if th == THRESH:
            gap = [(g, a) for (g, a), i in zip(items, gold_idx) if not rows[i]["smart"]]
            score(gap, "  only the rows where the pattern reader found nothing (where a fallback would be used)", out)
            wrong = [(rows[i]["gold"], a) for (g, a), i in zip(items, gold_idx) if a and not ok(g, a)][:10]
            out.append("  - wrong examples: " + " || ".join(f"gold='{g}' got='{a}'" for g, a in wrong))
    # probe on the InvestBank wording, trained on everything
    rng = random.Random(SEED)
    m = Tagger(mid, lr); m.fit(make_train(rows, rng), rng)
    probes = ["You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
              "You have made a Purchase transaction on your Credit Card ending 1234 from CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card.",
              "You have made a Purchase transaction on your Credit Card ending 1234 at CARREFOUR MALL for AED 120.50.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card."]
    for p in probes:
        s, c = m.predict(p)
        out.append(f"  - probe: found '{clean_lite(s)}' (confidence {c:.2f})")
    out.append(f"  - time {time.time() - t0:.0f}s")


def main():
    rows = load(sys.argv[1])
    fold_of = folds_of(rows)
    out = ["# Field-reading test (merchant), unseen banks", f"rows {len(rows)}, banks {len(fold_of)}, gold {sum(1 for r in rows if r['gold'])}"]
    gold = [r for r in rows if r["gold"]]
    # baselines on the same rows
    items = [(r["gold"], r["smart"]) for r in gold]
    score(items, "Baseline: the app's pattern reader (fixtures were used to develop it, so optimistic)", out)
    items = [(r["gold"], clean_lite(r["tag"]) if r["tag"] and r["margin"] > 0 else "") for r in gold]
    score(items, "Baseline: the app's existing small tagger, margin > 0, leave-one-bank-out, cleaned", out)
    for mid, lr in MODELS:
        try: run_model(mid, lr, rows, fold_of, out)
        except Exception as e: out.append(f"\n### {mid}: could not run ({type(e).__name__}: {str(e)[:200]})")
    text = "\n".join(out)
    print(text)
    open("qa-field-report.md", "w").write(text)


if __name__ == "__main__":
    main()
