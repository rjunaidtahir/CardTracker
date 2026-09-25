package com.uaefinancial.tracker.bridge

import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The flat API the iPhone app uses. */
class BridgeTest {
    private val received = DateTime.of(2026, 9, 21, 22, 10).toEpochMillis()

    @Test fun reads_a_purchase() {
        val r = Bridge.readSms("EmiratesNBD", "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12", received, emptyMap())
        assertEquals("transaction", r.kind)
        assertEquals("Emirates NBD", r.bank)
        assertEquals("PURCHASE", r.type)
        assertEquals("CREDIT", r.cardType)
        assertEquals("3944", r.cardLast4)
        assertEquals(2872L, r.amountMinor)
        assertEquals(2872L, r.aedMinor)
        assertEquals(88512L, r.availableMinor)
        assertTrue(r.hasAvailable)
    }

    @Test fun foreign_currency_uses_given_rates() {
        val r = Bridge.readSms("DIB", "Refund of USD 25.00 from AMAZON MKTPL has been credited to your Credit Card ending 1122.", received, mapOf("USD" to "3.6725"))
        assertEquals("REFUND", r.type)
        assertEquals(9181L, r.aedMinor) // 91.8125
        assertTrue(r.fxEstimated)
        assertTrue(r.auto)
    }

    @Test fun statements_otp_and_other_senders() {
        val s = Bridge.readSms("HSBC-UAE", "HSBC Credit Card ending *** 5258 Statement Date 11/08/2026. Total Amt Due AED 79.20, Due Date 05/09/2026. Min. Amt Due AED 33.70.", received, emptyMap())
        assertEquals("statement", s.kind)
        assertEquals(7920L, s.balanceMinor)
        assertEquals(3370L, s.minimumDueMinor)
        assertEquals(CalendarDate.of(2026, 9, 5).toEpochDay(), s.dueEpochDay)
        assertEquals("otp", Bridge.readSms("ADCBAlert", "Your one-time password for online purchase of AED 99.00 is 482913", received, emptyMap()).kind)
        assertEquals("notBank", Bridge.readSms("+971501234567", "hi", received, emptyMap()).kind)
    }

    @Test fun statement_text_and_typed_entries() {
        val st = Bridge.readStatementText(
            """
            Statement Date 09/09/2026
            Payment Due Date 04/10/2026
            Total Amount Due 1,160.40
            Previous Balance 1,200.00
            Date        Description                      Amount
            12/08/2026  TALABAT.COM                        90.90
            20/08/2026  PAYMENT RECEIVED - THANK YOU    1,000.00 CR
            22/08/2026  CARREFOUR                         869.50
            """.trimIndent(),
            2026,
        )
        assertEquals(3, st.rows.size)
        assertEquals(116040L, st.totalDueMinor)
        assertEquals(1, st.addsUp)
        val e = assertNotNull(Bridge.typedEntry("lunch 45"))
        assertEquals(4500L, e.amountMinor)
        assertEquals("PURCHASE", e.type)
        assertNull(Bridge.typedEntry("lunch"))
    }

    @Test fun categories_and_spending() {
        assertEquals(2L, Bridge.guessCategory("TALABAT", "PURCHASE"))
        assertEquals(-1L, Bridge.guessCategory("Card payment", "PAYMENT"))
        assertEquals(5000L, Bridge.spendingContribution("PURCHASE", 5000, true))
        assertEquals(-5000L, Bridge.spendingContribution("REFUND", 5000, true))
        assertEquals(0L, Bridge.spendingContribution("PAYMENT", 5000, true))
        assertEquals(0L, Bridge.spendingContribution("PURCHASE", -1, true))
        assertTrue(Bridge.countsByDefault("CREDIT"))
        assertTrue(Bridge.categories().any { it.name == "Groceries" })
        assertEquals(Bridge.dedupKey("FAB", 0, 1000, "x"), Bridge.dedupKey("AD-FAB", 0, 1000, "x").let { Bridge.dedupKey("FAB", 0, 1000, "x") })
    }

