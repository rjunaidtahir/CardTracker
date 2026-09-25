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



# ================================================================ ROUND 2 (written before any change for it)

# C1: balance-calculation box (Opening + Purchases + Fees - Payments = Closing) and a Ref No column with long numbers
def c1():
    d = Doc("c1")
    rows = [("74512345678", "SHARAF DG DEIRA", 129900, False), ("74512345702", "PAYMENT RECEIVED", 300000, True), ("74512345799", "ZOMATO", 6750, False),
            ("74512345811", "ANNUAL FEE", 30000, False), ("74512345900", "VAT ON FEE", 1500, False)]
    prev = 350000
    T = tot(prev, [(a, b, c) for _, a, b, c in rows]); MIN = T * 5 // 100
    purchases = 129900 + 6750; fees = 31500; pays = 300000
    d.text(40, "Mashreq Cashback Card", 15, True); d.down(20)
    d.text(40, "Statement Date: 14/09/2026", 9); d.text(300, "Payment Due Date: 09/10/2026", 9); d.down(13)
    d.text(40, f"Minimum Payment Due: AED {money(MIN)}", 9); d.text(300, "Card Number: 4210 99XX XXXX 6543", 9); d.down(22)
    d.text(40, "Account Summary", 10, True); d.down(14)
    for l, v in [("Opening Balance", money(prev)), ("(+) Purchases & Cash", money(purchases)), ("(+) Fees & Charges", money(fees)),
                 ("(-) Payments & Credits", money(pays)), ("(=) Closing Balance", money(T))]:
        d.text(40, l, 8.5); d.text(260, v, 8.5, right=True); d.down(12)
    d.down(14)
    for x, h, r in [(40, "Trans. Date", 0), (105, "Ref No.", 0), (190, "Description", 0), (555, "Amount (AED)", 1)]:
        d.text(x, h, 8, True, right=bool(r))
    d.down(13)
    for i, (ref, desc, amt, cr) in enumerate(rows):
        d.text(40, f"{10 + i * 4:02d}/08/2026", 8); d.text(105, ref, 8); d.text(190, desc, 8)
        d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("c1_balance_box_ref_numbers", d, card_expect("2026-09-14", "2026-10-09", T, MIN, prev, [(a, b, c) for _, a, b, c in rows], "6543"))


