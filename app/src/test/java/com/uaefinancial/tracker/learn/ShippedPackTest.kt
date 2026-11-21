package com.uaefinancial.tracker.learn

import com.uaefinancial.tracker.parser.ParseResult
import com.uaefinancial.tracker.parser.RulesPack
import com.uaefinancial.tracker.parser.SmsParser
import com.uaefinancial.tracker.parser.TxnType
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The exact rules/pack.json that is about to be signed and published must pass the app's own checks. */
class ShippedPackTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val file = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }

    @Test fun shippedPackIsAcceptedByTheAppAndTracksTheNewSenders() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        assertTrue(out.pack.version >= 5)
        assertTrue(out.pack.rules.size >= 10) // grows with each published pack (v6: 18)
        assertTrue(out.pack.ignore.size >= 13) // v6: 18
        val tracked = out.pack.banks.filter { it.track }.associate { it.name to it.senderIds }
        assertEquals(mapOf("Tabby" to listOf("Tabby"), "ADCB" to listOf("SIMPLYLIFE")), tracked)
        RulesPack.apply(out.pack)
        assertEquals("ADCB", SmsParser.bankFor("SIMPLYLIFE")?.name)
        assertEquals("Tabby", SmsParser.bankFor("AD-Tabby")?.name)
        val tamara = out.pack.banks.first { it.name == "Tamara" }
        assertNotNull(tamara)
        assertEquals(null, SmsParser.bankFor("Tamara")) // names only, no sender claimed without proof
    }

    @Test fun realMessagesFromTheTwoSendersAreRead() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4) as RulesPack.Outcome.Ok
        RulesPack.apply(out.pack)
        val simply = SmsParser.parse("SIMPLYLIFE", "Your Cr.Card XXX5886 was used for USD30.60 on 04/08/2023 11:04:30 at OW *passcert.com,London-GB. Avl. Cr.limit is AED2882.95", 0L)
        assertIs<ParseResult.Transaction>(simply)
        val tabby = SmsParser.parse("Tabby", "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94.", 0L)
        assertIs<ParseResult.Transaction>(tabby)
        assertEquals("9", tabby.txn.amount.toString().removeSuffix(".00").removeSuffix(".0"))
    }

    @Test fun v7IgnoresTheTabbyLimitNoticeAndTheRakbankStatementNotice() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4) as RulesPack.Outcome.Ok
        assertTrue(out.pack.version >= 7)
        RulesPack.apply(out.pack)
        // the two notices are ignored
        val limit = SmsParser.parse("Tabby", "Your Tabby Card total limit is now increased to AED 3,000.00", 0L)
        assertIs<ParseResult.Ignored>(limit)
        val zero = out.pack.ignore.first { it.label.startsWith("Statement notice with no due date") }
        val re = Regex(zero.pattern, RegexOption.IGNORE_CASE)
        // The notice carries no due date, so the app cannot track it as a statement: it is ignored whatever the amounts are.
        assertTrue(re.containsMatchIn("Your CC Statement for 526039XXXXXX2712 is generated; CC has a credit balance of AED 0 with Min Due AED 0.00")) // the real message
        assertTrue(re.containsMatchIn("Your CC Statement for 526039XXXXXX2712 is generated; CC has a credit balance of AED 1,234.50 with Min Due AED 62.00"))
        assertTrue(re.containsMatchIn("Your CC Statement for 526039XXXXXX2712 is generated; CC has a credit balance of AED 0.00 with Minimum Due AED 0.00"))
        // ordinary statements with a due date, and ordinary Tabby messages, are not ignored
        assertTrue(!re.containsMatchIn("Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 on 30 Jun, 2026."))
        val spent = SmsParser.parse("Tabby", "You spent AED 9.00 at ISLAND SHELL CAFETERIA. Your available Tabby Card limit is now AED 2488.94.", 0L)
        assertIs<ParseResult.Transaction>(spent)
        val stmt = SmsParser.parse("Tabby", "Your statement for AED 511.06 is ready. We'll charge a minimum amount of AED 127.78 to your default payment method on 30 Jun, 2026.", 0L)
        assertTrue(stmt !is ParseResult.Ignored)
    }

    /**
     * v11: telecom / prepaid notices and the older RAKBANK statement wording that the app could not read (15 reports, 21 Nov).
     * The wordings below are rebuilt from the masked shapes (no real message is stored); each class is hidden, and the
     * ordinary spend / credit wordings that sit next to them are not.
     */
    @Test fun v11HidesTheTelecomNoticesAndTheOlderRakbankStatement() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4) as RulesPack.Outcome.Ok
        assertTrue(out.pack.version >= 11)
        RulesPack.apply(out.pack)
        fun hidden(label: String, vararg texts: String) {
            val rule = out.pack.ignore.first { it.label == label }
            val re = Regex(rule.pattern, RegexOption.IGNORE_CASE)
            for (t in texts) assertTrue(re.containsMatchIn(t), "$label should hide: $t")
        }
        hidden("Statement notice: credit balance",
            "Your Card Statement for 526039XXXXXX2712 is generated; Card has a credit balance of AED 0 with Min Due AED 0.00",
            "Your Card Statement for 526039XXXXXX2712 is generated; CC has a credit balance of AED 1,234.50 with Minimum Due AED 62.00")
        hidden("Telecom: account credited balance", "Your account has been credited successfully. Your new balance is AED 25.50. Dial *135# to check now.")
        hidden("Telecom: recharge thanks", "Thanks for the recharge. Your new balance for 0551234567 is AED 30.00. Manage from the du App https://du.ae or dial *135#.")
        hidden("Telecom: recharge credit added", "Thanks for using Du Pay. AED 5.00 cashback on recharge has been credited on your prepaid number, valid for 30 days.")
        hidden("Telecom: top-up receipt", "Dear Customer, You have successfully topped up your number successfully via Pay Mobile number:971551234567 Amount: AED 50.00 Transaction Type: Topup Transaction ID: 123456789012 Date: 17/11/2026 17:22:46")
        hidden("Telecom: spend limit set", "You have successfully changed the limit to AED 500.00. You can change the cooling time by sending LIMIT your-amount to 1234. To set a limit of AED 100, send LIM 1 For AED 200, send LIM 2")
        hidden("Telecom: spend limit reached", "The transaction you are trying to make is exceeding your spend limit. your monthly limit is AED 500.00 and you have spent AED 480.00. To change your monthly limit, send LIMIT your-amount to 1234.")
        hidden("Telecom: international pack", "You have successfully subscribed to Make Your Calls on with 100 international minutes to Pakistan , for AED 25.00 (incl 5% VAT) as a one-time charge and valid for 30 days. To cancel your pack, please dial *135# or visit https://eand.ae")
        hidden("Notice: wallet transfer fee change", "We have updated our terms & conditions. Please visit https://dupay.ae Note: The fee for sending money to any UAE bank account will be changed to AED 3.00 (excl VAT), effective 01/12/2026. For help contact us at 600566666.")
        // the older RAKBANK statement is now ignored end to end
        val r = SmsParser.parse("RAKBANK", "Your Card Statement for 526039XXXXXX2712 is generated; Card has a credit balance of AED 0 with Min Due AED 0.00", 0L)
        assertIs<ParseResult.Ignored>(r)
        // nothing real is swallowed: card spends, credits and statements with a due date keep reading
        val keep = listOf(
            "Your credit card ending 2712 was used for AED 45.00 at CARREFOUR on 17/11/2026. Available limit is AED 5,000.00",
            "AED 500.00 has been credited to your account 123456XXXX on 17/11/2026. Balance is AED 1,500.00",
            "You have spent AED 480.00 on your card ending 1234 at NOON. Your spend limit is AED 500.00 and you have spent AED 480.00 this month",
            "Your statement for AED 511.06 is ready. Minimum due AED 127.78 on 30 Jun 2026.",
            "Fee of AED 3.00 for sending money to your UAE bank account has been charged",
        )
        for (t in keep) for (rule in out.pack.ignore.takeLast(9)) {
            assertTrue(!Regex(rule.pattern, RegexOption.IGNORE_CASE).containsMatchIn(t), "${rule.label} must not hide: $t")
        }
    }

    /**
     * v12: shapes reported unread by 2.6 phones on 21 Nov (ADIB, ENBD cheque, Kotak, IDFC, Wio, ADCB, FAB). The wordings are
     * rebuilt from the masked shapes (no real message is stored). Each must now be read as the right kind of transaction,
     * or hidden as a notice; none of them may stay unread.
     */
    @Test fun v12ReadsOrHidesTheOtherUnreadShapes() {
        val out = RulesPack.parse(file.readText(), currentVersion = 4) as RulesPack.Outcome.Ok
        assertTrue(out.pack.version >= 12)
        RulesPack.apply(out.pack)
        // Kotak and IDFC are directory banks: the phone reads them once the user has made the sender a bank (parseAsBank).
        fun read(sender: String, text: String) = when (sender) {
            "KOTAKB" -> SmsParser.parseAsBank("Kotak Mahindra Bank", text, 0L)
            "IDFCFB" -> SmsParser.parseAsBank("IDFC FIRST Bank", text, 0L)
            else -> SmsParser.parse(sender, text, 0L)
        }
        fun txn(sender: String, text: String, type: TxnType, amount: String) {
            val r = read(sender, text)
            assertIs<ParseResult.Transaction>(r, "$sender should be read: $text -> $r")
            assertEquals(type, r.txn.type, text)
            assertEquals(amount, r.txn.amount.toString().replace(",", "").removeSuffix(".00"), text)
        }
        fun hidden(sender: String, text: String) {
            val r = read(sender, text)
            assertIs<ParseResult.Ignored>(r, "$sender should be hidden: $text -> $r")
        }
        txn("ADIB", "Trx. of AED 36.50 on your card ending 1234 at ONE-TIME PAY, UAE is Approved. Avl. card bal is 12345.67. Trx Date: 17/11/2026 10:20:46", TxnType.PURCHASE, "36.50")
        txn("ADIB", "Trx. of AED 80.00 on your card ending 1234 at CARREFOUR, UAE is Approved. Avl. card bal is 12345.67. Trx Date: 17/11/2026 10:20:46", TxnType.PURCHASE, "80")
        txn("ENBD", "Dear Customer, the cheque number 123456 for AED 1,500.00 has been debited from account 101-12345-01 on 17/11/2026 10:20.", TxnType.TRANSFER_OUT, "1500")
        txn("ENBD", "Dear Customer, the cheque number 123456 for AED 1,500.00 has been debited from account 101-12345-01 on 17/11/2026 10:20.Available Balance AED 5,000.00", TxnType.TRANSFER_OUT, "1500")
        txn("KOTAKB", "INR 500.00 sent from Kotak Bank ac X1234 and credited to Kotak ac X5678 on 17-11-26 10.20 AM. UTR- 123456789012. Bal:INR 1,000.00. Use kotak.com for help. PAY A CONTACT via kotak.com", TxnType.TRANSFER_OUT, "500")
        txn("KOTAKB", "Fund transfer of INR 750.00 to JOHN DOE from Kotak Bank A/c X123456 scheduled on 17-11-26 has been executed on 17-11-26. Ref No. 12345678.", TxnType.TRANSFER_OUT, "750")
        txn("IDFCFB", "INR 100.00 paid from IDFC FIRST Bank A/c X1234 for UPI ref no. 5678 at SOME SHOP on 17-Nov-2026 10:20. Thank you. Bal.: INR 2,000.00.", TxnType.PURCHASE, "100")
        txn("Wio", "We made an autopay of AED 250.00 towards your Wio Credit balance.", TxnType.PAYMENT, "250")
        hidden("KOTAKB", "Fund transfer of INR 750.00 to JOHN DOE Kotak Bank Ac X1234 is scheduled on 20-11-26 Ref: 12345678. If not by you, REPORT FRAUD on kotak.com - Kotak Bank")
        hidden("KOTAKB", "Transaction on your Kotak Bank Credit Card x1234 of INR 100.00 at EMIRATES could not be approved. Please call 18002100 for details.")
        hidden("Wio", "Payment of AED 20.00 at UBER*TRIP HOLD was reversed and the amount has been credited back to your Wio Debit card ending with 1234.")
        hidden("Wio", "Virtual card \"Online AED 500\" 1234 has been successfully created. If it wasn't you, freeze this card in the App and contact us immediately.")
        hidden("Wio", "Your daily transaction limit for local & international transfers is now AED 50,000.00. If you didn't request this, call Wio Care at 600500000.")
        hidden("Wio", "Your credit payment is due today. Statement amount: AED 300.00. Minimum due: AED 30.00. Auto-pay will be processed at 10:00 PM today. You can pay the full amount in the app before then.")
        hidden("ADCB", "Dear Customer, Your request number 12345678901 for 123456 Mobile Update Contact Details has been completed for ADCB on 17/11/2026 10:20. Charges AED 0.00 Regards ADCB Cash Services")
        hidden("FAB", "Dear Customer, Thank you for contacting to us with your FAB credit card. As to your confirmation, the request AED 1,000.00 is updated and credit card limit request has been processed.")
    }
}

