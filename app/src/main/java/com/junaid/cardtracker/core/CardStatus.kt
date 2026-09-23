package com.junaid.cardtracker.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** A payment reaching a card: from the card's bank ("payment received") or a transfer from your account. */
data class CardPayment(val date: LocalDate, val amountMinor: Long, val fromBankSms: Boolean)

object CardPayments {
    /**
     * Combines both sources without double counting: a transfer that the card's bank also
     * reported (same amount, within 3 days) is counted once.
     */
    fun combine(bankPayments: List<CardPayment>, transfers: List<CardPayment>): List<CardPayment> {
        val kept = transfers.filterNot { t ->
            bankPayments.any { p -> p.amountMinor == t.amountMinor && abs(ChronoUnit.DAYS.between(p.date, t.date)) <= 3 }
        }
        return (bankPayments + kept).sortedBy { it.date }
    }
}

enum class DueState {
    /** Paid the full statement balance. */
    PAID,
    /** Paid at least the minimum, not the full balance. */
    MIN_PAID,
    UNPAID,
    /** Due date passed without paying the minimum. */
    OVERDUE,
    /** Statement balance was zero. */
    NOTHING_DUE,
}

data class StatementStatus(
    val state: DueState,
    val paidMinor: Long,
    /** Statement balance still to pay (0 when paid in full). */
    val remainingMinor: Long,
    /** Days until the due date (negative = passed). */
    val daysLeft: Long,
)

object StatementStatusCalc {
    /**
     * Payments count toward a statement from its statement date onward (or 5 days before the SMS
     * arrived if the SMS has no statement date).
     */
    fun of(
        balanceMinor: Long,
        minimumDueMinor: Long?,
        dueDate: LocalDate,
        statementDate: LocalDate?,
        receivedDate: LocalDate,
        payments: List<CardPayment>,
        today: LocalDate,
    ): StatementStatus {
        val from = statementDate ?: receivedDate.minusDays(5)
        val paid = payments.filter { !it.date.isBefore(from) }.sumOf { it.amountMinor }
        val daysLeft = ChronoUnit.DAYS.between(today, dueDate)
        val remaining = (balanceMinor - paid).coerceAtLeast(0)
        val min = minimumDueMinor ?: balanceMinor
        val state = when {
            balanceMinor <= 0 -> DueState.NOTHING_DUE
            paid >= balanceMinor -> DueState.PAID
            paid >= min -> DueState.MIN_PAID
            daysLeft < 0 -> DueState.OVERDUE
            else -> DueState.UNPAID
        }
        return StatementStatus(state, paid, remaining, daysLeft)
    }
}

/** Available limit, utilisation and outstanding for a credit card. */
data class Utilisation(val limitMinor: Long, val availableMinor: Long) {
    val usedMinor: Long get() = (limitMinor - availableMinor).coerceAtLeast(0)
    /** 0..100+ (can exceed 100 when over limit). */
    val percent: Int get() = if (limitMinor <= 0) 0 else ((usedMinor * 100 + limitMinor / 2) / limitMinor).toInt()
}

object Reminders {
    /** Days-before-due that trigger a reminder; 0 = on the due day. */
    val OFFSETS = listOf(3L, 1L, 0L)

    /**
     * The reminder that should fire today for a statement, or null.
     * Fires on the offset days while the minimum isn't paid; a full-balance reminder is not sent.
     */
    fun offsetToday(status: StatementStatus): Long? {
        if (status.state != DueState.UNPAID) return null
        return OFFSETS.firstOrNull { it == status.daysLeft }
    }

    fun message(cardLabel: String, status: StatementStatus, minimumDueMinor: Long?, fmt: (Long) -> String): String {
        val whenText = when (status.daysLeft) {
            0L -> "today"
            1L -> "tomorrow"
            else -> "in ${status.daysLeft} days"
        }
        val min = minimumDueMinor?.let { " · minimum ${fmt(it)}" } ?: ""
        return "$cardLabel payment due $whenText: ${fmt(status.remainingMinor)}$min"
    }
}
