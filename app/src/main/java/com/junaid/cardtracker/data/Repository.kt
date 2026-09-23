package com.junaid.cardtracker.data

import androidx.room.withTransaction
import com.junaid.cardtracker.core.IngestOutcome
import com.junaid.cardtracker.core.SmsKey
import com.junaid.cardtracker.core.Spending
import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.ManualEntry
import com.junaid.cardtracker.parser.Money
import com.junaid.cardtracker.parser.ParseResult
import com.junaid.cardtracker.parser.SmsParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared by Sync and live listening: same parser, same de-duplication. */
class Repository(private val db: AppDatabase) {
    val dao = db.dao()
    private val lock = Mutex()

    suspend fun ingestSms(sender: String, body: String, receivedAt: Long, sentAt: Long?, source: String): IngestOutcome =
        lock.withLock {
            val bank = SmsParser.bankFor(sender) ?: return IngestOutcome.NOT_BANK
            val parsed = SmsParser.parse(sender, body, receivedAt)
            if (parsed is ParseResult.Ignored && !parsed.store) return IngestOutcome.OTP_SKIPPED

            val hash = SmsKey.bodyHash(body)
            val sent = sentAt?.takeIf { it > 0 }
            // Safety net when one copy has no sent time (then the keys can't match exactly).
            val window = 10 * 60 * 1000L
            if (dao.countNearDuplicates(bank.name, hash, receivedAt - window, receivedAt + window, sent) > 0) {
                return IngestOutcome.DUPLICATE
            }
            db.withTransaction {
                val id = dao.insertSms(
                    SmsEntity(
                        dedupKey = SmsKey.of(sender, sent, receivedAt, body),
                        sender = sender, body = body, bodyHash = hash,
                        receivedAt = receivedAt, sentAt = sent, source = source,
                        bank = bank.name, status = SmsStatus.PENDING,
                    ),
                )
                if (id == -1L) IngestOutcome.DUPLICATE else applyParse(id, parsed, receivedAt)
            }
        }

    /** Re-runs the current BankRules over every stored SMS (except dismissed ones). */
    suspend fun reparseAll(): Map<IngestOutcome, Int> = lock.withLock {
        val all = dao.smsForReparse()
        val counts = mutableMapOf<IngestOutcome, Int>()
        db.withTransaction {
            for (s in all) {
                val o = applyParse(s.id, SmsParser.parse(s.sender, s.body, s.receivedAt), s.receivedAt)
                counts[o] = (counts[o] ?: 0) + 1
            }
        }
        counts
    }

    private fun CardType.dbName() = if (this == CardType.DEBIT) CardTypes.DEBIT else CardTypes.CREDIT

    private suspend fun ensureCard(key: String, bank: String, last4: String?, type: CardType) {
        dao.insertCard(CardEntity(key, bank, last4, type.dbName(), Spending.defaultCountInSpending(type)))
    }

    private suspend fun applyParse(smsId: Long, r: ParseResult, receivedAt: Long): IngestOutcome {
        dao.deleteTxnsForSms(smsId)
        dao.deleteStatementsForSms(smsId)
        return when (r) {
            is ParseResult.Transaction -> {
                val t = r.txn
                val key = cardKeyOf(t.bank, t.cardLast4)
                ensureCard(key, t.bank, t.cardLast4, t.cardType)
                val aed = Money.toAedMinor(t.amount, t.currency)
                dao.insertTxn(
                    TransactionEntity(
                        smsId = smsId, source = "SMS", timestamp = t.timestamp, bank = t.bank,
                        cardLast4 = t.cardLast4, cardKey = key, merchant = t.merchant,
                        amountMinor = Money.toMinor(t.amount), currency = t.currency,
                        amountAedMinor = aed?.first, fxEstimated = aed?.second ?: false,
                        type = t.type.name, availableLimitMinor = t.availableLimit?.let { Money.toMinor(it) },
                        ruleId = r.ruleId,
                    ),
                )
                val note = if (aed == null) "No AED rate for ${t.currency}" else null
                dao.setSmsResult(smsId, SmsStatus.TRANSACTION, t.bank, r.ruleId, note)
                IngestOutcome.TRANSACTION
            }
            is ParseResult.Statement -> {
                val s = r.statement
                val key = cardKeyOf(s.bank, s.cardLast4)
                ensureCard(key, s.bank, s.cardLast4, s.cardType)
                dao.insertStatement(
                    StatementEntity(
                        smsId = smsId, receivedAt = receivedAt, bank = s.bank, cardLast4 = s.cardLast4, cardKey = key,
                        balanceMinor = Money.toMinor(s.statementBalance),
                        minimumDueMinor = s.minimumDue?.let { Money.toMinor(it) },
                        currency = s.currency, dueDateEpochDay = s.dueDate.toEpochDay(),
                        statementDateEpochDay = s.statementDate?.toEpochDay(),
                    ),
                )
                dao.setSmsResult(smsId, SmsStatus.STATEMENT, s.bank, r.ruleId, null)
                IngestOutcome.STATEMENT
            }
            is ParseResult.Ignored -> {
                if (!r.store) {
                    dao.deleteSms(smsId) // e.g. an old SMS that a newer rule now recognises as OTP
                    IngestOutcome.OTP_SKIPPED
                } else {
                    dao.setSmsResult(smsId, SmsStatus.IGNORED, r.bank, null, r.reason)
                    IngestOutcome.IGNORED
                }
            }
            is ParseResult.Failed -> {
                dao.setSmsResult(smsId, SmsStatus.FAILED, r.bank, null, r.reason)
                IngestOutcome.FAILED
            }
            ParseResult.NotBank -> {
                dao.setSmsResult(smsId, SmsStatus.IGNORED, null, null, "Sender no longer in BankRules")
                IngestOutcome.NOT_BANK
            }
        }
    }

    suspend fun addManual(entry: ManualEntry, timestamp: Long = System.currentTimeMillis()) {
        // Attach to a known card if the last 4 digits match exactly one card.
        val card = entry.cardLast4?.let { dao.cardsByLast4(it).singleOrNull() }
        val aed = Money.toAedMinor(entry.amount, entry.currency)
        dao.insertTxn(
            TransactionEntity(
                smsId = null, source = "MANUAL", timestamp = timestamp,
                bank = card?.bank ?: "Manual", cardLast4 = card?.last4 ?: entry.cardLast4,
                cardKey = card?.cardKey, merchant = entry.description,
                amountMinor = Money.toMinor(entry.amount), currency = entry.currency,
                amountAedMinor = aed?.first, fxEstimated = aed?.second ?: false, type = entry.type.name,
            ),
        )
    }

    /** Deleting an SMS transaction also dismisses its SMS, so Re-parse doesn't bring it back. */
    suspend fun deleteTransaction(t: TransactionEntity) = db.withTransaction {
        dao.deleteTxn(t.id)
        t.smsId?.let { dao.setSmsStatus(it, SmsStatus.DISMISSED) }
    }

    suspend fun setCardType(key: String, type: CardType) =
        dao.setCardType(key, type.dbName(), Spending.defaultCountInSpending(type))
}
