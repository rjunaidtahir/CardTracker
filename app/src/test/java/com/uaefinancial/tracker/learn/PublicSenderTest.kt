package com.uaefinancial.tracker.learn

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PublicSenderTest {
    @Test fun bank_ids_and_short_codes_are_public() {
        assertEquals("HBL", Learning.publicSender("hbl"))
        assertEquals("AX-HDFCBK-S", Learning.publicSender("AX-HDFCBK-S"))
        assertEquals("8258", Learning.publicSender("8258"))
        assertEquals("99000", Learning.publicSender(" 99000 "))
    }

    @Test fun phone_numbers_and_contact_names_never_leave_the_phone() {
        assertNull(Learning.publicSender("+971501234567"))
        assertNull(Learning.publicSender("0501234567"))
        assertNull(Learning.publicSender("97150123"))
        assertNull(Learning.publicSender("Mum"))
        assertNull(Learning.publicSender("Ahmed Khan"))
        assertNull(Learning.publicSender("12"))
    }
}
