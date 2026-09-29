package com.uaefinancial.tracker.parser

import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.DateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Dates as written around the world, month-first regions, and the phone's time zone. */
class GlobalDatesTest {

    @AfterTest fun reset() {
        SmsParser.setMonthFirstDates(false)
        SmsParser.setZoneMinutes(SmsParser.UAE_ZONE)
        SmsParser.setHomeCurrency("AED")
        SmsParser.setCustomSenders(emptyMap())
    }

    private fun date(raw: String): CalendarDate? = SmsParser.parseDateTime(raw)?.first?.toLocalDate()

    @Test fun numeric_dates_in_every_order() {
        assertEquals(CalendarDate.of(2026, 9, 28), date("28/09/2026"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("28.09.2026"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("2026-09-28"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("28-09-26"))
        // Unambiguous either way.
        assertEquals(CalendarDate.of(2026, 9, 28), date("09/28/2026"))
        // Ambiguous: day first by default (UAE, UK, Europe, India), month first where that's the norm (US).
        assertEquals(CalendarDate.of(2026, 3, 4), date("04/03/2026"))
        SmsParser.setMonthFirstDates(true)
        assertEquals(CalendarDate.of(2026, 4, 3), date("04/03/2026"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("28/09/2026"))
    }

    @Test fun month_names_in_other_languages() {
        assertEquals(CalendarDate.of(2026, 9, 28), date("28. September 2026"))
        assertEquals(CalendarDate.of(2026, 8, 28), date("28 août 2026"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("28 de septiembre de 2026"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("28 set 2026"))
        assertEquals(CalendarDate.of(2026, 10, 3), date("3 okt 2026"))
        assertEquals(CalendarDate.of(2026, 3, 1), date("1 mrt 2026"))
        assertEquals(CalendarDate.of(2026, 12, 24), date("24 dic 2026"))
        assertEquals(CalendarDate.of(2026, 9, 28), date("Sept. 28, 2026"))
        // English still works.
        assertEquals(CalendarDate.of(2026, 7, 7), date("Tuesday, 7 July 2026, 3:16 pm"))
    }

    @Test fun invalid_dates_are_rejected_not_crashing() {
        assertEquals(null, date("31/02/2026"))
        assertEquals(null, date("45/45/2026"))
    }

    @Test fun month_first_regions() {
        assertTrue(SmsParser.isMonthFirstRegion("US"))
        assertTrue(SmsParser.isMonthFirstRegion("ph"))
        for (r in listOf("AE", "GB", "IN", "PK", "SA", "DE", "FR")) assertFalse(SmsParser.isMonthFirstRegion(r), r)
    }

    @Test fun times_are_read_in_the_phones_time_zone() {
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        val body = "AED 45.00 spent on your credit card ending 1234 at CARREFOUR on 28/09/2026 10:00"
        val received = DateTime.of(2026, 9, 28, 12, 0).toEpochMillis(0)
        // UAE (UTC+4): 10:00 local = 06:00 UTC.
        SmsParser.setZoneMinutes(240)
        val uae = SmsParser.parse("MYBANK", body, received)
        assertIs<ParseResult.Transaction>(uae)
        assertEquals(DateTime.of(2026, 9, 28, 6, 0).toEpochMillis(0), uae.txn.timestamp)
        // London in summer (UTC+1): 10:00 local = 09:00 UTC.
        SmsParser.setZoneMinutes(60)
        val london = SmsParser.parse("MYBANK", body, received)
        assertIs<ParseResult.Transaction>(london)
        assertEquals(DateTime.of(2026, 9, 28, 9, 0).toEpochMillis(0), london.txn.timestamp)
    }

    @Test fun amounts_are_never_read_as_dates() {
        SmsParser.setCustomSenders(mapOf("MYBANK" to "My Bank"))
        SmsParser.setHomeCurrency("IDR")
        val received = DateTime.of(2026, 9, 28, 12, 0).toEpochMillis(SmsParser.UAE_ZONE)
        val r = SmsParser.parse("MYBANK", "Purchase of IDR 1.500.000 with credit card ending 4455 at TOKOPEDIA", received, SmsParser.UAE_ZONE)
        assertIs<ParseResult.Transaction>(r)
        assertEquals(received, r.txn.timestamp, "no date in the message: the time it arrived")
    }
}
