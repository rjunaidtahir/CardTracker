package com.uaefinancial.tracker.core

import com.uaefinancial.tracker.parser.TxnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MerchantReportTest {
    @Test fun shopNamesAreTidiedAndSent() {
        assertEquals("CIRCLE K MINI STORE BR", MerchantReport.clean("  Circle K  Mini Store BR ", TxnType.PURCHASE))
        assertEquals("EPPCO-SITE NO -91", MerchantReport.clean("EPPCO-SITE NO -91", TxnType.REFUND))
        assertEquals("DIGITAL DUBAI", MerchantReport.clean("Digital Dubai", TxnType.PURCHASE))
    }

    @Test fun transfersAndGenericTextAreNeverSent() {
        assertNull(MerchantReport.clean("JOHN SMITH", TxnType.TRANSFER_OUT))
        assertNull(MerchantReport.clean("JOHN SMITH", TxnType.TRANSFER_IN))
        assertNull(MerchantReport.clean("Account debit", TxnType.PURCHASE))
        assertNull(MerchantReport.clean("Card purchase", TxnType.PURCHASE))
        assertNull(MerchantReport.clean("Cashback", TxnType.REFUND))
        assertNull(MerchantReport.clean("FAB Rewards", TxnType.REFUND))
    }

    @Test fun contactDetailsAndDigitRunsAreDropped() {
        assertNull(MerchantReport.clean("PAY john@example.com", TxnType.PURCHASE))
        assertNull(MerchantReport.clean("WWW.SOMESHOP.COM", TxnType.PURCHASE))
        assertNull(MerchantReport.clean("SHOP 0501234567", TxnType.PURCHASE))
        assertNull(MerchantReport.clean("AB", TxnType.PURCHASE))
        assertNull(MerchantReport.clean("12 34", TxnType.PURCHASE))
    }
}