/**
 * Checks the shop-name category words in the exact rules/pack.json about to be published. rules/merchants.json holds one
 * real example name per published word group (written by the daily merchant review); each must file under the category
 * the review chose once the shipped pack is applied, and no word may change what the built-in keywords already file.
 */
class ShippedCategoryWordsTest {
    @AfterTest fun reset() { RulesPack.apply(null) }

    private val packFile = listOf(File("../rules/pack.json"), File("rules/pack.json")).first { it.exists() }
    private val examplesFile = listOf(File("../rules/merchants.json"), File("rules/merchants.json")).first { it.exists() }

    @Test fun everyExampleFilesWhereTheReviewChose() {
        val out = RulesPack.parse(packFile.readText(), currentVersion = 0)
        assertIs<RulesPack.Outcome.Ok>(out, (out as? RulesPack.Outcome.Rejected)?.reason)
        RulesPack.apply(out.pack)
        // rules/merchants.json: {"name": "...", "category": "..."} pairs (read with a regex: the JSON helper is internal to shared)
        val pair = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"category\"\\s*:\\s*\"([^\"]+)\"")
        val examples = pair.findAll(examplesFile.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        for ((name, category) in examples) {
            val want = com.uaefinancial.tracker.parser.CategoryRules.defaults.first { it.name.equals(category, true) }.id
            assertEquals(want, com.uaefinancial.tracker.parser.CategoryRules.guess(name, com.uaefinancial.tracker.parser.TxnType.PURCHASE), "example $name")
        }
        // every published word must file at least one example (no word goes out untested)
        for ((_, words) in out.pack.categories) for (w in words) {
            assertTrue(examples.any { it.first.uppercase().contains(w) }, "word $w has no example in rules/merchants.json")
        }
    }

    @Test fun builtInFilingIsNeverChangedByThePack() {
        val tp = com.uaefinancial.tracker.parser.TxnType.PURCHASE
        val names = listOf("CARREFOUR CITY", "EPPCO SITE 91", "GAZANIA RESTAURANT", "AMAZONUFR DI", "ADNOC 1234", "NOON FOOD", "DEWA", "SALIK", "TALABAT", "LULU HYPERMARKET")
        val before = names.map { com.uaefinancial.tracker.parser.CategoryRules.guess(it, tp) }
        val out = RulesPack.parse(packFile.readText(), currentVersion = 0) as RulesPack.Outcome.Ok
        RulesPack.apply(out.pack)
        assertEquals(before, names.map { com.uaefinancial.tracker.parser.CategoryRules.guess(it, tp) })
    }
}
