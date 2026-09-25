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


# ---------------------------------------------------------------- B1: values ABOVE their labels, two date columns, Debit/Credit columns
def b1():
    d = Doc("b1")
    d.text(40, "RAKBANK", 16, True); d.down(18)
    d.text(40, "Credit Card Statement - Titanium", 10); d.down(30)
    rows = [("CARREFOUR MOE", 34560, False), ("PAYMENT - THANK YOU", 150000, True), ("EMIRATES AIRLINE", 185000, False),
            ("CASHBACK", 1000, True), ("DEWA", 30000, False), ("TALABAT", 4600, False)]
    T = tot(150000, rows); MIN = T * 5 // 100
    tiles = [("12 Sep 2026", "Statement Date"), ("07 Oct 2026", "Payment Due Date"), ("AED " + money(T), "Total Amount Due"),
             ("AED " + money(MIN), "Minimum Amount Due"), ("AED 20,000.00", "Credit Limit"), ("AED " + money(2000000 - T), "Available Credit")]
    for i, (v, l) in enumerate(tiles):
        x = 40 + (i % 3) * 180
        if i == 3: d.down(40)
        d.text(x, v, 13, True)
        d.text(x, l, 7.5, y=d.y - 12)
    d.down(46)
    d.text(40, "Card number 5234 56** **** 8812   Previous balance AED 1,500.00"); d.down(28)
    for x, h, r in [(40, "Txn Date", 0), (100, "Post Date", 0), (160, "Description", 0), (470, "Debit", 1), (555, "Credit", 1)]:
        d.text(x, h, 8, True, right=bool(r))
    d.down(14)
    for i, (desc, amt, cr) in enumerate(rows):
        day = 14 + i * 3 if i < 6 else 10
        td = f"{(day - 1) % 30 + 1:02d}/{8 if day <= 31 else 9:02d}/2026"
        dd_ = f"{(day - 1) % 30 + 1:02d}/{8 if day <= 31 else 9:02d}/2026"
        d.text(40, td, 8); d.text(100, dd_, 8); d.text(160, desc, 8)
        d.text(555 if cr else 470, money(amt), 8, right=True)
        d.down(13)
    dump("b1_values_above_labels", d, card_expect("2026-09-12", "2026-10-07", T, MIN, 150000, rows, "8812", limit=2000000, avail=2000000 - T))


# ---------------------------------------------------------------- B2: bilingual Arabic/English labels, CR suffix
def b2():
    d = Doc("b2")
    d.text(40, "Dubai Islamic Bank", 15, True); d.text(555, "بنك دبي الإسلامي", 12, right=True); d.down(20)
    d.text(40, "Covered Card Statement  كشف حساب البطاقة", 10); d.down(26)
    items = [("Statement Date", "تاريخ الكشف", "15/09/2026"), ("Payment Due Date", "تاريخ استحقاق الدفع", "10/10/2026"),
             ("Total Outstanding", "إجمالي المبلغ المستحق", "3,210.75"), ("Minimum Payment", "الحد الأدنى للدفع", "160.54"),
             ("Previous Balance", "الرصيد السابق", "2,000.00"), ("Card Limit", "حد البطاقة", "15,000.00")]
    for en, ar, v in items:
        d.text(40, en, 9); d.text(200, v, 9, True); d.text(555, ar, 9, right=True); d.down(15)
    d.down(8)
    d.text(40, "Card No. 4096 11XX XXXX 3355"); d.down(24)
    d.text(40, "Date / التاريخ", 8, True); d.text(130, "Description / الوصف", 8, True); d.text(555, "Amount AED / المبلغ", 8, True, right=True); d.down(14)
    rows = [("NOON.COM", 45075, False), ("PAYMENT RECEIVED شكرا", 200000, True), ("LULU HYPERMARKET", 38000, False),
            ("GOVERNMENT FEES", 90000, False), ("PROFIT CHARGE", 0, False)]
    rows = [r for r in rows if r[1] > 0] + [("REFUND NOON", 12000, True), ("ADNOC", 100000, False), ("ETISALAT", 60000, False)]
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{2 + i * 3:02d}/09/2026", 8); d.text(130, desc, 8)
        d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    dump("b2_bilingual_arabic", d, card_expect("2026-09-15", "2026-10-10", 321075, 16054, 200000, rows, "3355", limit=1500000))


