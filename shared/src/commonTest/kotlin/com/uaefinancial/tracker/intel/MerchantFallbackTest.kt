package com.uaefinancial.tracker.intel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MerchantFallbackTest {
    @Test fun cutsAtSentenceEndAndCardDigits() {
        assertEquals("DEWA", MessageIntel.cleanSpan("Paid to DEWA. Ref 123", "DEWA. Ref"))
        assertEquals("ENBD ANNUM", MessageIntel.cleanSpan("Card ENBD ANNUM XXXX7701 used", "ENBD ANNUM XXXX7701"))
    }

    @Test fun dropsCityAndCountryTails() {
        assertEquals("IIA STORE", MessageIntel.cleanSpan("at IIA STORE,NEW YORK-US on", "IIA STORE,NEW YORK-US"))
    }

    @Test fun neverReturnsAnAmountOrWordsNotInTheMessage() {
        assertNull(MessageIntel.cleanSpan("for AED 25.00 today", "AED 25.00"))
        assertNull(MessageIntel.cleanSpan("at SHOP ONE today", "SHOP TWO"))
    }

    @Test fun findsTheInvestBankMerchantFromWordsAlone() {
        val body = "You have made a Purchase transaction on your Credit Card ending 1234 from AL AHLI HOSPITAL for AED 25.00.  your available balance is AED 5,000.00 .Thank you for using Invest Bank Credit Card."
        assertEquals("AL AHLI HOSPITAL", MessageIntel.merchantFallback(body))
    }

    @Test fun staysSilentOnAMessageWithoutAMerchant() {
        assertNull(MessageIntel.merchantFallback("Your OTP is 123456. Do not share it."))
    }
}
