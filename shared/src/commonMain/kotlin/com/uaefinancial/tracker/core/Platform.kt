package com.uaefinancial.tracker.core

/*
 * Small platform-neutral replacements for java.time, java.math.BigDecimal and java.security.MessageDigest, so the
 * message and statement engine is plain Kotlin and runs unchanged on Android and iPhone. Only what the engine needs.
 */

/** UAE time is UTC+4 all year (no daylight saving). */
const val UAE_OFFSET_MINUTES = 240

private const val DAYS_0000_TO_1970 = 719_528L
private const val DAYS_PER_CYCLE = 146_097L
private const val MS_PER_DAY = 86_400_000L

/** A calendar date without a time or zone, like java.time.LocalDate. */
class CalendarDate private constructor(val year: Int, val monthValue: Int, val dayOfMonth: Int) : Comparable<CalendarDate> {

    /** Days since 1970-01-01 (same numbering as java.time.LocalDate.toEpochDay). */
    fun toEpochDay(): Long {
        val y = year.toLong()
        val m = monthValue.toLong()
        var total = 365 * y
        total += if (y >= 0) (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400 else -(y / -4 - y / -100 + y / -400)
        total += (367 * m - 362) / 12
        total += dayOfMonth - 1
        if (m > 2) {
            total--
            if (!isLeap(year)) total--
        }
        return total - DAYS_0000_TO_1970
    }

    fun plusDays(days: Long): CalendarDate = if (days == 0L) this else ofEpochDay(toEpochDay() + days)
    fun minusDays(days: Long): CalendarDate = plusDays(-days)
    fun isAfter(other: CalendarDate) = compareTo(other) > 0
    fun isBefore(other: CalendarDate) = compareTo(other) < 0
    fun atTime(hour: Int, minute: Int, second: Int = 0) = DateTime.of(this, hour, minute, second)

    /** Midnight at the start of this day, in epoch millis, for a zone [offsetMinutes] ahead of UTC. */
    fun startMillis(offsetMinutes: Int = UAE_OFFSET_MINUTES): Long = toEpochDay() * MS_PER_DAY - offsetMinutes * 60_000L

    override fun compareTo(other: CalendarDate): Int =
        compareValuesBy(this, other, { it.year }, { it.monthValue }, { it.dayOfMonth })

    override fun equals(other: Any?) = other is CalendarDate && other.year == year && other.monthValue == monthValue && other.dayOfMonth == dayOfMonth
    override fun hashCode() = (year * 31 + monthValue) * 31 + dayOfMonth

    /** ISO format, e.g. 2026-09-25. */
    override fun toString(): String = "${year.toString().padStart(4, '0')}-${pad2(monthValue)}-${pad2(dayOfMonth)}"

    companion object {
        fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

        fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
            2 -> if (isLeap(year)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        /** Throws IllegalArgumentException for an impossible date (e.g. 32 August), like java.time does. */
        fun of(year: Int, month: Int, day: Int): CalendarDate {
            require(month in 1..12) { "Invalid month $month" }
            require(day in 1..lengthOfMonth(year, month)) { "Invalid day $day for $year-$month" }
            return CalendarDate(year, month, day)
        }

        fun ofEpochDay(epochDay: Long): CalendarDate {
            var zeroDay = epochDay + DAYS_0000_TO_1970 - 60
            var adjust = 0L
            if (zeroDay < 0) {
                val adjustCycles = (zeroDay + 1) / DAYS_PER_CYCLE - 1
                adjust = adjustCycles * 400
                zeroDay += -adjustCycles * DAYS_PER_CYCLE
            }
            var yearEst = (400 * zeroDay + 591) / DAYS_PER_CYCLE
            var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            if (doyEst < 0) {
                yearEst--
                doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
            }
            yearEst += adjust
            val marchMonth0 = (doyEst * 5 + 2) / 153
            val month = ((marchMonth0 + 2) % 12 + 1).toInt()
            val dom = (doyEst - (marchMonth0 * 306 + 5) / 10 + 1).toInt()
            yearEst += marchMonth0 / 10
            return CalendarDate(yearEst.toInt(), month, dom)
        }

        /** The date at [epochMillis] in a zone [offsetMinutes] ahead of UTC. */
        fun fromEpochMillis(epochMillis: Long, offsetMinutes: Int = UAE_OFFSET_MINUTES): CalendarDate =
            ofEpochDay(floorDiv(epochMillis + offsetMinutes * 60_000L, MS_PER_DAY))

        /** "2026-09-25" */
        fun parse(iso: String): CalendarDate {
            val p = iso.trim().split("-")
            require(p.size == 3) { "Not a date: $iso" }
            return of(p[0].toInt(), p[1].toInt(), p[2].toInt())
        }

        /** Whole days from [a] to [b] (negative if [b] is earlier), like ChronoUnit.DAYS.between. */
        fun daysBetween(a: CalendarDate, b: CalendarDate): Long = b.toEpochDay() - a.toEpochDay()

        private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a xor b) < 0)) q - 1 else q }
    }
}