# ---------------------------------------------------------------- B3: credit balance (overpaid): negative total, Dr/Cr words
def b3():
    d = Doc("b3")
    d.text(40, "Emirates Islamic", 15, True); d.down(22)
    d.text(40, "Credit Card Statement", 10); d.down(24)
    for l, v in [("Statement Date :", "18-Sep-2026"), ("Due Date :", "13-Oct-2026"), ("Statement Balance :", "500.00 Cr"),
                 ("Minimum Due :", "0.00"), ("Opening Balance :", "1,000.00 Dr")]:
        d.text(40, l, 9); d.text(300, v, 9, right=True); d.down(14)
    d.text(40, "Card ending 6060"); d.down(24)
    d.text(40, "Date", 8, True); d.text(110, "Details", 8, True); d.text(470, "Amount", 8, True, right=True); d.text(500, "Dr/Cr", 8, True); d.down(14)
    rows = [("GRAND HYATT", 45000, False), ("PAYMENT", 200000, True), ("VOX CINEMAS", 5000, False)]
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{3 + i * 5:02d}-Sep-2026", 8); d.text(110, desc, 8); d.text(470, money(amt), 8, right=True); d.text(500, "Cr" if cr else "Dr", 8); d.down(13)
    e = card_expect("2026-09-18", "2026-10-13", -50000, 0, 100000, rows, "6060")
    dump("b3_credit_balance_dr_cr_words", d, e)


# ---------------------------------------------------------------- B4: rewards and instalment tables that must NOT count
def b4():
    d = Doc("b4")
    d.text(40, "CBD Commercial Bank of Dubai", 15, True); d.down(22)
    d.text(40, "Statement Date 11/09/2026    Payment Due Date 06/10/2026", 9); d.down(14)
    rows = [("SPINNEYS", 29500, False), ("PAYMENT THANK YOU", 120000, True), ("APPLE.COM/BILL", 3500, False),
            ("INSTALMENT 3 OF 12 - IPHONE", 35000, False), ("IKEA", 96500, False)]
    T = tot(120000, rows); MIN = T * 5 // 100
    d.text(40, f"Total Amount Due AED {money(T)}    Minimum Amount Due AED {money(MIN)}    Previous Balance AED 1,200.00", 9); d.down(14)
    d.text(40, "Credit Card 5555 44XX XXXX 9021", 9); d.down(26)
    d.text(40, "Date", 8, True); d.text(110, "Transaction", 8, True); d.text(555, "Amount (AED)", 8, True, right=True); d.down(14)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"{15 + i * 4:02d}/08/2026" if i < 4 else "02/09/2026", 8); d.text(110, desc, 8)
        d.text(555, money(amt) + ("CR" if cr else ""), 8, right=True); d.down(13)
    d.down(16)
    d.text(40, "Rewards Summary", 10, True); d.down(14)
    for l, v in [("Opening points", "3,400"), ("Points earned", "1,250"), ("Points redeemed", "0"), ("Closing points", "4,650")]:
        d.text(40, l, 8); d.text(300, v, 8, right=True); d.down(12)
    d.down(10)
    d.text(40, "Instalment Plans", 10, True); d.down(14)
    d.text(40, "Plan", 8, True); d.text(250, "Monthly", 8, True, right=True); d.text(350, "Remaining", 8, True, right=True); d.text(450, "Balance", 8, True, right=True); d.down(12)
    d.text(40, "IPHONE 16 (started 15/06/2026)", 8); d.text(250, "350.00", 8, right=True); d.text(350, "9", 8, right=True); d.text(450, "3,150.00", 8, right=True); d.down(12)
    dump("b4_rewards_and_instalment_tables", d, card_expect("2026-09-11", "2026-10-06", T, MIN, 120000, rows, "9021"))


