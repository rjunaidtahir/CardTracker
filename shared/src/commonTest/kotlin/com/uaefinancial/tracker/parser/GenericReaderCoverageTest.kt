package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.DateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The generic reader (no bank rule) on the wordings of messages that bank rules read today (owner export, 21 Nov 2026).
 * Shop names, amounts and card digits invented. If the bank rule were ever missing, these must still be read, not hidden.
 */
class GenericReaderCoverageTest {
    private val zone = SmsParser.UAE_ZONE
    private val at = DateTime.of(2026, 11, 19, 12, 0).toEpochMillis(zone)
    private fun read(bank: String, s: String) = SmartParser.read(bank, SmsParser.normalizeBody(s), at, zone)

    private fun purchase(bank: String, s: String, amount: String) {
        val r = read(bank, s)
        assertTrue(r is ParseResult.Transaction, "not read: $r for: $s")
        assertEquals(TxnType.PURCHASE, r.txn.type)
        assertEquals(amount, r.txn.amount.toPlainString())
    }

    @Test fun enbdPurchaseWithAvailableLimit() =
        purchase("Emirates NBD", "Purchase of AED 120.50 with Credit Card ending 1234 at ZORBO QUILLEX, DUBAI. Avl Cr. Limit is AED 8,450.00", "120.50")

    @Test fun fabPurchaseWithAedBalance() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 AED 85.00 ZORBO QUILLEX 12/11/26 14:05 Available Balance AED 4,500.00", "85.00")

    @Test fun fabForeignPurchaseWithAedBalance() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 USD 25.00 ZORBO QUILLEX 12/11/26 14:05 Available Balance AED 4,500.00", "25.00")

    @Test fun fabForeignPurchaseWithCountryCode() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 USD 25.00 ZORBO QUILLEX AE 12/11/26 14:05 Available Balance AED 4,500.00", "25.00")

    @Test fun fabPurchaseWithStatementDueFooter() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 AED 85.00 ZORBO QUILLEX 12/11/26 14:05 Available Balance AED 4,500.00 Your October statement payment due date is 15/11/26", "85.00")

    @Test fun fabPurchaseWithInstalmentAdvertFooter() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 AED 85.00 ZORBO QUILLEX 12/11/26 14:05 Your August statement payment due date is 15/09/26 Pay school fees in 6 instalments at 0% interest with no fee. zorbo.example/pay Conditions apply.", "85.00")

    @Test fun fabSgdPurchaseWithInstalmentAdvertFooter() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 SGD 40.00 ZORBO QUILLEX 12/11/26 14:05 Your August statement payment due date is 15/09/26 Pay school fees in 6 instalments at 0% interest with no fee. zorbo.example/pay Conditions apply.", "40.00")

    @Test fun fabForeignPurchaseWithFlightsInstalmentAdvert() =
        purchase("FAB", "Credit Card Purchase Card No XXXX1234 USD 25.00 ZORBO QUILLEX 12/11/26 14:05 Available Balance AED 4,500.00 Enjoy 0% instalment plans on flights and stay with no processing fee. zorbo.example T&C apply.", "25.00")
}
