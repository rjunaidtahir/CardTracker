package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import com.uaefinancial.tracker.core.Decimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Money-movement formats reported by real users (masked shapes in the learning Sheet), rebuilt with invented values.
 * Each must be read as a transaction of the right amount and direction even when the sender is not a known bank.
 */
class PakistanTransferFormatsTest {
    private val zone = SmsParser.UAE_ZONE
    private val received = DateTime.of(2026, 10, 19, 9, 0).toEpochMillis(zone)

    private fun read(body: String): ParseResult = SmsParser.parseAsBank("Test Bank", body, received, zone)

    private fun out(body: String, amount: String) {
        val r = read(body)
        assertIs<ParseResult.Transaction>(r, "not read as a transaction: $body -> $r")
        assertEquals(Decimal(amount), r.txn.amount, body)
        assertEquals(TxnType.TRANSFER_OUT, r.txn.type, body)
    }

    @Test fun ibft_via_mobile_app() = out(
        "You have made an IBFT of PKR 5,000.00 from A/C 1234****7890 to *4321 via Mobile App on 12-Oct-2026 14:20", "5000.00")

    @Test fun ibft_through_mobile() = out(
        "Dear Customer, You have made an IBFT of PKR 2,500.00 from A/C 1234567890 to 9876543210 through Bank Mobile/App on 12-Oct-2026, 14:59.", "2500.00")

    @Test fun ibft_to_other_bank_limited() = out(
        "Dear Customer, You made an IBFT of PKR 1,000.00 from A/C 12345678901234567 to 12AB Bank Limited through ABC MOBILE on 19-Oct-2026 , 05:07.", "1000.00")

    @Test fun ibft_short_wording() = out(
        "Dear Customer, You made an IBFT of PKR 750.00 from A/C 12345678901234567 to 98765432 Bank Limited through ABC on 19-Oct-2026 , 06:25.", "750.00")

    @Test fun raast_fund_transfer_from_iban() = out(
        "Dear Customer, you have made a Fund Transfer of PKR 3,000.00 from IBAN PK12ABCD0000001234567890 through ABC Raast to Ali Khan on 18-Oct-2026 at 07:50. For details, please call: (042-111) 123456789", "3000.00")

    @Test fun fund_transfer_from_bank_account() = out(
        "Dear Customer, You have made a Fund Transfer of PKR 4,000.00 from Bank ABC A/C 12345678901234567 on 20-Oct-2026 10:15", "4000.00")

    @Test fun reversal_of_a_failed_transfer_is_money_back() {
        val body = "Dear Customer, Debit to your account for failed IBFT transaction has been reversed and PKR 500.00 credited to your account 1234-5678-******-12-3. Helpline 111222333"
        val r = read(body)
        assertIs<ParseResult.Transaction>(r, "not read as a transaction: $body -> $r")
        assertEquals(Decimal("500.00"), r.txn.amount)
        assertEquals(TxnType.TRANSFER_IN, r.txn.type)
    }

    @Test fun credit_with_fee_note() {
        val body = "Your account has been successfully credited with AED 1,000.00. This amount less AED 5.00 fee will be deducted from your account"
        val r = read(body)
        assertIs<ParseResult.Transaction>(r, "not read as a transaction: $body -> $r")
        assertEquals(Decimal("1000.00"), r.txn.amount)
        assertEquals(TxnType.TRANSFER_IN, r.txn.type)
    }
}
