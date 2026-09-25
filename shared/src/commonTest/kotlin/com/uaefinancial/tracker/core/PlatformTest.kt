package com.uaefinancial.tracker.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The small date / decimal / SHA-256 replacements must match java.time, BigDecimal and MessageDigest exactly. */
class PlatformTest {
    @Test fun sha256_known_answers() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Sha256.hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test fun calendar_dates() {
        assertEquals(0L, CalendarDate.of(1970, 1, 1).toEpochDay())
        assertEquals(20721L, CalendarDate.of(2026, 9, 25).toEpochDay())
        assertEquals(CalendarDate.of(2028, 2, 29), CalendarDate.ofEpochDay(CalendarDate.of(2028, 2, 28).toEpochDay() + 1))
        assertEquals(CalendarDate.of(2027, 1, 1), CalendarDate.of(2026, 12, 31).plusDays(1))
        assertEquals(-1L, CalendarDate.daysBetween(CalendarDate.of(2026, 3, 1), CalendarDate.of(2026, 2, 28)))
        assertFailsWith<IllegalArgumentException> { CalendarDate.of(2026, 8, 32) }
        assertFailsWith<IllegalArgumentException> { CalendarDate.of(2026, 2, 29) }
        assertEquals("2026-09-05", CalendarDate.parse("2026-09-05").toString())
        for (d in -100_000L..100_000L step 97) assertEquals(d, CalendarDate.ofEpochDay(d).toEpochDay())
    }

    @Test fun uae_time() {
        // 13 Sep 2026 11:58:47 in Dubai = 07:58:47 UTC
        assertEquals(1789286327000L, DateTime.of(2026, 9, 13, 11, 58, 47).toEpochMillis())
        assertEquals(CalendarDate.of(2026, 9, 14), CalendarDate.fromEpochMillis(DateTime.of(2026, 9, 13, 20, 30).toEpochMillis(0) + 0))
    }

    @Test fun decimals() {
        assertEquals(500L, Decimal("5.00").toMinor())
        assertEquals(7L, Decimal(".07").toMinor())
        assertEquals(-6138L, Decimal("-61.38").toMinor())
        assertEquals(1235L, Decimal("12.345").toMinor(), "half up")
        assertEquals(453_392L, (Decimal("1234.56") * Decimal("3.6725")).toMinor()) // 4,533.9216
        assertTrue(Decimal("5.00") != Decimal("5"))
        assertEquals(0, Decimal("5.00").compareTo(Decimal("5")))
        assertEquals("12.5", Decimal("12.50").stripTrailingZeros().toPlainString())
        assertEquals("0.07", Decimal.valueOf(7, 2).toPlainString())
        assertEquals("-0.05", Decimal.valueOf(-5, 2).toPlainString())
        assertFailsWith<IllegalArgumentException> { Decimal("1,234") }
    }
}
