package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.TxnType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CoreTest {

    private val body = "Purchase of AED 28.72 with Credit Card ending 3944 at MORE VALUE SUPERMARKE, DUBAI. Avl Cr. Limit is AED 885.12"

    // ------------------------------------------------------------ dedup key

    @Test fun live_and_inbox_copies_get_the_same_key() {
        // Live: SmsMessage.timestampMillis (service centre time); phone clock when received is a bit later.
        val live = SmsKey.of("EmiratesNBD", sentAtMillis = 1_758_477_000_000, receivedAtMillis = 1_758_477_003_412, body = body)
        // Inbox: DATE_SENT = same service-centre time; DATE = phone receive time (different ms).
        val inbox = SmsKey.of("EmiratesNBD", sentAtMillis = 1_758_477_000_000, receivedAtMillis = 1_758_477_002_999, body = "$body\r\n")
        assertEquals(live, inbox)
    }

    @Test fun sender_formatting_does_not_change_key() {
        assertEquals(
            SmsKey.of("HSBC-UAE", 1_000_000, 0, body),
            SmsKey.of("hsbc uae", 1_000_000, 0, body),
        )
    }

    @Test fun different_messages_get_different_keys() {
        val a = SmsKey.of("FAB", 1_000_000, 0, body)
        assertNotEquals(a, SmsKey.of("FAB", 1_000_000, 0, body.replace("28.72", "28.73")), "different text")
        assertNotEquals(a, SmsKey.of("FAB", 2_000_000, 0, body), "same text, different time (e.g. repeated cashback SMS)")
        assertNotEquals(a, SmsKey.of("ADCBAlert", 1_000_000, 0, body), "different sender")
    }

    @Test fun missing_sent_time_falls_back_to_received_time() {
        assertEquals(SmsKey.of("FAB", 0, 5_000, body), SmsKey.of("FAB", null, 5_000, body))
        assertNotEquals(SmsKey.of("FAB", 0, 5_000, body), SmsKey.of("FAB", 5_000, 5_000, body), "s/r prefixes keep the two kinds apart")
    }

    // ------------------------------------------------------------- spending

    @Test fun default_count_in_spending() {
        assertEquals(true, Spending.defaultCountInSpending(CardType.CREDIT))
        assertEquals(false, Spending.defaultCountInSpending(CardType.DEBIT))
    }

    @Test fun spending_rules() {
        val items = listOf(
            Spending.Item(TxnType.PURCHASE, 10_000, "FAB ·0831"),      // +100.00
            Spending.Item(TxnType.REFUND, 2_500, "FAB ·0831"),         // -25.00
            Spending.Item(TxnType.PAYMENT, 90_000, "HSBC ·5258"),      // never counts
            Spending.Item(TxnType.PURCHASE, 5_000, "Emirates NBD ·1111"), // debit card, excluded
            Spending.Item(TxnType.PURCHASE, 4_500, null),              // manual cash entry, counts
            Spending.Item(TxnType.PURCHASE, null, "FAB ·0831"),        // no AED rate, skipped
        )
        assertEquals(10_000L - 2_500 + 4_500, Spending.totalAedMinor(items, excludedCardKeys = setOf("Emirates NBD ·1111")))
        assertEquals(10_000L - 2_500 + 5_000 + 4_500, Spending.totalAedMinor(items, excludedCardKeys = emptySet()))
    }

    @Test fun payments_and_transfers_never_count_even_on_counted_cards() {
        assertEquals(0L, Spending.contributionAedMinor(TxnType.PAYMENT, 90_000, cardCounted = true))
        assertEquals(0L, Spending.contributionAedMinor(TxnType.TRANSFER_OUT, 90_000, cardCounted = true))
        assertEquals(0L, Spending.contributionAedMinor(TxnType.TRANSFER_IN, 90_000, cardCounted = true))
    }

    @Test fun accounts_are_not_counted_by_default() {
        assertEquals(false, Spending.defaultCountInSpending(CardType.ACCOUNT))
    }

    @Test fun rewards_redemption_without_card_reduces_spending() {
        val items = listOf(Spending.Item(TxnType.PURCHASE, 31_395, null), Spending.Item(TxnType.REFUND, 5_000, null))
        assertEquals(26_395L, Spending.totalAedMinor(items, emptySet()))
    }

    // -------------------------------------------------------- review export

    @Test fun review_export_groups_by_shape() {
        val items = listOf(
            ReviewExport.Item("Mashreq", "Get Easy Cash up to AED 50000. STOP 4250"),
            ReviewExport.Item("Mashreq", "Get Easy Cash up to AED 30000. STOP 4250"),
            ReviewExport.Item("FAB", "Something new AED 12.00"),
        )
        val text = ReviewExport.summarize(items)
        assertEquals(true, text.startsWith("UAE Financial Tracker: 3 bank SMS it couldn't read, in 2 formats"))
        assertEquals(true, text.contains("#1 · Mashreq · 2×"), text)
        assertEquals(true, text.contains("#2 · FAB · 1×"), text)
    }

    // ---------------------------------------------------------- sync result

    @Test fun sync_summary_text() {
        val outcomes = List(12) { IngestOutcome.TRANSACTION } + IngestOutcome.STATEMENT +
            List(2) { IngestOutcome.FAILED } + List(5) { IngestOutcome.DUPLICATE } + IngestOutcome.OTP_SKIPPED
        val r = SyncResult.of(outcomes)
        assertEquals("12 new transactions, 1 statement, 2 couldn't be parsed", r.summary())
        assertEquals(21, r.scanned)
        assertEquals("1 new transaction", SyncResult.of(listOf(IngestOutcome.TRANSACTION)).summary())
        assertEquals("Nothing new", SyncResult.of(listOf(IngestOutcome.DUPLICATE, IngestOutcome.OTP_SKIPPED)).summary())
    }
}
