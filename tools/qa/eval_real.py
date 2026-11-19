#!/usr/bin/env python3
"""Blind test of small pretrained encoders on REAL masked messages exported from the owner's phone.

Usage: eval_real.py EXPORT.txt
Fixed before running (not tuned afterwards): gold rows, bank-group folds, hyper-parameters, abstain threshold 0.90.
Gold = rows decided by a verified bank rule, the signed pack or a user fix (never by the generic reader), plus not-a-transaction
rows (decided by ignore rules). Every shape is predicted by a model that was fine-tuned WITHOUT any message from its bank
(4 folds of whole banks). Nothing here is shipped in the app.
"""
import sys, json, collections, random
sys.path.insert(0, __import__("os").path.dirname(__file__))
from eval import direction_of, instrument_of

THRESH = 0.90
FOLDS = 4
EPOCHS = 12
SEED = 0
TYPES = ["PURCHASE", "REFUND", "PAYMENT", "TRANSFER_OUT", "TRANSFER_IN", "STATEMENT", "NOT_TXN"]
INSTR = ["CREDIT", "DEBIT", "ACCOUNT"]
DIR_OF = {"PURCHASE": "out", "TRANSFER_OUT": "out", "REFUND": "in", "TRANSFER_IN": "in", "PAYMENT": "payment", "STATEMENT": "none", "NOT_TXN": "none"}
MODELS = [  # (id, learning rate)
    ("google/bert_uncased_L-2_H-128_A-2", 2e-4),
    ("google/bert_uncased_L-4_H-256_A-4", 1e-4),
    ("sentence-transformers/all-MiniLM-L6-v2", 5e-5),
    ("microsoft/MiniLM-L12-H384-uncased", 5e-5),
    ("microsoft/deberta-v3-xsmall", 5e-5),
]


def load(path):
    rows = []
    for l in open(path, encoding="utf-8"):
        if l.startswith("#") or not l.strip(): continue
        n, bank, kind, direction, instr, source, shape = l.rstrip("\n").split("\t")
        if kind == "UNREAD": continue
        if not (source in ("rule", "pack", "user-fix") or (kind == "NOT_TXN" and source == "none")): continue
        rows.append(dict(n=int(n), bank=bank, kind=kind, instr=instr, source=source, shape=shape))
    return rows


def folds_of(rows):
    size = collections.Counter(r["bank"] for r in rows)
    bins = [[] for _ in range(FOLDS)]; load_ = [0] * FOLDS
    for b, c in sorted(size.items(), key=lambda x: -x[1]):
        i = load_.index(min(load_)); bins[i].append(b); load_[i] += c
    return {b: i for i, bs in enumerate(bins) for b in bs}


class Tally:
    def __init__(self): self.right = self.wrong = self.abst = 0; self.wr = self.ww = self.wa = 0
    def add(self, answered, ok, w):
        if not answered: self.abst += 1; self.wa += w
        elif ok: self.right += 1; self.wr += w
        else: self.wrong += 1; self.ww += w
    def row(self):
        n = self.right + self.wrong + self.abst
        conf = self.right + self.wrong
        rate = f"{100*self.wrong/conf:.1f}%" if conf else "-"
        return f"{self.right} right, {self.wrong} wrong, {self.abst} abstained of {n}; wrong among answered {rate}; (by messages: {self.wr}/{self.ww}/{self.wa})"


def score(rows, pred_type, pred_instr, title, out, types=True):
    """pred_type[i] -> (label, prob) ; pred_instr[i] -> (label, prob) or None."""
    t = collections.defaultdict(Tally)
    for i, r in enumerate(rows):
        lab, p = pred_type[i]
        ans = lab is not None and p >= THRESH
        if types:
            t["type (7 kinds)"].add(ans, ans and lab == r["kind"], r["n"])
            t[f"type, class {r['kind']}"].add(ans, ans and lab == r["kind"], r["n"])
        gd = DIR_OF[r["kind"]]
        if gd in ("out", "in"):
            pd = DIR_OF.get(lab) if ans else None
            dans = pd in ("out", "in")
            t["money out vs in"].add(dans, dans and pd == gd, r["n"])
        if r["instr"] in INSTR:
            il, ip = pred_instr[i]
            ia = il is not None and ip >= THRESH
            t["credit card / debit card / account"].add(ia, ia and il == r["instr"], r["n"])
        if types and r["source"] == "user-fix":
            t["tricky: rows you fixed by hand (type)"].add(ans, ans and lab == r["kind"], r["n"])
        if types and r["bank"] == "Tabby":
            t["Tabby (type)"].add(ans, ans and lab == r["kind"], r["n"])
    out.append(f"\n### {title}\n")
    for k in sorted(t, key=lambda s: (not s.startswith("type (7"), s)):
        out.append(f"- **{k}**: {t[k].row()}")


