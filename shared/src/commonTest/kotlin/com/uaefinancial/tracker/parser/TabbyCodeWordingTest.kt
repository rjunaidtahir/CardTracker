package com.uaefinancial.tracker.parser

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TabbyCodeWordingTest {
    @Test fun purchaseApprovalCodesAreCodes() {
        assertTrue(SmsParser.isOtp("Do not tell anyone this code: 617967. ANTHROPIC. USD 21.00"))
        assertTrue(SmsParser.isOtp("Do not tell anyone this code: 891400. Talabat. AED 149.40"))
        assertTrue(SmsParser.isOtp("Never share this code 123456 with anyone"))
    }

    @Test fun realTransactionsAreNotCodes() {
        assertFalse(SmsParser.isOtp("You spent AED 9.00 at NOON. Your available Tabby Card limit is now AED 2,991.00"))
        assertFalse(SmsParser.isOtp("Your credit card ending 1234 was used for AED 45.00 at CARREFOUR on 03/05/2026. Do not share your PIN with anyone."))
        assertFalse(SmsParser.isOtp("Your Tabby Card total limit is now increased to AED 3,000.00"))
    }
}
