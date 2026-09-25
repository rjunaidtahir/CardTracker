"""
Blind statement set: 8 made-up statements in layouts the reader has never seen, rendered as REAL PDFs
(proportional fonts, right-aligned amounts), then read back glyph by glyph the way the app reads a PDF.
Writes one fixture file per statement: header lines with the expected answers, then one glyph per line.
"""
import os, sys
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
import pdfplumber

pdfmetrics.registerFont(TTFont("DV", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"))
pdfmetrics.registerFont(TTFont("DVB", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"))
W, H = A4
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def money(minor, cr=False):
    s = f"{abs(minor) / 100:,.2f}"
    return s


class Doc:
    def __init__(self, name):
        self.path = f"/tmp/{name}.pdf"
        self.c = canvas.Canvas(self.path, pagesize=A4)
        self.y = H - 50

    def text(self, x, s, size=9, bold=False, right=False, y=None):
        f = "DVB" if bold else "DV"
        self.c.setFont(f, size)
        yy = self.y if y is None else y
        if right:
            self.c.drawRightString(x, yy, s)
        else:
            self.c.drawString(x, yy, s)

    def down(self, d=14):
        self.y -= d

    def page(self):
        self.c.showPage()
        self.y = H - 50

    def save(self):
        self.c.save()


def dump(name, doc, expect):
    doc.save()
    lines = [f"#{k}={v}" for k, v in expect.items()]
    with pdfplumber.open(doc.path) as pdf:
        for pi, p in enumerate(pdf.pages):
            for ch in p.chars:
                t = ch["text"].replace("\t", " ").replace("\n", " ")
                if not t.strip():
                    continue
                lines.append(f"{pi}\t{ch['x0']:.2f}\t{ch['bottom']:.2f}\t{ch['x1'] - ch['x0']:.2f}\t{ch['size']:.2f}\t{t}")
    with open(os.path.join(OUT, name + ".tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def tot(prev, rows):
    return prev + sum(a for _, a, c in rows if not c) - sum(a for _, a, c in rows if c)


def card_expect(sd, dd, total, mn, prev, rows, last4, limit=None, avail=None, extra_n=0):
    deb = sum(a for _, a, c in rows if not c)
    cred = sum(a for _, a, c in rows if c)
    assert prev + deb - cred == total, (prev, deb, cred, total)
    e = dict(sd=sd, dd=dd, total=total, min=mn, prev=prev, n=len(rows) + extra_n, deb=deb, cred=cred, last4=last4, agree="true", account="false")
    if limit is not None: e["limit"] = limit
    if avail is not None: e["avail"] = avail
    return e



# ================================================================ ROUND 3 (written before any change for it)

def d1():  # amounts inside descriptions
    d = Doc("d1")
    rows = [("EMI 3/12 OF AED 1,200.00 - SAMSUNG", 10000, False), ("PAYMENT RECEIVED", 50000, True), ("CASHBACK 1% ON AED 2,500.00", 2500, True), ("KFC", 4200, False), ("DU POSTPAID", 32500, False)]
    prev = 50000; T = tot(prev, rows); MIN = max(10000, T * 5 // 100)
    d.text(40, "RAKBANK", 15, True); d.down(20)
    for l, v in [("Statement Date", "19/09/2026"), ("Payment Due Date", "14/10/2026"), ("Total Amount Due", money(T)), ("Minimum Amount Due", money(MIN)), ("Previous Balance", money(prev))]:
        d.text(40, l, 9); d.text(280, v, 9, right=True); d.down(13)
    d.text(40, "Card Number 5210 XXXX XXXX 6161", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Description", 8, True); d.text(555, "Amount", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{2 + i * 5:02d}/09/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("d1_amounts_inside_descriptions", d, card_expect("2026-09-19", "2026-10-14", T, MIN, prev, rows, "6161"))


def d2():  # LABEL: on its own line, value on the line below
    d = Doc("d2")
    rows = [("ADNOC", 15000, False), ("PAYMENT", 80000, True), ("MAX FASHION", 21900, False), ("NOON", 45000, False)]
    prev = 80000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "Sharjah Islamic Bank", 15, True); d.down(22)
    for l, v in [("STATEMENT DATE:", "11/09/2026"), ("PAYMENT DUE DATE:", "06/10/2026"), ("TOTAL AMOUNT DUE:", "AED " + money(T)), ("MINIMUM AMOUNT DUE:", "AED " + money(MIN)), ("PREVIOUS BALANCE:", "AED " + money(prev))]:
        d.text(40, l, 8, True); d.down(11); d.text(40, v, 10); d.down(17)
    d.text(40, "CARD NO: 4598 12XX XXXX 3030", 9); d.down(22)
    d.text(40, "DATE", 8, True); d.text(110, "DESCRIPTION", 8, True); d.text(555, "AMOUNT (AED)", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{8 + i * 6:02d}/08/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("d2_label_colon_value_below", d, card_expect("2026-09-11", "2026-10-06", T, MIN, prev, rows, "3030"))


def d3():  # dot dates, "Closing Date"
    d = Doc("d3")
    rows = [("WAITROSE", 33300, False), ("CREDIT PAYMENT", 120000, True), ("BOOKING.COM", 145000, False)]
    prev = 120000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "UAB United Arab Bank", 15, True); d.down(20)
    for l, v in [("Closing Date", "07.09.2026"), ("Due Date", "02.10.2026"), ("Closing Balance", money(T)), ("Minimum Payment", money(MIN)), ("Opening Balance", money(prev)), ("Credit Limit", "25,000.00")]:
        d.text(40, l, 9); d.text(260, v, 9, right=True); d.down(13)
    d.text(40, "Card ****7575", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Transaction", 8, True); d.text(555, "Amount", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{10 + i * 7:02d}.08.2026", 8); d.text(110, desc, 8); d.text(555, ("-" if cr else "") + money(amt), 8, right=True); d.down(13)
    dump("d3_dot_dates_closing_date", d, card_expect("2026-09-07", "2026-10-02", T, MIN, prev, rows, "7575", limit=2500000))


def d4():  # account: signed Amount + Running Balance, "Balance at start / end"
    d = Doc("d4")
    start = 300000
    rows = [("SALARY ACME LLC", 1500000, True), ("RENT TRANSFER", 700000, False), ("DEWA", 38000, False), ("CAREEM", 2500, False), ("REFUND NOON", 9900, True)]
    d.text(40, "Wio Bank", 15, True); d.down(18)
    d.text(40, "Account Statement  Account number ending 8282", 9); d.down(13)
    d.text(40, "Statement period 01/09/2026 - 30/09/2026", 9); d.down(13)
    bal = start
    for desc, amt, cr in rows: bal = bal + amt if cr else bal - amt
    d.text(40, f"Balance at start {money(start)}", 9); d.text(300, f"Balance at end {money(bal)}", 9); d.down(22)
    for x, h, r in [(40, "Transaction Date", 0), (130, "Narrative", 0), (450, "Amount", 1), (555, "Running Balance", 1)]:
        d.text(x, h, 8, True, right=bool(r))
    d.down(13)
    b = start
    for i, (desc, amt, cr) in enumerate(rows):
        b = b + amt if cr else b - amt
        d.text(40, f"{1 + i * 6:02d}/09/2026", 8); d.text(130, desc, 8); d.text(450, ("" if cr else "-") + money(amt), 8, right=True); d.text(555, money(b), 8, right=True); d.down(13)
    deb = sum(a for _, a, c in rows if not c); cred = sum(a for _, a, c in rows if c)
    dump("d4_account_signed_running_balance", d, dict(sd="2026-09-30", prev=start, n=len(rows), deb=deb, cred=cred, last4="8282", agree="true", account="true"))


def d5():  # summary box on the right next to an address block
    d = Doc("d5")
    rows = [("SPOTIFY", 2199, False), ("PAYMENT RECEIVED", 30000, True), ("CARREFOUR", 40050, False), ("ETIHAD AIRWAYS", 210000, False)]
    prev = 30000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "Arab Bank", 15, True); d.down(24)
    left = ["MR JOHN SMITH", "VILLA 12, STREET 4", "AL BARSHA 2", "DUBAI, UAE", "Card 4000 22XX XXXX 9191"]
    right = [("Statement Date", "21/09/2026"), ("Payment Due Date", "16/10/2026"), ("Total Amount Due", money(T)), ("Minimum Due", money(MIN)), ("Previous Balance", money(prev))]
    for k in range(5):
        d.text(40, left[k], 9); d.text(340, right[k][0], 9); d.text(555, right[k][1], 9, right=True); d.down(13)
    d.down(12)
    d.text(40, "Date", 8, True); d.text(110, "Description", 8, True); d.text(555, "Amount AED", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{24 + i * 7 - (31 if 24 + i * 7 > 31 else 0):02d}/{8 if 24 + i * 7 <= 31 else 9:02d}/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("d5_summary_right_of_address", d, card_expect("2026-09-21", "2026-10-16", T, MIN, prev, rows, "9191"))


def d6():  # payment reversal (a debit), late fee, interest
    d = Doc("d6")
    rows = [("PAYMENT RECEIVED", 100000, True), ("PAYMENT REVERSAL - RETURNED", 100000, False), ("LATE PAYMENT FEE", 23100, False), ("INTEREST CHARGES", 18750, False), ("SHELL", 9000, False)]
    prev = 100000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "InvestBank", 15, True); d.down(20)
    for l, v in [("Statement Date", "09/09/2026"), ("Payment Due Date", "04/10/2026"), ("Total Amount Due", money(T)), ("Minimum Amount Due", money(MIN)), ("Previous Balance", money(prev))]:
        d.text(40, l, 9); d.text(280, v, 9, right=True); d.down(13)
    d.text(40, "Card Number 5100 88** **** 4242", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Description", 8, True); d.text(555, "Amount", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{11 + i * 5:02d}/08/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("d6_payment_reversal_fees", d, card_expect("2026-09-09", "2026-10-04", T, MIN, prev, rows, "4242"))


for f in [d1, d2, d3, d4, d5, d6]:
    f()
print("ok")
