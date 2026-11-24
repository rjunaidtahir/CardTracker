package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.Masker
import com.uaefinancial.tracker.parser.RulesPack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Section 6 of the 2.8 spec: the extra report keys. Invented senders and wordings only. */
class ReportFieldsTest {
    @Test fun keysAreAppendedAfterTheExistingKindWhichNeverChanges() {
        val k = ReportFields.append("unread", listOf("sid" to "ab12cd34ef56ab78", "pv" to "27", "ev" to "8", "st" to "NO_DIRECTION", "fr" to "ACK", "ab" to null))
        assertEquals("unread|sid=ab12cd34ef56ab78|pv=27|ev=8|st=NO_DIRECTION|fr=ACK", k)
        assertTrue(k.startsWith("unread|"))
        val hidden = ReportFields.append("hidden:Advert|reader=PURCHASE", listOf("rule" to "0011223344556677"))
        assertTrue(hidden.startsWith("hidden:Advert|reader=PURCHASE|rule="))
    }

    @Test fun valuesCannotBreakTheFormatOrCarryOddText() {
        assertEquals("a/b:c", ReportFields.clean("a|b=c"))
        assertNull(ReportFields.clean("   "))
        assertNull(ReportFields.clean("\u0001\u0002"))
        val k = ReportFields.append("x", listOf("w" to "Some|Wallet=App\n<script>"))
        assertEquals(1, k.count { it == '=' })
        assertFalse(k.contains('\n')); assertFalse(k.contains('<'))
    }

    @Test fun theKindTextStaysInsideTheFormLimit() {
        val many = (1..80).map { "k$it" to "x".repeat(60) }
        assertTrue(ReportFields.append("unread", many).length <= ReportFields.MAX_KIND)
    }

    @Test fun senderClassNeverCarriesTheSendersLetters() {
        val (sc, len, nb) = ReportFields.senderClass("My Card Chat", null, addedByUser = true)
        assertEquals(ReportFields.SenderClass.USER_NAMED_CHAT, sc)
        assertEquals("11-15", len)
        assertNull(nb)
        assertEquals(ReportFields.SenderClass.LONG_DIGITS, ReportFields.senderClass("+971 50 000 0000", null, false).first)
        assertEquals(ReportFields.SenderClass.SHORT_CODE, ReportFields.senderClass("8258", "8258", false).first)
        assertEquals(ReportFields.SenderClass.PUBLIC_ID_UNKNOWN, ReportFields.senderClass("NEWBANK", "NEWBANK", false).first)
        assertEquals(ReportFields.SenderClass.SPACES, ReportFields.senderClass("Ab Cd", null, false).first)
        val k = ReportFields.append("unread", listOf("sc" to sc, "len" to len, "nb" to nb))
        assertFalse(k.contains("My Card")); assertFalse(k.contains("Card Chat"))
    }

    @Test fun aSenderSpellingABuiltInBankNamesIt() {
        assertEquals("Emirates NBD", ReportFields.senderClass("Emirates NBD", null, true).third)
    }

    @Test fun askEntriesMatchByBankSenderOrHashAndExpire() {
        val asks = listOf(
            RulesPack.Ask(bank = "hsbc", sender = null, hash = null, fields = setOf("layout", "wholeAmount"), until = 30),
            RulesPack.Ask(bank = null, sender = "DFM", hash = null, fields = setOf("fullerWords"), until = 30),
            RulesPack.Ask(bank = null, sender = null, hash = "00aa", fields = setOf("stage"), until = 25),
        )
        assertEquals(setOf("layout", "wholeAmount"), ReportFields.askFor(asks, "hsbc", "HSBC", "ff", 28))
        assertEquals(setOf("fullerWords"), ReportFields.askFor(asks, null, "dfm", "ff", 28))
        assertEquals(emptySet(), ReportFields.askFor(asks, null, null, "00aa", 28), "expired")
        assertEquals(setOf("stage"), ReportFields.askFor(asks, null, null, "00aa", 25))
    }

    @Test fun amountLayoutCarriesNoDigitsOfTheAmount() {
        val r = Masker.mask("Your account 123456***-AED 100.00 2,500.00- TT Payment from 654321. Your available balance is AED 2,400.00", "HSBC", loose = true, fuller = true, layout = true)
        val amt = ReportFields.amountLayout(r.shape)!!
        assertEquals("3.2;1,3.2-;1,3.2", amt)
        assertFalse(amt.contains("100")); assertFalse(amt.contains("2500"))
    }

    @Test fun walletCardShapeKeepsOnlyTheLengthOfTheDigits() {
        assertEquals("Visa_Signature:4", ReportFields.walletCardShape("Paid with Visa Signature 1234"))
        assertNull(ReportFields.walletCardShape("No card here"))
    }

    @Test fun longDigitRunsAreFoundOutsideTokens() {
        assertTrue(ReportFields.hasLongDigitRun("card 12345"))
        assertFalse(ReportFields.hasLongDigitRun("card {CARD:4} {AMT:1,3.2}"))
    }
}