/** A date with a time of day (no zone), like java.time.LocalDateTime. */
data class DateTime(val date: CalendarDate, val hour: Int, val minute: Int, val second: Int) {
    fun toLocalDate(): CalendarDate = date

    /** Epoch millis for this local time in a zone [offsetMinutes] ahead of UTC. */
    fun toEpochMillis(offsetMinutes: Int = UAE_OFFSET_MINUTES): Long =
        date.toEpochDay() * MS_PER_DAY + (hour * 3600L + minute * 60L + second) * 1000L - offsetMinutes * 60_000L

    override fun toString() = "$date ${pad2(hour)}:${pad2(minute)}:${pad2(second)}"

    companion object {
        fun of(date: CalendarDate, hour: Int, minute: Int, second: Int = 0): DateTime {
            require(hour in 0..23 && minute in 0..59 && second in 0..59) { "Invalid time $hour:$minute:$second" }
            return DateTime(date, hour, minute, second)
        }

        fun of(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0) = of(CalendarDate.of(year, month, day), hour, minute, second)
    }
}

private fun pad2(n: Int) = n.toString().padStart(2, '0')

/**
 * An exact decimal number (an amount or a rate), like java.math.BigDecimal: an unscaled whole number and a scale.
 * "5.00" is 500 with scale 2. Equality, like BigDecimal, also compares the scale ("5.00" != "5.0"); use compareTo
 * for the value alone.
 */
class Decimal private constructor(val unscaled: Long, val scale: Int) : Comparable<Decimal> {

    /** Parses "1234.56", "-61.38", ".07", "5". Throws IllegalArgumentException for anything else. */
    constructor(text: String) : this(parseUnscaled(text), parseScale(text))

    fun signum(): Int = unscaled.compareTo(0L)
    fun abs(): Decimal = if (unscaled < 0) Decimal(-unscaled, scale) else this
    operator fun unaryMinus(): Decimal = Decimal(-unscaled, scale)

    operator fun times(other: Decimal): Decimal = Decimal(multiplyExact(unscaled, other.unscaled), scale + other.scale)

    /** Moves the decimal point right, like BigDecimal.movePointRight: 12.34 -> 1234 for n = 2. */
    fun movePointRight(n: Int): Decimal = if (scale - n >= 0) Decimal(unscaled, scale - n) else Decimal(multiplyExact(unscaled, pow10(n - scale)), 0)

    /** Rounded (half up, away from zero) to [newScale] decimals. */
    fun setScale(newScale: Int): Decimal {
        if (newScale == scale) return this
        if (newScale > scale) return Decimal(multiplyExact(unscaled, pow10(newScale - scale)), newScale)
        val div = pow10(scale - newScale)
        val q = unscaled / div
        val r = unscaled % div
        val rounded = if (kotlin.math.abs(r) * 2 >= div) q + (if (unscaled < 0) -1 else 1) else q
        return Decimal(rounded, newScale)
    }

    /** The value in hundredths (fils / cents), rounded half up. Throws ArithmeticException if it doesn't fit. */
    fun toMinor(): Long = setScale(2).unscaled

    fun stripTrailingZeros(): Decimal {
        var u = unscaled
        var s = scale
        while (s > 0 && u % 10 == 0L) { u /= 10; s-- }
        return Decimal(u, s)
    }

