package com.junaid.cardtracker.core

import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.TxnType

/**
 * The single place that decides what counts as spending. Every total and (future) chart uses this.
 *  - PURCHASE adds, REFUND (incl. cashback) subtracts.
 *  - PAYMENT (paying off a credit card) never counts.
 *  - TRANSFER_OUT / TRANSFER_IN (bank account money moving, incl. paying your cards) never count.
 *  - Transactions on cards with "Count in spending" OFF don't count (they still show in lists).
 *  - Manual entries with no card always count.
 */
object Spending {
    /** Credit cards ON; debit cards and bank accounts OFF. */
    fun defaultCountInSpending(type: CardType): Boolean = type == CardType.CREDIT

    /** Contribution of one transaction to spending, in AED fils. */
    fun contributionAedMinor(type: TxnType, amountAedMinor: Long?, cardCounted: Boolean): Long {
        if (!cardCounted || amountAedMinor == null) return 0L
        return when (type) {
            TxnType.PURCHASE -> amountAedMinor
            TxnType.REFUND -> -amountAedMinor
            TxnType.PAYMENT, TxnType.TRANSFER_OUT, TxnType.TRANSFER_IN -> 0L
        }
    }

    data class Item(val type: TxnType, val amountAedMinor: Long?, val cardKey: String?)

    /** [excludedCardKeys] = cards whose "Count in spending" toggle is OFF. */
    fun totalAedMinor(items: List<Item>, excludedCardKeys: Set<String>): Long =
        items.sumOf { contributionAedMinor(it.type, it.amountAedMinor, it.cardKey == null || it.cardKey !in excludedCardKeys) }
}