# C2: DD-MMM-YY dates everywhere, amounts with an AED suffix, Cr as its own word
def c2():
    d = Doc("c2")
    rows = [("CARREFOUR MCC", 23450, False), ("PAYMENT THANK YOU", 100000, True), ("SALIK TOPUP", 10000, False), ("AMAZON.AE", 51999, False), ("REFUND AMAZON.AE", 51999, True)]
    prev = 80000; T = tot(prev, rows); MIN = max(10000, T * 5 // 100)
    d.text(40, "First Abu Dhabi Bank", 15, True); d.down(20)
    for l, v in [("Statement Date", "10-SEP-26"), ("Payment Due Date", "05-OCT-26"), ("Total Amount Due", money(T) + " AED"),
                 ("Minimum Amount Due", money(MIN) + " AED"), ("Previous Balance", money(prev) + " AED")]:
        d.text(40, l, 9); d.text(330, v, 9, right=True); d.down(13)
    d.text(40, "Card ending 1188", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Details", 8, True); d.text(500, "Amount", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{5 + i * 5:02d}-AUG-26", 8); d.text(110, desc, 8); d.text(500, money(amt) + " AED", 8, right=True)
        if cr: d.text(515, "Cr", 8)
        d.down(13)
    dump("c2_dd_mmm_yy_aed_suffix", d, card_expect("2026-09-10", "2026-10-05", T, MIN, prev, rows, "1188"))


# C3: figures printed BEFORE their labels on the same line
def c3():
    d = Doc("c3")
    rows = [("IKEA", 88000, False), ("PAYMENT", 50000, True), ("NETFLIX", 5600, False)]
    prev = 50000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "Emirates NBD", 15, True); d.down(20)
    for v, l in [("15/09/2026", "Statement Date"), ("10/10/2026", "Payment Due Date"), ("AED " + money(T), "Total Amount Due"),
                 ("AED " + money(MIN), "Minimum Amount Due"), ("AED " + money(prev), "Previous Balance"), ("AED 12,000.00", "Credit Limit")]:
        d.text(160, v, 9, True, right=True); d.text(175, l, 9); d.down(13)
    d.text(40, "Card No. 5200 11** **** 9090", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Description", 8, True); d.text(555, "Amount", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{20 + i * 5:02d}/08/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + ("CR" if cr else ""), 8, right=True); d.down(13)
    dump("c3_values_before_labels", d, card_expect("2026-09-15", "2026-10-10", T, MIN, prev, rows, "9090", limit=1200000))


# C4: account statement, the whole summary on one line, Cr/Dr on balances, "From ... To ..." period
def c4():
    d = Doc("c4")
    opening = 500000
    rows = [("POS PURCHASE LULU", 12000, False), ("SALARY CREDIT", 300000, True), ("ETISALAT BILL", 45000, False), ("ATM CASH", 50000, False), ("TRANSFER IN AHMED", 7000, True)]
    bal = opening; deb = sum(a for _, a, c in rows if not c); cred = sum(a for _, a, c in rows if c); closing = opening + cred - deb
    d.text(40, "Commercial Bank of Dubai", 14, True); d.down(18)
    d.text(40, "Account Statement    A/C No. 1001 2345 6789 3434    From 01-Aug-2026 To 31-Aug-2026", 8); d.down(18)
    d.text(40, f"Opening Balance {money(opening)} Cr   Total Debits {money(deb)}   Total Credits {money(cred)}   Closing Balance {money(closing)} Cr", 7.5); d.down(22)
    for x, h, r in [(40, "Date", 0), (100, "Description", 0), (400, "Debit", 1), (470, "Credit", 1), (555, "Balance", 1)]:
        d.text(x, h, 8, True, right=bool(r))
    d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        bal = bal + amt if cr else bal - amt
        d.text(40, f"{3 + i * 5:02d}-Aug-2026", 8); d.text(100, desc, 8); d.text(470 if cr else 400, money(amt), 8, right=True); d.text(555, money(bal) + " Cr", 8, right=True); d.down(13)
    dump("c4_account_one_line_summary", d, dict(sd="2026-08-31", prev=opening, n=len(rows), deb=deb, cred=cred, last4="3434", agree="true", account="true"))


# C5: prose: due date and minimum only in a sentence
def c5():
    d = Doc("c5")
    rows = [("DUBAI MALL", 60000, False), ("PAYMENT RECEIVED", 20000, True), ("UBER", 3500, False)]
    prev = 20000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "HSBC", 15, True); d.down(20)
    d.text(40, "Credit card statement for the period 10 Aug 2026 to 09 Sep 2026", 9); d.down(14)
    d.text(40, f"Your statement balance is AED {money(T)}.", 9); d.down(13)
    d.text(40, f"Please pay at least AED {money(MIN)} by 04 October 2026 to avoid a late payment fee.", 9); d.down(13)
    d.text(40, f"Previous statement balance AED {money(prev)}", 9); d.down(13)
    d.text(40, "Card number ending 3131", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Description", 8, True); d.text(555, "Amount (AED)", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{15 + i * 6:02d} Aug", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("c5_prose_due_and_minimum", d, card_expect("2026-09-09", "2026-10-04", T, MIN, prev, rows, "3131"))


# C6: every transaction on two lines (amount on the second), page break with the header repeated, "Page 1 of 2"
def c6():
    d = Doc("c6")
    rows = [("NOON.COM", "DUBAI AE REF 88121", 34500, False), ("PAYMENT - THANK YOU", "ONLINE BANKING", 150000, True),
            ("EMARAT PETROL", "SHARJAH AE", 18000, False), ("PHARMACY LIFE", "DUBAI AE", 7650, False), ("CARREFOUR", "AL AIN AE", 21000, False)]
    prev = 200000; T = tot(prev, [(a, c, e) for a, _, c, e in rows]); MIN = T * 5 // 100
    def header(p):
        d.text(40, "Emirates Islamic", 14, True); d.text(555, f"Page {p} of 2", 8, right=True); d.down(16)
        d.text(40, "Statement Date 13/09/2026", 8.5); d.text(300, "Card No. 4444 55XX XXXX 7272", 8.5); d.down(16)
    header(1)
    d.text(40, "Payment Due Date 08/10/2026", 9); d.down(13)
    d.text(40, f"Total Outstanding {money(T)}", 9); d.down(13)
    d.text(40, f"Minimum Payment {money(MIN)}", 9); d.down(13)
    d.text(40, f"Previous Balance {money(prev)}", 9); d.down(20)
    def cols():
        d.text(40, "Date", 8, True); d.text(110, "Description", 8, True); d.text(555, "Amount AED", 8, True, right=True); d.down(13)
    cols()
    for i, (desc, sub, amt, cr) in enumerate(rows):
        if i == 3:
            d.page(); header(2); cols()
        d.text(40, f"{5 + i * 5:02d}/08/2026", 8); d.text(110, desc, 8); d.down(11)
        d.text(120, sub, 7); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(14)
    dump("c6_two_line_rows_repeated_page_header", d, card_expect("2026-09-13", "2026-10-08", T, MIN, prev, [(a, c, e) for a, _, c, e in rows], "7272"))


# C7: Dhs prefix, no-year table dates, statement period "Aug 06 - Sep 05, 2026"
def c7():
    d = Doc("c7")
    rows = [("GEANT", 18950, False), ("PAYMENT", 90000, True), ("ENOC", 12000, False), ("CINEMA VOX", 9000, False)]
    prev = 90000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "Al Hilal Bank", 15, True); d.down(20)
    d.text(40, "Statement period: Aug 06 - Sep 05, 2026", 9); d.down(13)
    d.text(40, "Statement date: Sep 05, 2026", 9); d.down(13)
    d.text(40, "Due date: Sep 30, 2026", 9); d.down(13)
    d.text(40, f"Amount due: Dhs {money(T)}", 9); d.down(13)
    d.text(40, f"Minimum due: Dhs {money(MIN)}", 9); d.down(13)
    d.text(40, f"Previous balance: Dhs {money(prev)}", 9); d.down(13)
    d.text(40, "Card: **** **** **** 5151", 9); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Merchant", 8, True); d.text(555, "Amount", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"Aug {10 + i * 6:02d}", 8); d.text(110, desc, 8); d.text(555, ("-" if cr else "") + "Dhs " + money(amt), 8, right=True); d.down(13)
    dump("c7_dhs_no_year_dates", d, card_expect("2026-09-05", "2026-09-30", T, MIN, prev, rows, "5151"))