def keyword(rows):
    pt, pi = [], []
    for r in rows:
        d = direction_of(r["shape"])
        # keyword baseline can only say out / in; map to a representative type for the direction score
        pt.append(({"out": "PURCHASE", "in": "REFUND"}.get(d), 1.0))
        pi.append((instrument_of(r["shape"]), 1.0))
    return pt, pi


def majority(rows):
    mt = collections.Counter(r["kind"] for r in rows).most_common(1)[0][0]
    mi = collections.Counter(r["instr"] for r in rows if r["instr"] in INSTR).most_common(1)[0][0]
    return [(mt, 1.0)] * len(rows), [(mi, 1.0)] * len(rows)


def run_model(mid, lr, rows, fold_of, out):
    import torch, torch.nn as nn
    from transformers import AutoTokenizer, AutoModel
    torch.manual_seed(SEED); random.seed(SEED)
    tok = AutoTokenizer.from_pretrained(mid)
    base0 = AutoModel.from_pretrained(mid)
    nparams = sum(p.numel() for p in base0.parameters())
    pred_t = [None] * len(rows); pred_i = [None] * len(rows)
    for f in range(FOLDS):
        tr = [i for i, r in enumerate(rows) if fold_of[r["bank"]] != f]
        te = [i for i, r in enumerate(rows) if fold_of[r["bank"]] == f]
        if not te: continue
        base = AutoModel.from_pretrained(mid)
        h = base.config.hidden_size
        ht, hi = nn.Linear(h, len(TYPES)), nn.Linear(h, len(INSTR))
        params = list(base.parameters()) + list(ht.parameters()) + list(hi.parameters())
        opt = torch.optim.AdamW(params, lr=lr, weight_decay=0.01)
        def enc(idx):
            b = tok([rows[i]["shape"] for i in idx], padding=True, truncation=True, max_length=128, return_tensors="pt")
            o = base(**b).last_hidden_state
            m = b["attention_mask"].unsqueeze(-1).float()
            return (o * m).sum(1) / m.sum(1)
        base.train()
        for ep in range(EPOCHS):
            random.shuffle(tr)
            for s in range(0, len(tr), 16):
                idx = tr[s:s + 16]
                z = enc(idx)
                yt = torch.tensor([TYPES.index(rows[i]["kind"]) for i in idx])
                loss = nn.functional.cross_entropy(ht(z), yt)
                yi = [INSTR.index(rows[i]["instr"]) if rows[i]["instr"] in INSTR else -100 for i in idx]
                if any(v >= 0 for v in yi):
                    loss = loss + nn.functional.cross_entropy(hi(z), torch.tensor(yi), ignore_index=-100)
                opt.zero_grad(); loss.backward(); opt.step()
        base.eval()
        with torch.no_grad():
            for s in range(0, len(te), 32):
                idx = te[s:s + 32]
                z = enc(idx)
                pt, it = torch.softmax(ht(z), -1), torch.softmax(hi(z), -1)
                for k, i in enumerate(idx):
                    a = int(pt[k].argmax()); b = int(it[k].argmax())
                    pred_t[i] = (TYPES[a], float(pt[k][a])); pred_i[i] = (INSTR[b], float(it[k][b]))
        print(f"{mid} fold {f} done", flush=True)
    score(rows, pred_t, pred_i, f"{mid} ({nparams/1e6:.1f}M parameters, about {nparams/1e6:.0f} MB at 8-bit)", out)


def main():
    rows = load(sys.argv[1])
    fold_of = folds_of(rows)
    out = []
    kinds = collections.Counter(r["kind"] for r in rows)
    msgs = collections.Counter()
    for r in rows: msgs[r["kind"]] += r["n"]
    out.append(f"## Real masked messages: {len(rows)} distinct shapes ({sum(r['n'] for r in rows)} messages), {len(set(r['bank'] for r in rows))} bank labels, {FOLDS} folds of whole banks, abstain below {THRESH}")
    out.append("shapes by kind: " + ", ".join(f"{k}={v} ({msgs[k]} msgs)" for k, v in kinds.most_common()))
    out.append("banks: " + ", ".join(f"{b}={c}" for b, c in collections.Counter(r['bank'] for r in rows).most_common(12)))
    pt, pi = majority(rows); score(rows, pt, pi, "Baseline: always the most common answer", out)
    pt, pi = keyword(rows); score(rows, pt, pi, "Baseline: plain keywords (direction and card type only)", out, types=False)
    for mid, lr in MODELS:
        try: run_model(mid, lr, rows, fold_of, out)
        except Exception as e: out.append(f"\n### {mid}: could not run ({type(e).__name__}: {str(e)[:200]})")
    text = "\n".join(out)
    print(text)
    open("qa-real-report.md", "w").write(text)

if __name__ == "__main__":
    main()
