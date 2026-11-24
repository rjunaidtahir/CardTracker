package com.uaefinancial.tracker.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uaefinancial.tracker.core.IngestOutcome
import com.uaefinancial.tracker.parser.SmsParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 2.8 spec 7.2-7.6 and 15 on a real Room database: a wallet app notification and the bank SMS for the same money become ONE
 * row (lever crossSourceMerge), whichever arrives first; look-alikes stay apart; wallet money-in goes on one account and is
 * not spending. Invented wordings only.
 */
@RunWith(AndroidJUnit4::class)
class WalletMergeDbTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: Repository

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = Repository(db)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setMonthFirstDates(false)
    }

    @After fun tearDown() { Pack28.reset(); db.close() }

    private fun at(h: Int, min: Int = 0): Long = LocalDateTime.of(2026, 11, 20, h, min).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun sms(body: String, t: Long) = runBlocking { repo.ingestSms("CBD", body, t, null, SmsSource.LIVE) }
    private fun notif(body: String, t: Long) = runBlocking { repo.ingestSms("WALLET", body, t, null, SmsSource.NOTIF) }
    private fun txns() = runBlocking { db.dao().txnsListBetween(0L, Long.MAX_VALUE) }.sortedBy { it.id }
    private fun cards() = runBlocking { db.dao().allCards() }

    private val wallet = Pack28.rule("Wallet", "w-visa", """^\s*{MERCHANT}\.\s*{CUR}\s*{AMOUNT}\s+with\s+Visa\s+Signature\s+{CARD}\s*$""", "PURCHASE", "CREDIT")
    private val bankSms = Pack28.rule("CBD", "cbd-cc", """Purchase of {CUR} {AMOUNT} on credit card {CARD} at {MERCHANT}\. Avl limit {ANYCUR} {AVAIL}""", "PURCHASE", "CREDIT")
    private val refundSms = Pack28.rule("CBD", "cbd-refund", """Refund of {CUR} {AMOUNT} on credit card {CARD} from {MERCHANT}\.""", "REFUND", "CREDIT")
    private val banks = listOf(Pack28.bank("Wallet", "WALLET"))
    private fun on(merge: Boolean = true) =
        Pack28.apply(if (merge) listOf("crossSourceMerge", "notificationBanks") else emptyList(), listOf(wallet, bankSms, refundSms), banks,
            "\"notificationBanks\":[{\"titlePattern\":\"^Wallet\",\"bank\":\"Wallet\",\"isAggregator\":true}]")

    private val walletBody = "SOME CAFE. AED 45.00 with Visa Signature 1234"
    private val smsBody = "Purchase of AED 45.00 on credit card XXXX1234 at SOME CAFE DUBAI. Avl limit AED 955.00"

    private fun assertOneMerged() {
        val t = txns().single()
        assertEquals(4_500L, t.amountMinor); assertEquals("AED", t.currency); assertEquals("PURCHASE", t.type)
        assertEquals("merchant from the wallet", "SOME CAFE", t.merchant)
        assertEquals("balance from the SMS", 95_500L, t.availableLimitMinor)
        assertEquals("card from the bank", "CBD", t.bank); assertEquals("1234", t.cardLast4)
        assertFalse("no empty wallet card is left", cards().any { it.bank == "Wallet" })
    }

    @Test fun wallet_notification_then_sms_is_one_transaction() {
        on()
        assertEquals(IngestOutcome.TRANSACTION, notif(walletBody, at(10)))
        assertEquals(IngestOutcome.MERGED, sms(smsBody, at(10, 1)))
        assertOneMerged()
    }

    @Test fun sms_then_wallet_notification_is_one_transaction() {
        on()
        sms(smsBody, at(10))
        assertEquals(IngestOutcome.MERGED, notif(walletBody, at(10, 2)))
        assertOneMerged()
    }

    @Test fun a_re_read_keeps_one_transaction() {
        on()
        notif(walletBody, at(10)); sms(smsBody, at(10, 1))
        runBlocking { repo.reparseAll() }
        assertEquals(1, txns().size); assertEquals(4_500L, txns().single().amountMinor)
    }

    @Test fun different_merchants_of_the_same_amount_stay_two() {
        on()
        notif("GARDEN TOOLS. AED 45.00 with Visa Signature 1234", at(10))
        sms(smsBody, at(10, 1))
        assertEquals(2, txns().size)
    }

    @Test fun purchase_and_refund_of_the_same_amount_stay_two() {
        on()
        notif(walletBody, at(10))
        sms("Refund of AED 45.00 on credit card XXXX1234 from SOME CAFE.", at(10, 1))
        assertEquals(2, txns().size)
    }

    @Test fun the_same_purchase_an_hour_apart_stays_two() {
        on()
        notif(walletBody, at(10))
        sms(smsBody, at(11, 5))
        assertEquals(2, txns().size)
    }

    @Test fun different_amounts_keep_both() {
        on()
        notif(walletBody, at(10))
        sms(smsBody.replace("45.00", "54.00"), at(10, 1))
        assertEquals(2, txns().size)
        assertEquals(setOf(4_500L, 5_400L), txns().map { it.amountMinor }.toSet())
    }

    @Test fun two_candidates_merge_nothing() {
        on()
        notif(walletBody, at(10))
        notif(walletBody.replace("SOME CAFE", "SOME CAFE TWO"), at(10, 3))
        sms(smsBody, at(10, 4))
        assertEquals("ambiguous: keep all three", 3, txns().size)
    }

    @Test fun lever_off_keeps_the_2_7_behaviour() {
        on(merge = false)
        notif(walletBody, at(10))
        sms(smsBody, at(10, 1))
        // 2.7: the bank names differ (Wallet vs CBD), so nothing matched them and both were counted.
        assertEquals(2, txns().size)
    }

    // ---- section 15: wallet money-in with no account or card
    private val moneyIn = Pack28.rule("e& money", "eand-in", """Good news! {CUR} {AMOUNT} from {MERCHANT} in your account\.""", "TRANSFER_IN", "ACCOUNT", ",\"accountNotNamed\":true")

    @Test fun wallet_money_in_goes_on_one_account_and_is_not_spending() {
        Pack28.apply(emptyList(), listOf(moneyIn), listOf(Pack28.bank("e& money", "EANDMONEY")))
        runBlocking {
            repo.ingestSms("EANDMONEY", "Good news! AED 200.00 from SOME FRIEND in your account. Check your new balance in the app.", at(9), null, SmsSource.LIVE)
            repo.ingestSms("EANDMONEY", "Good news! AED 75.00 from OTHER FRIEND in your account. Check your new balance in the app.", at(12), null, SmsSource.LIVE)
        }
        val rows = txns()
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.type == "TRANSFER_IN" })
        val accounts = cards().filter { it.bank == "e& money" }
        assertEquals("exactly one account per wallet, never one per message", 1, accounts.size)
        assertEquals(CardTypes.ACCOUNT, accounts.single().cardType)
        assertFalse("an account is not counted as spending by default", accounts.single().countInSpending)
        assertTrue(rows.all { it.cardKey == accounts.single().cardKey })
    }
}