# ---------------------------------------------------------------- B5: account, Withdrawals/Deposits/Balance, cheque col, 2 pages, B/F rows
def b5():
    d = Doc("b5")
    opening = 1250000
    rows = [("ATM WITHDRAWAL", 100000, False), ("SALARY SEP 2026", 1800000, True), ("RENT CHEQUE", 4500000 // 10, False),
            ("DEWA BILL", 42050, False), ("TRANSFER TO SAVINGS", 300000, False), ("CARD PAYMENT RAKBANK", 234560, False),
            ("INTEREST CREDIT", 1520, True), ("GROCERIES POS", 18000, False)]
    bal = opening
    def header():
        d.text(40, "NBF National Bank of Fujairah", 14, True); d.down(18)
        d.text(40, "Statement of Account   Account No. 0112 3456 7890 4477   Period 01/09/2026 to 30/09/2026", 8); d.down(22)
        for x, h, r in [(40, "Date", 0), (95, "Value Date", 0), (150, "Narration", 0), (330, "Chq No", 0), (430, "Withdrawals", 1), (495, "Deposits", 1), (560, "Balance", 1)]:
            d.text(x, h, 7.5, True, right=bool(r))
        d.down(13)
    header()
    d.text(150, "BALANCE BROUGHT FORWARD", 7.5); d.text(560, money(bal), 7.5, right=True); d.down(12)
    for i, (desc, amt, cr) in enumerate(rows):
        if i == 5:
            d.text(150, "BALANCE CARRIED FORWARD", 7.5); d.text(560, money(bal), 7.5, right=True)
            d.page(); header()
            d.text(150, "BALANCE BROUGHT FORWARD", 7.5); d.text(560, money(bal), 7.5, right=True); d.down(12)
        bal = bal + amt if cr else bal - amt
        dt = f"{2 + i * 3:02d}/09/2026"
        d.text(40, dt, 7.5); d.text(95, dt, 7.5); d.text(150, desc, 7.5)
        if desc.startswith("RENT"): d.text(330, "000123", 7.5)
        d.text(495 if cr else 430, money(amt), 7.5, right=True); d.text(560, money(bal), 7.5, right=True); d.down(12)
    d.down(8)
    d.text(150, "CLOSING BALANCE", 8, True); d.text(560, money(bal), 8, True, right=True)
    deb = sum(a for _, a, c in rows if not c); cred = sum(a for _, a, c in rows if c)
    dump("b5_account_two_pages_brought_forward", d, dict(sd="2026-09-30", prev=opening, n=len(rows), deb=deb, cred=cred, last4="4477", agree="true", account="true"))


# ---------------------------------------------------------------- B6: foreign spends with original amount + AED columns, fee rows
def b6():
    d = Doc("b6")
    d.text(40, "Standard Chartered", 15, True); d.down(22)
    rows = [("HOTEL LUTETIA PARIS", "EUR", "450.00", 180000, False), ("FOREIGN TRANSACTION FEE", "", "", 3600, False),
            ("AMAZON UK", "GBP", "120.00", 56400, False), ("FOREIGN TRANSACTION FEE", "", "", 1128, False),
            ("PAYMENT RECEIVED", "", "", 90000, True), ("DUBAI MALL PARKING", "", "", 5000, False), ("NOON", "", "", 76202, False)]
    T = tot(90000, [(r[0], r[3], r[4]) for r in rows]); MIN = T * 5 // 100
    d.text(40, "Statement date: 08 September 2026", 9); d.down(13)
    d.text(40, "Payment due date: 03 October 2026", 9); d.down(13)
    d.text(40, f"New balance: AED {money(T)}", 9); d.down(13)
    d.text(40, f"Minimum payment: AED {money(MIN)}", 9); d.down(13)
    d.text(40, "Balance from last statement: AED 900.00", 9); d.down(13)
    d.text(40, "Your card ending in 7070", 9); d.down(24)
    for x, h, r in [(40, "Date", 0), (110, "Description", 0), (380, "Foreign amount", 1), (470, "Currency", 0), (555, "AED", 1)]:
        d.text(x, h, 8, True, right=bool(r))
    d.down(14)
    for i, (desc, cur, fa, amt, cr) in enumerate(rows):
        d.text(40, f"{11 + i * 3:02d} Aug", 8); d.text(110, desc, 8)
        if fa: d.text(380, fa, 8, right=True); d.text(470, cur, 8)
        d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    e = card_expect("2026-09-08", "2026-10-03", T, MIN, 90000, [(r[0], r[3], r[4]) for r in rows], "7070")
    dump("b6_foreign_amount_columns", d, e)


# ---------------------------------------------------------------- B7: terse abbreviations, ISO dates in the table
def b7():
    d = Doc("b7")
    d.text(40, "Ajman Bank", 15, True); d.down(22)
    rows = [("CAREEM RIDE", 4765, False), ("PYMT RCVD TKS", 50000, True), ("SHARAF DG", 89900, False), ("RVSL SHARAF DG", 5000, True),
            ("STARBUCKS", 2000, False), ("NETFLIX", 5600, False), ("CASH BACK", 1500, True), ("DU MOBILE", 5000, False)]
    T = tot(50000, rows); MIN = T * 5 // 100
    for l, v in [("Stmt Dt", "2026-09-20"), ("Due Dt", "2026-10-15"), ("Tot. Amt. Due", money(T)), ("Min. Amt.", money(MIN)),
                 ("Cr. Limit", "10,000.00"), ("Avl. Cr. Limit", money(1000000 - T)), ("Prev. Bal.", "500.00")]:
        d.text(40, l, 8.5); d.text(160, v, 8.5, right=True); d.down(13)
    d.text(40, "Card: ************2468", 8.5); d.down(22)
    d.text(40, "Date", 8, True); d.text(110, "Particulars", 8, True); d.text(555, "Amt", 8, True, right=True); d.down(13)
    for i, (desc, amt, cr) in enumerate(rows):
        d.text(40, f"2026-08-{21 + i * 3:02d}" if 21 + i * 3 <= 31 else f"2026-09-{21 + i * 3 - 31:02d}", 8); d.text(110, desc, 8)
        d.text(555, ("-" if cr else "") + money(amt), 8, right=True); d.down(13)
    dump("b7_terse_abbreviations_iso", d, card_expect("2026-09-20", "2026-10-15", T, MIN, 50000, rows, "2468", limit=1000000, avail=1000000 - T))


# ---------------------------------------------------------------- B8: supplementary card section with its own subtotal lines
def b8():
    d = Doc("b8")
    main = [("PAYMENT RECEIVED", 100000, True), ("CARREFOUR", 64000, False), ("ENOC", 20000, False)]
    supp = [("SEPHORA", 120000, False), ("STARBUCKS", 5000, False), ("REFUND SEPHORA", 25000, True)]
    rows = main + supp
    T = tot(100000, rows); MIN = T * 5 // 100
    d.text(40, "ADIB", 15, True); d.down(22)
    d.text(40, "Statement Date 16/09/2026", 9); d.text(300, "Payment Due Date 11/10/2026", 9); d.down(13)
    d.text(40, f"Total Amount Due {money(T)}", 9); d.text(300, f"Minimum Amount Due {money(MIN)}", 9); d.down(13)
    d.text(40, "Previous Balance 1,000.00", 9); d.down(22)
    d.text(40, "Primary Card 4580 22XX XXXX 1357 - AHMED K", 9, True); d.down(15)
    for i, (desc, amt, cr) in enumerate(main):
        d.text(40, f"{18 + i * 4:02d}/08/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    d.text(110, "Total for card ending 1357", 8, True); d.text(555, "160.00 CR", 8, True, right=True); d.down(20)
    d.text(40, "Supplementary Card 4580 22XX XXXX 2468 - SARA K", 9, True); d.down(15)
    for i, (desc, amt, cr) in enumerate(supp):
        d.text(40, f"{3 + i * 4:02d}/09/2026", 8); d.text(110, desc, 8); d.text(555, money(amt) + (" CR" if cr else ""), 8, right=True); d.down(13)
    d.text(110, "Total for card ending 2468", 8, True); d.text(555, "1,000.00", 8, True, right=True); d.down(13)
    dump("b8_supplementary_card_subtotals", d, card_expect("2026-09-16", "2026-10-11", T, MIN, 100000, rows, "1357"))


for f in [b1, b2, b3, b4, b5, b6, b7, b8]:
    f()
print("ok")
