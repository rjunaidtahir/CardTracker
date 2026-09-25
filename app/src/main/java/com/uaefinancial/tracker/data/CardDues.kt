package com.uaefinancial.tracker.data

import com.uaefinancial.tracker.core.CardPayment
import com.uaefinancial.tracker.core.CardPayments
import com.uaefinancial.tracker.core.StatementStatus
import com.uaefinancial.tracker.core.StatementStatusCalc
import com.uaefinancial.tracker.parser.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The latest statement of a card with its paid / due status. */
data class CardDue(
    val card: CardEntity,
    val statement: StatementEntity,
    val status: StatementStatus,
    val payments: List<CardPayment>,
) {
    val label: String get() = card.nickname ?: card.cardKey
}

class CardDues(private val dao: AppDao) {
    suspend fun currentDues(today: LocalDate = LocalDate.now()): List<CardDue> {
        val since = System.currentTimeMillis() - 150L * 86_400_000L
        return compute(dao.allCards(), dao.allStatements(), dao.txnsSince(since), today)
    }

    companion object {
        private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        /** Payments reaching each card: its bank's "payment received" SMS + transfers to it from your accounts. */
        fun paymentsByCard(txns: List<TransactionEntity>, zone: ZoneId = ZoneId.systemDefault()): Map<String, List<CardPayment>> {
            val bank = txns.filter { it.type == TxnType.PAYMENT.name && it.cardKey != null }
                .groupBy { it.cardKey!! }
                .mapValues { (_, l) -> l.map { CardPayment(day(it.timestamp, zone), it.amountMinor, true) } }
            val transfers = txns.filter { it.counterpartyKey != null }
                .groupBy { it.counterpartyKey!! }
                .mapValues { (_, l) -> l.map { CardPayment(day(it.timestamp, zone), it.amountMinor, false) } }
            return (bank.keys + transfers.keys).associateWith { k ->
                CardPayments.combine(bank[k].orEmpty(), transfers[k].orEmpty())
            }
        }

        fun compute(
            cards: List<CardEntity>,
            statements: List<StatementEntity>,
            txns: List<TransactionEntity>,
            today: LocalDate,
            zone: ZoneId = ZoneId.systemDefault(),
        ): List<CardDue> {
            val payments = paymentsByCard(txns, zone)
            val latest = statements.groupBy { it.cardKey }
                .mapValues { (_, l) -> l.maxWith(compareBy<StatementEntity>({ it.dueDateEpochDay }, { it.receivedAt })) }
            return cards.filter { !it.archived }.mapNotNull { c ->
                val s = latest[c.cardKey] ?: return@mapNotNull null
                val p = payments[c.cardKey].orEmpty()
                val status = StatementStatusCalc.of(
                    balanceMinor = s.balanceMinor,
                    minimumDueMinor = s.minimumDueMinor,
                    dueDate = LocalDate.ofEpochDay(s.dueDateEpochDay),
                    statementDate = s.statementDateEpochDay?.let { LocalDate.ofEpochDay(it) },
                    receivedDate = day(s.receivedAt, zone),
                    payments = p,
                    today = today,
                )
                CardDue(c, s, status, p)
            }.sortedBy { it.status.daysLeft }
        }
    }
}
