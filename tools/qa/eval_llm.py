#!/usr/bin/env python3
"""Same blind test as eval.py, but with small pretrained instruction models that answer in JSON.
Usage: eval_llm.py QASET.jsonl MODEL_ID [...]
Fixed before running: the prompt below, greedy decoding, the mapping, and the rule that any number the model
returns must literally appear in the message (code check) or it counts as an abstention.
"""
import json, re, sys, collections
sys.path.insert(0, __import__("os").path.dirname(__file__))
from eval import Stat, split, num, parse_date, merchant_ok, report, keyword_baseline  # same scoring helpers

PROMPT = """You read one bank SMS. Answer with ONE line of JSON only, no other text. Never calculate; copy numbers exactly as written in the SMS.
Keys:
"type": one of "purchase","refund","payment","transfer_out","transfer_in","statement","not_money" (statement = a card statement or payment-due notice; not_money = OTP, offer, balance notice, reminder or anything that is not a completed money movement)
"direction": "out" if money left the customer, "in" if money came to the customer, "none" otherwise
"instrument": "credit_card","debit_card","account" or "unknown" (use unknown when the SMS does not say)
"amount": the transaction amount as written, or null
"merchant": who was paid or where it was spent, or null
"statement_total": total statement amount as written, or null
"minimum_due": minimum amount as written, or null
"due_date": the payment due or charge date as written, or null

SMS: """


def in_text(v, body):
    if v is None: return None
    n = num(str(v))
    if n is None: return None
    digits = re.sub(r"[,\s]", "", body)
    return n if re.sub(r"[,\s]", "", str(v)).split(".")[0] in digits else None


def main():
    rows = [json.loads(l[len("QASET "):]) for l in open(sys.argv[1]) if l.startswith("QASET {")]
    out = []
    errors = []
    import torch
    from transformers import AutoTokenizer, AutoModelForCausalLM
    for mid in sys.argv[2:]:
        try:
            tok = AutoTokenizer.from_pretrained(mid)
            model = AutoModelForCausalLM.from_pretrained(mid, torch_dtype=torch.float32)
            model.eval()
        except Exception as e:
            out.append(f"\n### {mid}: could not be loaded ({type(e).__name__}: {str(e)[:200]})"); continue
        stats = collections.defaultdict(Stat)
        for r in rows:
            msgs = [{"role": "user", "content": PROMPT + r["body"]}]
            ids = tok.apply_chat_template(msgs, add_generation_prompt=True, return_tensors="pt")
            with torch.no_grad():
                g = model.generate(ids, max_new_tokens=110, do_sample=False)
            txt = tok.decode(g[0][ids.shape[1]:], skip_special_tokens=True)
            try: j = json.loads(re.search(r"\{.*\}", txt, re.S).group(0))
            except Exception: j = {}
            sp = split(r["group"]); tags = [sp] + (["tabby"] if r["group"] == "Tabby" else [])
            def rec(task, answered, ok, detail=""):
                for t in tags: stats[(task, t)].add(answered, ok)
                if answered and not ok: errors.append({"model": mid, "task": task, "group": r["group"], "body": r["body"], "detail": detail, "raw": txt[:200]})
            t = str(j.get("type", ""))
            pred = "STATEMENT" if t == "statement" else "NOT_TXN" if t == "not_money" else "TRANSACTION" if t in ("purchase","refund","payment","transfer_out","transfer_in") else None
            gold = "STATEMENT" if r["kind"] == "STATEMENT" else "NOT_TXN" if r["kind"] == "NOT_TXN" else "TRANSACTION"
            rec("type (statement/transaction/not)", pred is not None, pred == gold, f"{t} vs {gold}")
            if gold == "NOT_TXN": rec("false alarm: not-a-transaction read as money", pred is not None, pred == "NOT_TXN", t)
            if gold == "TRANSACTION":
                v = in_text(j.get("amount"), r["body"])
                rec("transaction amount", v is not None, v is not None and abs(v - float(r["amount"])) < 0.005, f"{j.get('amount')} vs {r['amount']}")
                if r["direction"]:
                    d = j.get("direction"); d = d if d in ("out", "in") else None
                    rec("debit vs credit (money out vs in)", d is not None, d == r["direction"], f"{d} vs {r['direction']}")
                if r["instrument"]:
                    i = {"credit_card": "CREDIT", "debit_card": "DEBIT", "account": "ACCOUNT"}.get(j.get("instrument"))
                    rec("credit card vs debit card vs account", i is not None, i == r["instrument"], f"{j.get('instrument')} vs {r['instrument']}")
                if r["merchant"]:
                    m = j.get("merchant") if isinstance(j.get("merchant"), str) else None
                    rec("merchant", m is not None, m is not None and merchant_ok(m, r["merchant"]), f"{m} vs {r['merchant']}")
            if r["kind"] == "STATEMENT":
                for key, f in (("statement_total", "total"), ("minimum_due", "min")):
                    v = in_text(j.get(key), r["body"])
                    rec(f"statement {f}", v is not None, v is not None and abs(v - float(r[f])) < 0.005, f"{j.get(key)} vs {r[f]}")
                if r["due"] is not None:
                    d = parse_date(str(j.get("due_date")) if j.get("due_date") else None)
                    rec("statement due date", d is not None, d == r["due"], f"{j.get('due_date')} vs {r['due']}")
        report(mid, stats, out)
    text = "\n".join(out)
    print(text)
    open("qa-llm-report.md", "w").write(text)
    json.dump(errors, open("qa-llm-errors.json", "w"), indent=1)

if __name__ == "__main__":
    main()
