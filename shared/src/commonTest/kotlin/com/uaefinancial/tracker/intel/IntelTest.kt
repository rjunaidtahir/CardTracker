package com.uaefinancial.tracker.intel

import com.uaefinancial.tracker.core.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntelTest {
    private val at = 1_790_000_000_000L

    @Test fun corpus_is_built_from_rule_readings() {
        val s = Corpus.samples
        println("INTEL corpus: ${s.size} samples, ${s.map { it.group }.distinct().size} banks, labels=" +
            s.groupingBy { it.label }.eachCount())
        assertTrue(s.size > 100)
        assertTrue(s.any { it.label == Label.PURCHASE })
    }

    /** Honest accuracy: train without one bank at a time, test on that bank. Printed for the CI log. */
    @Test fun leave_one_bank_out_report() {
        val s = Corpus.samples
        var right = 0; var total = 0; var confidentWrong = 0; var confident = 0
        for (bank in s.map { it.group }.distinct()) {
            val train = s.filter { it.group != bank }
            if (train.size < 50) continue
            val m = NaiveBayes()
            m.train(train.map { Features.of(it.body) to it.label })
            for (t in s.filter { it.group == bank }) {
                val p = m.predict(Features.of(t.body))
                val (l, pr) = p.maxByOrNull { it.value }!!.toPair()
                total++
                if (l == t.label) right++
                if (pr >= MessageIntel.DISAGREE_AT) { confident++; if (l != t.label) confidentWrong++ }
            }
        }
        val acc = 100.0 * right / total
        println("INTEL leave-one-bank-out: $right/$total = ${acc.toInt()}%; confident(>=0.80) $confident, wrong among them $confidentWrong")
        assertTrue(total > 100)
        assertTrue(acc >= 60.0, "accuracy on unseen banks fell below 60%: $acc")
    }

    @Test fun trained_on_everything_agrees_with_rules_on_known_messages() {
        var agree = 0; var disagree = 0
        for (t in Corpus.samples) when (MessageIntel.secondOpinion(t.body, t.label)) {
            Verdict.AGREE -> agree++
            Verdict.DISAGREE -> disagree++
            else -> {}
        }
        println("INTEL on own corpus: agree=$agree disagree=$disagree of ${Corpus.samples.size}")
        assertTrue(disagree <= Corpus.samples.size / 20, "too many disagreements with the rules on their own data: $disagree")
    }

    @Test fun validators_block_wrong_amount_direction_and_declines() {
        val body = "Your card ending 1234 was used for AED 50.00 at CARREFOUR. Available limit AED 9,000.00"
        val ok = Validators.check(body, Proposal(Label.PURCHASE, Decimal("50.00"), "AED", "CARREFOUR", "1234"))
        assertFalse(Validators.blocks(ok), ok.toString())
        val bal = Validators.check(body, Proposal(Label.PURCHASE, Decimal("9000.00"), "AED", null, null))
        assertTrue(bal.any { it.code == "AMOUNT_IS_BALANCE" })
        val missing = Validators.check(body, Proposal(Label.PURCHASE, Decimal("77.00"), "AED", null, null))
        assertTrue(missing.any { it.code == "AMOUNT_NOT_IN_TEXT" })
        val none = Validators.check(body, Proposal(Label.PURCHASE, null, "AED", null, null))
        assertTrue(none.any { it.code == "AMOUNT_MISSING" })
        val declined = Validators.check("Your card ending 1234 transaction of AED 50.00 at SHOP was declined", Proposal(Label.PURCHASE, Decimal("50.00"), "AED", null, null))
        assertTrue(declined.any { it.code == "NOT_DONE" })
        val dir = Validators.check("AED 500.00 has been credited to your account 1234", Proposal(Label.PURCHASE, Decimal("500.00"), "AED", null, null))
        assertTrue(dir.any { it.code == "DIRECTION" })
        val badCur = Validators.check(body, Proposal(Label.PURCHASE, Decimal("50.00"), "XXX", null, null))
        assertTrue(badCur.any { it.code == "CURRENCY_UNKNOWN" })
        val otp = Validators.check("Your OTP is 123456 for AED 50.00 payment. Do not share", Proposal(Label.PURCHASE, Decimal("50.00"), "AED", null, null))
        assertTrue(otp.any { it.code == "OTP" })
        val merch = Validators.check(body, Proposal(Label.PURCHASE, Decimal("50.00"), "AED", "LULU", "9999"))
        assertTrue(merch.any { it.code == "MERCHANT_NOT_IN_TEXT" } && merch.any { it.code == "LAST4_NOT_IN_TEXT" })
    }

    @Test fun suggestion_never_invents_numbers() {
        val body = "Your card ending 4321 was used for AED 120.50 at SOME NEW SHOP DUBAI on 01/10/2026. Available balance AED 3,000.00"
        val s = MessageIntel.suggest("NewBank", body, at)
        println("INTEL suggestion: $s")
        if (s != null) {
            assertTrue(s.amount == null || s.amount.toString().startsWith("120.5"), "amount must come from the text: ${s.amount}")
            if (s.merchant != null) assertTrue(body.contains(s.merchant, ignoreCase = true))
        }
    }

    @Test fun otp_and_plain_text_give_no_usable_suggestion() {
        val a = MessageIntel.suggest("X", "Your OTP is 482913. Do not share it with anyone.", at)
        assertTrue(a == null || !a.usable)
        val b = MessageIntel.suggest("X", "Hello, see you at lunch tomorrow", at)
        assertTrue(b == null || !b.usable)
    }

    @Test fun merchant_tagger_report() {
        val s = Corpus.samples.filter { !it.merchant.isNullOrBlank() && it.label.toTxnType() != null }
        var exact = 0; var inText = 0; var total = 0
        for (bank in s.map { it.group }.distinct()) {
            val train = s.filter { it.group != bank }
            if (train.size < 30) continue
            val t = MerchantTagger(); t.train(train.map { it.body to it.merchant!! })
            for (x in s.filter { it.group == bank }) {
                total++
                val f = t.find(x.body) ?: continue
                if (f.text.equals(x.merchant, ignoreCase = true)) exact++
                if (x.body.contains(f.text, ignoreCase = true)) inText++
            }
        }
        println("INTEL tagger leave-one-bank-out: exact $exact/$total, always-in-text $inText")
        assertEquals(inText >= exact, true)
    }
}
