package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.fail

/** Investigation only (branch probe-mashreq-thankyou-0722, never for main). A variant that is NOT read as a purchase fails with a short tag. */
class ProbeMashreqTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 11, 15, 12, 31).toEpochMillis(zone)
    private fun chk(tag: String, body: String) {
        val r = SmsParser.parse("Mashreq", body, received, zone)
        val ok = r is ParseResult.Transaction && r.txn.type.name == "PURCHASE" && r.txn.amount.toString() == "45.50"
        if (!ok) fail("NOTREAD[$tag] ${r.toString().take(40)}")
    }
    private val tail = "Avl.Limit: AED 5,000.00"
    @Test fun v1() = chk("v1-dash-date-pm", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-NOV-2026 12:30 PM. $tail")
    @Test fun v2() = chk("v2-comma-num", "Thank you for using your card ending 1234 for AED 45.50 at AL FUTTAIM, 12345678 on 15-NOV-2026 12:30 PM. $tail")
    @Test fun v3() = chk("v3-no-dot", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30 $tail")
    @Test fun v4() = chk("v4-trailing-dot", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30. Avl.Limit: AED 5,000.00.")
    @Test fun v5() = chk("v5-payment-word", "Thank you for using your card ending 1234 for AED 45.50 at Careem Payment on 15-11-2026 12:30. $tail")
    @Test fun v6() = chk("v6-on-in-name", "Thank you for using your card ending 1234 for AED 45.50 at NOON ON DEMAND on 15-11-2026 12:30. $tail")
    @Test fun v7() = chk("v7-and-in-name", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI HILLS AND MALL on 15-11-2026 12:30. $tail")
    @Test fun v8() = chk("v8-home", "Thank you for using your card ending 1234 for AED 45.50 at HOME CENTRE LLC on 15-11-2026 12:30. $tail")
    @Test fun v9() = chk("v9-gold-point", "Thank you for using your card ending 1234 for AED 45.50 at JOYALUKKAS GOLD POINT LLC on 15-11-2026 12:30. $tail")
    @Test fun v10() = chk("v10-emirates", "Thank you for using your card ending 1234 for AED 45.50 at Emirates Airline on 15-11-2026 12:30. $tail")
    @Test fun v11() = chk("v11-seconds", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30:45. $tail")
    @Test fun v12() = chk("v12-slash", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15/11/2026 12:30. $tail")
    @Test fun v13() = chk("v13-dubai-comma", "Thank you for using your card ending 1234 for AED 45.50 at DUBAI, UAE on 15-11-2026 12:30. $tail")
    @Test fun v14() = chk("v14-purchasing", "Thank you for purchasing your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30. $tail")
}
