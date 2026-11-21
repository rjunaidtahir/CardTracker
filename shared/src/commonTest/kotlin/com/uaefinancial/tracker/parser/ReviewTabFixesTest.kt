package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** From the owner's Needs review screenshot and fresh export (21 Nov 2026). Names and numbers invented. */
class ReviewTabFixesTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)
    private fun read(s: String) = SmartParser.read("Unknown Test Bank", SmsParser.normalizeBody(s), at, zone)

    @Test fun feeAndTaxDebitedTogetherIsOneAmount() {
        val r = read("Dear Customer, SMS Alerts Monthly Fee PKR 250.00 & Tax 40.00 Debited from A/C: 0166-1***1440 on 16-Nov-2026 Bal: Reply with AB 021111225111")
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        assertEquals("290.00", r.txn.amount.toPlainString())
        assertEquals(TxnType.PURCHASE, r.txn.type)
    }

    @Test fun aFeeWithoutTaxIsUnchanged() {
        val r = read("Annual fee of AED 300.00 has been charged to your Credit Card ending 1234")
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        assertEquals("300.00", r.txn.amount.toPlainString())
    }

    @Test fun raastReceiptWithASplitWordIsMoneyIn() {
        val r = read("Dear ZORBO QUIBBLE via RAAST receive d PKR 5,000.00 from ZORBO QUIBBLE IBAN PK00XXXX1234 on 11-Nov-2026 at 10:15 Bal: Reply with AB 021111225111")
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        assertEquals(TxnType.TRANSFER_IN, r.txn.type)
    }

    @Test fun mashreqBeneficiaryActivationNoticeIsIgnored() {
        val t = "لتعزيز الأمان، يستغرق تفعيل المستفيد على تطبيق المشرق موبايل 4 ساعات عند الدخول عبر المقاييس الحيوية و 24 ساعة عند الدخول باسم المستخدم وكلمة المرور. وخلال فترة التفعيل إجراء عمليتي تحويل كحد أقصى بإجمالي 2,000 درهم"
        assertTrue(SmsParser.parseAsBank("Mashreq", t, at, zone) is ParseResult.Ignored)
    }

    @Test fun severalTransactionsInOneTextAreNotReadAsOne() {
        val t = "An amount of EUR 1.00 has been reversed to your Credit Card XXX2777 on 11/09/2022 13:42:36 by ZORBO. Your Cr.Card XXX2777 was used for EUR 1.00 on 11/09/2022 13:42:36 at ZORBO. Your Cr.Card XXX2777 was used for AED 10768.62 on 11/09/2022 14:30:35 at ZORBO2. Avl. Cr.limit is AED 751.25"
        assertEquals(null, read(t))
    }

    @Test fun aSingleReversalIsStillRead() {
        val r = read("An amount of AED 25.00 has been reversed to your Credit Card XXX2777 on 11/09/2022 13:42:36 by ZORBO. Avl. Cr.limit is AED 751.25")
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        assertEquals(TxnType.REFUND, r.txn.type)
    }

    @Test fun aTermDepositPaidIntoTheAccountIsNotHiddenAsAnAdvert() {
        val t = "Congrats! Your term deposit Savings is now mature. You've earned AED 12.50 in interest! The total amount of AED 5,012.50 in interest, along with your principal amount of AED 5,000.00, has been credited to your Current Account. Open a new Fixed Saving Space to continue earning interest."
        val r = SmsParser.parseAsBank("Wio", t, at, zone)
        assertTrue(r !is ParseResult.Ignored, "still hidden: $r")
        assertTrue(r is ParseResult.Transaction, "not read: $r")
        assertEquals(TxnType.TRANSFER_IN, r.txn.type)
        assertEquals("5012.50", r.txn.amount.toPlainString())
    }

    @Test fun anAdvertAloneIsStillHidden() {
        val r = SmsParser.parseAsBank("Wio", "Open a new Fixed Saving Space and earn up to 5% interest. Offer ends soon. Terms apply.", at, zone)
        assertTrue(r is ParseResult.Ignored || r !is ParseResult.Transaction)
    }
}
