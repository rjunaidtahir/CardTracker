package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.fail

/** Investigation only (branch probe-mashreq-thankyou-0722, never for main): prints how invented variants of the stuck Mashreq shape are read. */
class ProbeMashreqTest {
    @Test fun probe() {
        val zone = SmsParser.UAE_ZONE
        val received = DateTime.of(2026, 11, 15, 12, 31).toEpochMillis(zone)
        val bodies = listOf(
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30:15. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15/11/2026 12:30. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-NOV-2026 12:30 PM. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at AL FUTTAIM, 12345678 on 15-NOV-2026 12:30 PM. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30. Avl.Limit: AED 5,000.00.",
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30 Avl.Limit: AED 5,000.00",
            "Thank you for using your Mashreq card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at Careem Payment on 15-11-2026 12:30. Avl.Limit: AED 5,000.00",
            "Thank you for using your card ending 1234 for AED 45.50 at DUBAI MALL on 15-11-2026 12:30. Avl.Limit: AED 5,000.00 Not you? Call 600",
        )
        val out = StringBuilder("PROBE RESULTS\n")
        for (b in bodies) out.append("R=").append(SmsParser.parse("Mashreq", b, received, zone)).append("  <=  ").append(b.takeLast(75)).append("\n")
        fail(out.toString())
    }
}
