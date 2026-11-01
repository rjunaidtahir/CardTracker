package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/** Weekly rules update (2026-11-01): the new ignore entry, tried on a message built from its masked shape. */
class WeeklyRules20261101Test {
    @AfterTest fun reset() { RulesPack.apply(null); SmsParser.setCustomSenders(emptyMap()) }

    @Test fun failedPaymentNoticeIsIgnored() {
        SmsParser.setCustomSenders(mapOf("AEPAY" to "Some Pay"))
        val out = RulesPack.parse("""{"version":3,"minEngine":5,"banks":[],"rules":[],"ignore":[{"label":"Failed payment notice","pattern":"Your payment of [A-Z]{3} [\\d,.]+ is failed"}]}""")
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        val r = SmsParser.parse("AEPAY", "Bill payment. 👆 Your payment of AED 123.45 is failed.", 0L)
        assertIs<ParseResult.Ignored>(r)
    }
}