    @Test fun messages_without_a_sender() {
        val enbd = Bridge.readSmsAnySender("Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12", received, emptyMap())
        assertEquals("Emirates NBD", enbd.bank)
        assertEquals("transaction", enbd.kind)
        val other = Bridge.readSmsAnySender("Your card ending 9090 was used for AED 60.00 at LULU HYPERMARKET on 21/09/2026 12:00.", received, emptyMap())
        assertEquals("transaction", other.kind)
        assertEquals(Bridge.UNKNOWN_BANK, other.bank)
        val named = Bridge.readSmsAnySender("RAKBANK: AED 1,200.00 debited from your A/C XXXX5566 on 19/09/2026 for DEWA BILL PAYMENT.", received, emptyMap())
        assertEquals("RAKBANK", named.bank)
        assertEquals("otp", Bridge.readSmsAnySender("123456 is your OTP for AED 50.00 on card ending 1234", received, emptyMap()).kind)
    }

    @Test fun statement_from_pdf_characters() {
        val tsv = com.uaefinancial.tracker.core.StatementFixtures.all.getValue("b7_terse_abbreviations_iso").lines().filterNot { it.startsWith("#") }.joinToString("\n")
        val st = Bridge.readStatementTsv(tsv, 2026)
        assertEquals("2468", st.cardLast4)
        assertEquals(8, st.rows.size)
        assertEquals(1, st.addsUp)
    }

    @Test fun helpers_for_the_iphone_app() {
        assertEquals("FAB ·1234", Bridge.cardKey("FAB", "1234"))
        assertEquals("FAB ·????", Bridge.cardKey("FAB", null))
        assertEquals(10000L, Bridge.toAedMinor(10000, "aed", emptyMap()))
        assertEquals(36725L, Bridge.toAedMinor(10000, "USD", mapOf("USD" to "3.6725")))
        assertEquals(Bridge.NONE, Bridge.toAedMinor(10000, "XYZ", mapOf("USD" to "3.6725")))
        assertTrue(Bridge.defaultRates().containsKey("USD"))
        assertTrue(Bridge.canHaveCategory("PURCHASE"))
        assertFalse(Bridge.canHaveCategory("PAYMENT"))
        val g = Bridge.guessTransaction("Other bank", "AED 45.00 was spent on your card ending 1234 at CARREFOUR on 10/09/2026", 1_757_000_000_000)
        assertEquals(4500L, g?.amountMinor)
    }

    @Test fun statement_rows_missing_from_the_app() {
        val d = CalendarDate.of(2026, 9, 1).toEpochDay()
        val rows = listOf(
            StatementRow(d, "CARREFOUR", 12_000, false, null),
            StatementRow(d + 2, "NOON", 5_000, false, null),
            StatementRow(d + 5, "PAYMENT THANK YOU", 50_000, true, null),
        )
        val app = listOf(AppTxn("a", d + 1, 12_000, false, false), AppTxn("b", d + 5, 50_000, true, false), AppTxn("c", d + 3, 999, false, false))
        assertEquals(listOf("NOON"), Bridge.missingRows(rows, app).map { it.details })
        val r = Bridge.reconcile(rows, app)
        assertEquals(listOf("a", "b"), r.matchedRefs.sorted())
        assertEquals(listOf("c"), r.onlyInAppRefs)
    }

    @Test fun transfer_pairing_rules() {
        val fab = Bridge.readSms("FAB", "Outward Remittance\nDebit\nAccount XXXX8001\nAED 1000.00\nDate 17/09/2026\nBalance AED 2386.00", 1_758_100_000_000, emptyMap())
        assertEquals("transaction", fab.kind)
        assertNotNull(Bridge.pairGroup(fab.ruleId))
        assertTrue(Bridge.pairPartnerRules(fab.ruleId).isNotEmpty())
        assertTrue(Bridge.isAmountSpecific("ACCOUNT DEBIT", "PURCHASE"))
        assertFalse(Bridge.isAmountSpecific("CARREFOUR", "PURCHASE"))
    }
}
