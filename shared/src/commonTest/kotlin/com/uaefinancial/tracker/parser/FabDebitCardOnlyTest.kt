package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** FAB debit card purchase that does not name the account (real sample, 7 Mar 2026). */
class FabDebitCardOnlyTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 3, 7, 19, 52).toEpochMillis(zone)

    @Test fun debit_purchase_without_account_line_keeps_merchant_and_card() {
        val r = SmsParser.parse("FAB", "Debit Card Purchase   Card XXXX5207 AED 100.00 SmartDXBGov-PRKN      DUBAI           AE  07/03/26 19:51  Balance AED 85019.44", received, zone)
        assertIs<ParseResult.Transaction>(r, "got $r")
        val t = r.txn
        assertEquals("fab-debit-card-purchase-card-only", r.ruleId)
        assertEquals(TxnType.PURCHASE, t.type)
        assertEquals(CardType.DEBIT, t.cardType)
        assertEquals("5207", t.cardLast4)
        assertEquals(Decimal("100.00"), t.amount)
        assertTrue(t.merchant.startsWith("SmartDXBGov-PRKN"), "merchant was '${t.merchant}'")
        assertEquals(DateTime.of(2026, 3, 7, 19, 51).toEpochMillis(zone), t.timestamp)
        assertEquals(Decimal("85019.44"), t.availableLimit)
    }

    @Test fun the_account_variant_still_wins_when_the_account_is_named() {
        val r = SmsParser.parse("FAB", "Debit Card Purchase  Debit  Account XXXX4016  Card XXXX9704 AED 245.00 JUMEIRAH HOTEL AT ETIHABU DHABI      AE  17/12/18 18:39  Available Balance AED 39686.35", received, zone)
        assertIs<ParseResult.Transaction>(r, "got $r")
        assertEquals("fab-debit-card-purchase", r.ruleId)
        assertEquals("4016", r.txn.cardLast4)
    }
}