# C8: card statement with a "Payments" and "Purchases" column, zero-amount info rows and a points column
def c8():
    d = Doc("c8")
    rows = [("LULU", 25000, False), ("PAYMENT BANK TRANSFER", 60000, True), ("APPLE STORE", 399900, False), ("CAREEM", 4200, False)]
    prev = 60000; T = tot(prev, rows); MIN = T * 5 // 100
    d.text(40, "Citibank UAE", 15, True); d.down(20)
    d.text(40, f"Statement Date 17/09/2026   Payment Due Date 12/10/2026   Total Amount Due {money(T)}", 8.5); d.down(13)
    d.text(40, f"Minimum Amount Due {money(MIN)}   Previous Balance {money(prev)}   Credit Limit 30,000.00", 8.5); d.down(13)
    d.text(40, "Citi Card Number 5412 75XX XXXX 8080", 8.5); d.down(22)
    for x, h, r in [(40, "Date", 0), (100, "Description", 0), (380, "Points", 1), (460, "Purchases", 1), (555, "Payments", 1)]:
        d.text(x, h, 8, True, right=bool(r))
    d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{19 + i * 4:02d}/08/2026", 8); d.text(100, desc, 8)
        if not cr: d.text(380, str(amt // 100), 8, right=True)
        d.text(555 if cr else 460, money(amt), 8, right=True); d.down(13)
    d.text(40, "25/08/2026", 8); d.text(100, "CARD REPLACEMENT FEE WAIVED", 8); d.text(460, "0.00", 8, right=True); d.down(13)
    dump("c8_purchases_payments_points_columns", d, card_expect("2026-09-17", "2026-10-12", T, MIN, prev, rows, "8080", limit=3000000))


for f in [c1, c2, c3, c4, c5, c6, c7, c8]:
    f()
print("ok")
