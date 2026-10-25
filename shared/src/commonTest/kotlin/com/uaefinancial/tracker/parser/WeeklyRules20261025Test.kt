package com.uaefinancial.tracker.parser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertIs

/** Weekly rules update (2026-10-25): each new ignore entry in rules/pack.json, tried on a message built from its masked shape. */
class WeeklyRules20261025Test {
    @AfterTest fun reset() { RulesPack.apply(null); SmsParser.setCustomSenders(emptyMap()) }

    private val cases = listOf(
        "Declined: insufficient balance" to "Dear Customer Your Raast transaction of PKR 5,000.00 cannot be processed due to insufficient balance in your account. For details please call 021111111",
        "Notice: transfer limit raised" to "Dear Customer, Bank Alfalah is pleased to increase its limit for raising your limits of PKR 500,000/-, PKR 1,000,000/- in your account. For details call 111222333 or visit your nearest branch.",
        "Notice: IBFT limit advert" to "Dear Customer, Bank Alfalah now allows IBFT transactions up to PKR 1,000,000/- daily on all of your accounts via Mobile App and across our ATMs in Pakistan. For IBFT transaction charges of PKR 25/- please refer to our schedule of charges available on our website or call 111222333333.",
        "Advert: instant credit branches" to "Offer has launched of Rs of PKR 5/- only. Visit any of our branches for instant credit to your account.",
        "Telecom bill reminder" to "Your bill limit alert is 80.00 %. Total payable are AED 123.45. Pay Your Bill via My App https://x.y &dial 123x",
        "Telecom bill due notice" to "Dear Customer Your total bill for Billing of May is AED 123.45, Please pay by 10-Nov-2026and visit our site"
    )

    @Test fun eachNewIgnoreRuleStopsItsNoticeBeingRead() {
        SmsParser.setCustomSenders(mapOf("PKBANK" to "Some Bank"))
        val out = RulesPack.parse("""{"version":2,"minEngine":5,"banks":[],"rules":[],"ignore":[{"label":"Declined: insufficient balance","pattern":"cannot be processed due to insufficient balance"},{"label":"Notice: transfer limit raised","pattern":"is pleased to .{1,120} in your account\\. For .{1,30} call .{1,20} or visit your .{1,30} branch"},{"label":"Notice: IBFT limit advert","pattern":"now .{1,30} IBFT transactions up to [A-Z]{3} [\\d,.]+/-"},{"label":"Advert: instant credit branches","pattern":"branches for instant credit to your account"},{"label":"Telecom bill reminder","pattern":"Total payable are [A-Z]{3} [\\d,.]+"},{"label":"Telecom bill due notice","pattern":"Your total bill for .{1,40} is [A-Z]{3} [\\d,.]+, Please pay by"}]}""")
        assertIs<RulesPack.Outcome.Ok>(out)
        RulesPack.apply(out.pack)
        for ((label, body) in cases) {
            val r = SmsParser.parse("PKBANK", body, 0L)
            assertIs<ParseResult.Ignored>(r, label)
        }
    }
}
