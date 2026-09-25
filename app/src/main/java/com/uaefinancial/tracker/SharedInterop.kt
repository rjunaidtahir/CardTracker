package com.uaefinancial.tracker

import com.uaefinancial.tracker.core.CalendarDate
import com.uaefinancial.tracker.core.Decimal

/*
 * Bridges between the shared engine's plain-Kotlin types (they also run on iPhone) and the java.time / BigDecimal
 * types the Android screens use.
 */

fun CalendarDate.toJava(): java.time.LocalDate = java.time.LocalDate.ofEpochDay(toEpochDay())

fun java.time.LocalDate.toShared(): CalendarDate = CalendarDate.ofEpochDay(toEpochDay())

/** For java.text formatters, which only accept java.lang.Number. */
fun Decimal.toJava(): java.math.BigDecimal = java.math.BigDecimal(toPlainString())
