package com.junaid.cardtracker.data

import androidx.room.withTransaction
import com.junaid.cardtracker.core.Csv
import com.junaid.cardtracker.parser.Money
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup = one .zip of CSV files. Restoring imports the raw SMS, your typed entries and all your
 * settings (cards, categories, learned rules, per-transaction choices, goals, rates), then
 * re-parses the SMS to rebuild everything else. all_transactions.csv is for Excel only.
 */
class Backup(private val db: AppDatabase, private val repo: Repository) {
    private val dao = db.dao()

    data class Result(val sms: Int, val manual: Int, val cards: Int)

    suspend fun export(out: OutputStream) {
        val cats = dao.allCategories().associate { it.id to it.name }
        val files = linkedMapOf(
            "sms.csv" to Csv.write(
                listOf("dedupKey", "sender", "body", "bodyHash", "receivedAt", "sentAt", "source", "bank", "status", "ruleId", "note"),
                dao.allSms().map { listOf(it.dedupKey, it.sender, it.body, it.bodyHash, it.receivedAt.toString(), it.sentAt?.toString(), it.source, it.bank, it.status, it.ruleId, it.note) },
            ),
            "manual_transactions.csv" to Csv.write(
                listOf("timestamp", "bank", "cardLast4", "cardKey", "merchant", "amountMinor", "currency", "amountAedMinor", "type", "categoryId", "note"),
                dao.manualTxns().map {
                    listOf(it.timestamp.toString(), it.bank, it.cardLast4, it.cardKey, it.merchant, it.amountMinor.toString(), it.currency,
                        it.amountAedMinor?.toString(), it.type, it.categoryId?.toString(), it.note)
                },
            ),
            "cards.csv" to Csv.write(
                listOf("cardKey", "bank", "last4", "cardType", "countInSpending", "nickname", "creditLimitMinor", "statementDay", "dueDay", "remindersEnabled", "archived", "createdAt"),
                dao.allCards().map {
                    listOf(it.cardKey, it.bank, it.last4, it.cardType, it.countInSpending.toString(), it.nickname, it.creditLimitMinor?.toString(),
                        it.statementDay?.toString(), it.dueDay?.toString(), it.remindersEnabled.toString(), it.archived.toString(), it.createdAt.toString())
                },
            ),
            "categories.csv" to Csv.write(
                listOf("id", "name", "sortOrder", "archived"),
                dao.allCategories().map { listOf(it.id.toString(), it.name, it.sortOrder.toString(), it.archived.toString()) },
            ),
            "merchant_rules.csv" to Csv.write(listOf("merchantKey", "categoryId"), dao.allRules().map { listOf(it.merchantKey, it.categoryId.toString()) }),
            "txn_overrides.csv" to Csv.write(listOf("dedupKey", "categoryId"), dao.allOverrides().map { listOf(it.dedupKey, it.categoryId?.toString()) }),
            "savings_goals.csv" to Csv.write(
                listOf("name", "targetMinor", "savedMinor", "targetDateEpochDay", "createdAt"),
                dao.allGoals().map { listOf(it.name, it.targetMinor.toString(), it.savedMinor.toString(), it.targetDateEpochDay?.toString(), it.createdAt.toString()) },
            ),
            "fx_rates.csv" to Csv.write(listOf("currency", "rateToAed", "updatedAt"), dao.allRates().map { listOf(it.currency, it.rateToAed, it.updatedAt.toString()) }),
            "all_transactions.csv" to Csv.write(
                listOf("date", "bank", "card", "merchant", "category", "type", "amount", "currency", "amount_aed"),
                dao.allTxns().map {
                    listOf(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDateTime()),
                        it.bank, it.cardKey, it.merchant, it.categoryId?.let { c -> cats[c] }, it.type,
                        Money.fromMinor(it.amountMinor).toPlainString(), it.currency, it.amountAedMinor?.let { a -> Money.fromMinor(a).toPlainString() },
                    )
                },
            ),
        )
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    suspend fun import(input: InputStream): Result {
        val files = mutableMapOf<String, List<Map<String, String?>>>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                files[e.name] = Csv.read(zip.readBytes().toString(Charsets.UTF_8))
                e = zip.nextEntry
            }
        }
        require(files.containsKey("sms.csv")) { "Not a Card Tracker backup (sms.csv missing)" }
        fun Map<String, String?>.s(k: String) = this[k]
        fun Map<String, String?>.l(k: String) = this[k]?.toLongOrNull()
        fun Map<String, String?>.b(k: String) = this[k]?.toBoolean() ?: false

        var sms = 0
        var manual = 0
        var cards = 0
        db.withTransaction {
            files["categories.csv"]?.mapNotNull { r ->
                val id = r.l("id") ?: return@mapNotNull null
                CategoryEntity(id, r.s("name") ?: return@mapNotNull null, r.l("sortOrder")?.toInt() ?: id.toInt(), r.b("archived"))
            }?.let { dao.upsertCategories(it) }
            files["cards.csv"]?.mapNotNull { r ->
                CardEntity(
                    cardKey = r.s("cardKey") ?: return@mapNotNull null,
                    bank = r.s("bank") ?: "",
                    last4 = r.s("last4"),
                    cardType = r.s("cardType") ?: CardTypes.CREDIT,
                    countInSpending = r.b("countInSpending"),
                    nickname = r.s("nickname"),
                    creditLimitMinor = r.l("creditLimitMinor"),
                    statementDay = r.l("statementDay")?.toInt(),
                    dueDay = r.l("dueDay")?.toInt(),
                    remindersEnabled = r["remindersEnabled"]?.toBoolean() ?: true,
                    archived = r.b("archived"),
                    createdAt = r.l("createdAt") ?: System.currentTimeMillis(),
                )
            }?.let { dao.upsertCards(it); cards = it.size }
            files["merchant_rules.csv"]?.mapNotNull { r ->
                MerchantRuleEntity(r.s("merchantKey") ?: return@mapNotNull null, r.l("categoryId") ?: return@mapNotNull null)
            }?.let { dao.upsertRules(it) }
            files["txn_overrides.csv"]?.mapNotNull { r ->
                TxnOverrideEntity(r.s("dedupKey") ?: return@mapNotNull null, r.l("categoryId"))
            }?.let { dao.upsertOverrides(it) }
            files["fx_rates.csv"]?.mapNotNull { r ->
                FxRateEntity(r.s("currency") ?: return@mapNotNull null, r.s("rateToAed") ?: return@mapNotNull null, r.l("updatedAt") ?: 0L)
            }?.let { dao.upsertRates(it) }
            val existingGoals = dao.allGoals().map { it.name to it.targetMinor }.toSet()
            files["savings_goals.csv"]?.forEach { r ->
                val name = r.s("name") ?: return@forEach
                val target = r.l("targetMinor") ?: return@forEach
                if ((name to target) !in existingGoals) {
                    dao.upsertGoal(GoalEntity(name = name, targetMinor = target, savedMinor = r.l("savedMinor") ?: 0, targetDateEpochDay = r.l("targetDateEpochDay"), createdAt = r.l("createdAt") ?: System.currentTimeMillis()))
                }
            }
            files["sms.csv"]?.forEach { r ->
                val id = dao.insertSms(
                    SmsEntity(
                        dedupKey = r.s("dedupKey") ?: return@forEach,
                        sender = r.s("sender") ?: "",
                        body = r.s("body") ?: "",
                        bodyHash = r.s("bodyHash") ?: "",
                        receivedAt = r.l("receivedAt") ?: 0L,
                        sentAt = r.l("sentAt"),
                        source = r.s("source") ?: SmsSource.SYNC,
                        bank = r.s("bank"),
                        status = r.s("status") ?: SmsStatus.PENDING,
                        ruleId = r.s("ruleId"),
                        note = r.s("note"),
                    ),
                )
                if (id != -1L) sms++
            }
            files["manual_transactions.csv"]?.forEach { r ->
                val ts = r.l("timestamp") ?: return@forEach
                val amount = r.l("amountMinor") ?: return@forEach
                val merchant = r.s("merchant") ?: return@forEach
                if (dao.countManual(ts, amount, merchant) == 0) {
                    dao.insertTxn(
                        TransactionEntity(
                            smsId = null, source = "MANUAL", timestamp = ts, bank = r.s("bank") ?: "Manual",
                            cardLast4 = r.s("cardLast4"), cardKey = r.s("cardKey"), merchant = merchant,
                            amountMinor = amount, currency = r.s("currency") ?: "AED", amountAedMinor = r.l("amountAedMinor"),
                            fxEstimated = (r.s("currency") ?: "AED") != "AED", type = r.s("type") ?: "PURCHASE",
                            categoryId = r.l("categoryId"), categoryUserSet = r.l("categoryId") != null, note = r.s("note"),
                            merchantKey = com.junaid.cardtracker.parser.CategoryRules.merchantKey(merchant),
                        ),
                    )
                    manual++
                }
            }
        }
        repo.ensureDefaults()
        repo.reparseAll()
        return Result(sms, manual, cards)
    }
}
