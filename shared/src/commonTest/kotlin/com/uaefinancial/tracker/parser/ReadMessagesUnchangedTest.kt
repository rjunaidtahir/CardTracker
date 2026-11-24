package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.parser.P28.apply
import com.uaefinancial.tracker.parser.P28.file
import com.uaefinancial.tracker.parser.P28.ignore
import com.uaefinancial.tracker.parser.P28.levers
import com.uaefinancial.tracker.parser.P28.rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rule 5 of the 2.8 spec: a money figure (amount, currency, direction, bank, card key) of a message that is already read
 * correctly must never change because of this release. Every built-in-read message below keeps its figures with EVERY lever
 * on and a busy ext section applied. (The app-side safety net, ReadingGuard, has its own tests: ReadingGuardTest, ReReadGuardDbTest.)
 * Bodies are the invented / masked-style wordings already used by the 2.7 tests.
 */
class ReadMessagesUnchangedTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val senders = listOf("ENBD", "ADCB", "FAB", "Mashreq", "RAKBANK", "EIB", "CBD", "DIB", "AlHilal", "SIMPLYLIFE", "du", "Wio")
    private val bodies = listOf(
        "Credit Card Purchase Card No XXXX0831 AED 15.30 ADNOC ROVE HOTEL 532 DUBAI ARE 19/09/26 19:42 ",
        "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12",
        "Purchase of USD 20.00 with Credit Card ending 3944 at NETFLIX.COM, LOS GATOS. Avl Cr. Limit is AED 500.00",
        "Credit Card XX3538 was used for AED90.90 on 13/09/2026 11:58:47 at talabat.com, DUBAI-AE. Available limit AED4736.08",
        "Purchase of 3,589.95 AED at LIVA INS B S C CLOSED    ABU DHABI    AE on 14-09-2026,  10:20:46, card 3976. Limit: 338.04 AED.",
        "Purchase of AED 12.00 with Debit Card ending 1111 at CARREFOUR, DUBAI. Avl Bal is AED 5,000.00",
        "Debit Card XX2222 was used for AED50.00 on 13/09/2026 10:00:00 at NOON, DUBAI-AE. Available balance AED1000.00",
        "Inward Remittance\nCredit\nAccount XXXX8001\nAED 200.00\nDate 21/09/2026\nBalance AED 2586.00",
        "Outward Remittance\nDebit\nAccount XXXX8001\nAED 1000.00\nDate 17/09/2026\nBalance AED 2386.00",
        "Dear Customer, your funds transfer request of  AED 1,000.00 to IBAN/Account/Card XXXX9940  has been processed ",
        "Payment of AED 96.75 to TEMU.COM with Credit Card ending 9940. Avl Cr. Limit is AED 38,404.74.",
        "Amount of AED 43.81 from Amazon.ae has been credited to your card ending with 3944. Available limit is AED 54,081.68.",
        "Purchase amount of AED 1.00 at ADNOC on your Credit Card ending 9940 has been refunded to your card account. Avl Limit is AED 40,464.60.",
        "Your Cr.Card XXX3538 was used for AED59.50 on 18/08/2026 14:06:42 at talabat.com,DUBAI-AE. Avl. Cr.limit is AED5897.94",
        "Credit Card XXX3538 used for AED1213.94 (+2.99% foreign txn fee) on 12/03/2026 10:00:26 at AGODA.COM AL HAMRA R,London-GB. Avl. Cr.limit AED1671.82",
        "Your Visa card ending 1234 was used for AED 45.50 at NOON on 03-10-2026. Avl bal AED 9,876.00",
        "Debit Card Purchase\nCard No XXXX3333\nAED 7.50\nCOFFEE PLANET DUBAI ARE\n19/09/26 09:15\nAvl Bal AED 900.00",
        "Pymt for CARREFOUR DUBAI of AED 172.94 has been made using Credit Card 71534.Avl.limit AED 4,120.50.",
    )

    private fun figures(r: ParseResult): String? = (r as? ParseResult.Transaction)?.txn?.let {
        listOf(it.amount.toPlainString(), it.currency, it.type.name, it.bank, it.cardLast4 ?: "-", it.cardType.name, it.toLast4 ?: "-").joinToString("|")
    }

    private fun readAll(): Map<String, String?> = senders.flatMap { s -> bodies.map { b -> "$s\u0001$b" to figures(SmsParser.parse(s, b, P28.AT, P28.ZONE)) } }.toMap()

    @Test fun builtInReadingsKeepTheirMoneyFiguresWithEveryLeverOn() {
        RulesPack.apply(null)
        val before = readAll().filterValues { it != null }
        assertTrue(before.size >= 20, "expected plenty of built-in readings, got ${before.size}")
        val all = RulesPack.Lever.all.toTypedArray()
        val ext = levers(*all) +
            ",\"words\":{\"debit\":[\"is taken out by\"],\"credit\":[\"has landed in\"],\"notMoney\":[\"at month end\"]}" +
            ",\"wordingBanks\":[{\"pattern\":\"^Card spend alert\",\"bank\":\"Emirates Islamic\"}]" +
            ",\"rules\":[" + rule("Sharjah Islamic Bank", "sib-x", """Within UAE Funds Transfer of {CUR} {AMOUNT} from account {ACCT}""", type = "TRANSFER_OUT", cardType = "ACCOUNT", extra = ",\"fixedMerchant\":\"Transfer\"") + "]" +
            ",\"ignore\":[" + ignore("Transfer initiated", """\btransfer\b.{0,40}\binitiated\b""", ",\"bank\":\"cbd\",\"unless\":\"\\\\bdebited\\\\b\"") + "]" +
            ",\"readingPolicy\":{\"applyKeyChangeFor\":[\"adcb\"]},\"learnedExempt\":[\"\\\\bcredited to your account\\\\b\"]"
        apply(file(ext = ext))
        val after = readAll()
        for ((k, v) in before) assertEquals(v, after[k], "money figures changed for ${k.substringBefore('\u0001')}: ${k.substringAfter('\u0001').take(50)}")
    }
}