    fun toPlainString(): String {
        if (scale == 0) return unscaled.toString()
        val neg = unscaled < 0
        val digits = kotlin.math.abs(unscaled).toString().padStart(scale + 1, '0')
        val cut = digits.length - scale
        return (if (neg) "-" else "") + digits.substring(0, cut) + "." + digits.substring(cut)
    }

    fun toDouble(): Double = toPlainString().toDouble()

    override fun compareTo(other: Decimal): Int {
        val s = maxOf(scale, other.scale)
        return setScale(s).unscaled.compareTo(other.setScale(s).unscaled)
    }

    override fun equals(other: Any?) = other is Decimal && other.unscaled == unscaled && other.scale == scale
    override fun hashCode() = unscaled.hashCode() * 31 + scale
    override fun toString() = toPlainString()

    companion object {
        val ZERO = Decimal(0, 0)
        val ONE = Decimal(1, 0)

        /** [unscaled] × 10^-[scale]: valueOf(1234, 2) is 12.34. */
        fun valueOf(unscaled: Long, scale: Int): Decimal = Decimal(unscaled, scale)

        fun parseOrNull(text: String): Decimal? = runCatching { Decimal(text) }.getOrNull()

        private val SHAPE = Regex("""^([+-])?(\d*)(?:\.(\d*))?$""")

        private fun parts(text: String): MatchResult {
            val m = SHAPE.matchEntire(text.trim()) ?: throw IllegalArgumentException("Not a number: $text")
            require(m.groupValues[2].isNotEmpty() || m.groupValues[3].isNotEmpty()) { "Not a number: $text" }
            require(m.groupValues[2].length + m.groupValues[3].length <= 18) { "Number too long: $text" }
            return m
        }

        private fun parseUnscaled(text: String): Long {
            val m = parts(text)
            val digits = (m.groupValues[2] + m.groupValues[3]).ifEmpty { "0" }
            val v = digits.toLong()
            return if (m.groupValues[1] == "-") -v else v
        }

        private fun parseScale(text: String): Int = parts(text).groupValues[3].length

        private fun pow10(n: Int): Long {
            var r = 1L
            repeat(n) { r = multiplyExact(r, 10) }
            return r
        }

        private fun multiplyExact(a: Long, b: Long): Long {
            val r = a * b
            if (a != 0L && (r / a != b || (a == -1L && b == Long.MIN_VALUE))) throw ArithmeticException("Number too large")
            return r
        }
    }
}

fun String.toDecimalOrNull(): Decimal? = Decimal.parseOrNull(this)

/** SHA-256 (FIPS 180-4), hex output. Same result as java.security.MessageDigest("SHA-256"). */
object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    fun hex(bytes: ByteArray): String {
        val h = intArrayOf(0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6, 0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19)
        val bitLen = bytes.size.toLong() * 8
        val padLen = ((bytes.size + 9 + 63) / 64) * 64
        val msg = ByteArray(padLen)
        bytes.copyInto(msg)
        msg[bytes.size] = 0x80.toByte()
        for (i in 0 until 8) msg[padLen - 1 - i] = (bitLen ushr (8 * i)).toByte()
        val w = IntArray(64)
        for (chunk in 0 until padLen / 64) {
            for (t in 0 until 16) {
                val o = chunk * 64 + t * 4
                w[t] = (msg[o].toInt() and 0xff shl 24) or (msg[o + 1].toInt() and 0xff shl 16) or (msg[o + 2].toInt() and 0xff shl 8) or (msg[o + 3].toInt() and 0xff)
            }
            for (t in 16 until 64) {
                val s0 = w[t - 15].rotateRight(7) xor w[t - 15].rotateRight(18) xor (w[t - 15] ushr 3)
                val s1 = w[t - 2].rotateRight(17) xor w[t - 2].rotateRight(19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (t in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (v in h) for (i in 7 downTo 0) sb.append(hexChars[(v ushr (i * 4)) and 0xf])
        return sb.toString()
    }

    fun hex(text: String): String = hex(text.encodeToByteArray())
}
