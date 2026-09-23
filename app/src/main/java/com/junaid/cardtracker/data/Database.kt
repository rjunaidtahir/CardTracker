package com.junaid.cardtracker.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/*
 * Schema v4: v3 + categories, learned merchant rules, per-SMS category overrides, savings goals,
 * editable exchange rates and transactions.merchantKey. Columns marked "Phase N" are unused for now but exist so later phases don't need
 * table rebuilds. New tables planned for later phases (see ROADMAP.md) get added with a Room
 * Migration in Migrations.kt: never by bumping the version with destructive fallback.
 */

object SmsStatus {
    const val PENDING = "PENDING"
    const val TRANSACTION = "TRANSACTION"
    const val STATEMENT = "STATEMENT"
    const val IGNORED = "IGNORED"
    const val FAILED = "FAILED"
    /** Failed SMS you chose to dismiss on the Review tab. Re-parse skips these. */
    const val DISMISSED = "DISMISSED"
}

object SmsSource {
    const val SYNC = "SYNC"
    const val LIVE = "LIVE"
}

/** Every bank SMS is kept raw (except OTPs, which are never stored). */
@Entity(
    tableName = "sms",
    indices = [Index(value = ["dedupKey"], unique = true), Index(value = ["bank", "bodyHash"]), Index(value = ["status"])],
)
data class SmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** sender | sent-time | body hash: see core/SmsKey.kt */
    val dedupKey: String,
    val sender: String,
    val body: String,
    /** SHA-256 hex of the normalized body */
    val bodyHash: String,
    /** When the phone received it (inbox DATE, or clock time for live capture). */
    val receivedAt: Long,
    /** Service-centre timestamp (inbox DATE_SENT / SmsMessage.timestampMillis). Null if unknown. */
    val sentAt: Long?,
    /** SYNC or LIVE */
    val source: String,
    val bank: String?,
    val status: String,
    val ruleId: String? = null,
    val note: String? = null,
)

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["smsId"]), Index(value = ["timestamp"]), Index(value = ["cardKey"]), Index(value = ["categoryId"]),
        Index(value = ["merchantKey"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Null for manual entries. */
    val smsId: Long?,
    /** "SMS" or "MANUAL" */
    val source: String,
    val timestamp: Long,
    val bank: String,
    val cardLast4: String?,
    val cardKey: String?,
    val merchant: String,
    /** Original amount in minor units of [currency]. */
    val amountMinor: Long,
    val currency: String,
    /** AED equivalent in fils. Null if the currency has no rate in BankRules.fxToAed. */
    val amountAedMinor: Long?,
    val fxEstimated: Boolean,
    /** PURCHASE / REFUND / PAYMENT */
    val type: String,
    val availableLimitMinor: Long? = null,
    val ruleId: String? = null,
    /** Phase 3: category (table added then) and whether you set it yourself (feeds the learning). */
    val categoryId: Long? = null,
    val categoryUserSet: Boolean = false,
    /** Phase 3: recurring payment group */
    val recurringGroupId: Long? = null,
    val note: String? = null,
    /** For transfers/card payments to one of your own cards: that card's key (shows as a payment on it). */
    val counterpartyKey: String? = null,
    /** A second SMS describing the same money movement, merged into this transaction. */
    val pairedSmsId: Long? = null,
    /** Normalised merchant (CategoryRules.merchantKey) used to learn categories. */
    val merchantKey: String? = null,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val sortOrder: Int,
    val archived: Boolean = false,
)

/** Learned when you re-categorise a merchant with "apply to all". */
@Entity(tableName = "merchant_rules")
data class MerchantRuleEntity(
    @PrimaryKey val merchantKey: String,
    val categoryId: Long,
)

/** Your category choice for one SMS transaction, keyed by the SMS dedupKey so it survives Re-parse and backups. */
@Entity(tableName = "txn_overrides")
data class TxnOverrideEntity(
    @PrimaryKey val dedupKey: String,
    val categoryId: Long?,
)

@Entity(tableName = "savings_goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetMinor: Long,
    val savedMinor: Long,
    val targetDateEpochDay: Long?,
    val createdAt: Long,
)

/** Editable AED rates (seeded from BankRules.fxToAed). Stored as text to keep BigDecimal precision. */
@Entity(tableName = "fx_rates")
data class FxRateEntity(
    @PrimaryKey val currency: String,
    val rateToAed: String,
    val updatedAt: Long,
)

@Entity(tableName = "statements", indices = [Index(value = ["smsId"]), Index(value = ["cardKey"])])
data class StatementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val smsId: Long,
    val receivedAt: Long,
    val bank: String,
    val cardLast4: String?,
    val cardKey: String,
    val balanceMinor: Long,
    val minimumDueMinor: Long?,
    val currency: String,
    val dueDateEpochDay: Long,
    val statementDateEpochDay: Long?,
    /** Phase 2: auto-marked paid from a payment SMS (or by hand). */
    val paidAt: Long? = null,
    val paidByTxnId: Long? = null,
)

object CardTypes {
    const val CREDIT = "CREDIT"
    const val DEBIT = "DEBIT"
    const val ACCOUNT = "ACCOUNT"
}

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val cardKey: String,
    val bank: String,
    val last4: String?,
    /** CREDIT, DEBIT or ACCOUNT (bank account) */
    val cardType: String,
    /** Default ON for credit, OFF for debit. Excluded cards stay in lists but not in totals/charts. */
    val countInSpending: Boolean,
    val nickname: String? = null,
    /** Phase 2 card profile */
    val creditLimitMinor: Long? = null,
    val statementDay: Int? = null,
    val dueDay: Int? = null,
    val remindersEnabled: Boolean = true,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

fun cardKeyOf(bank: String, last4: String?) = "$bank ·${last4 ?: "????"}"

@Dao
interface AppDao {
    // --- SMS
    /** Returns -1 when the dedupKey already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSms(sms: SmsEntity): Long

    /** Fallback de-dup, only relevant when one of the two copies has no sent time. */
    @Query(
        "SELECT COUNT(*) FROM sms WHERE bank = :bank AND bodyHash = :hash AND receivedAt BETWEEN :from AND :to " +
            "AND (:sentAt IS NULL OR sentAt IS NULL)",
    )
    suspend fun countNearDuplicates(bank: String, hash: String, from: Long, to: Long, sentAt: Long?): Int

    @Query("SELECT * FROM sms WHERE status != 'DISMISSED' ORDER BY receivedAt ASC")
    suspend fun smsForReparse(): List<SmsEntity>

    @Query("SELECT body FROM sms WHERE id = :id")
    suspend fun smsBody(id: Long): String?

    @Query("DELETE FROM sms WHERE id = :id")
    suspend fun deleteSms(id: Long)

    @Query("UPDATE sms SET status = :status, bank = :bank, ruleId = :ruleId, note = :note WHERE id = :id")
    suspend fun setSmsResult(id: Long, status: String, bank: String?, ruleId: String?, note: String?)

    @Query("UPDATE sms SET status = :status WHERE id = :id")
    suspend fun setSmsStatus(id: Long, status: String)

    @Query("SELECT * FROM sms WHERE status = 'FAILED' ORDER BY receivedAt DESC")
    fun failedSms(): Flow<List<SmsEntity>>

    @Query("SELECT * FROM sms WHERE status = 'FAILED' ORDER BY receivedAt DESC")
    suspend fun failedSmsList(): List<SmsEntity>

    @Query("SELECT status, COUNT(*) AS n FROM sms GROUP BY status")
    fun smsStatusCounts(): Flow<List<StatusCount>>

    // --- transactions
    @Insert
    suspend fun insertTxn(t: TransactionEntity): Long

    @Query("DELETE FROM transactions WHERE smsId = :smsId")
    suspend fun deleteTxnsForSms(smsId: Long)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTxn(id: Long)

    @Query("UPDATE transactions SET pairedSmsId = NULL WHERE pairedSmsId = :smsId")
    suspend fun unpairSms(smsId: Long)

    /** The other half of a two-SMS transfer: same account, same amount, other rule of the same pair group, ±window. */
    @Query(
        "SELECT * FROM transactions WHERE cardKey = :cardKey AND type IN ('TRANSFER_OUT', 'PURCHASE') AND amountMinor = :amountMinor " +
            "AND pairedSmsId IS NULL AND ruleId IN (:ruleIds) AND ruleId != :ruleId " +
            "AND timestamp BETWEEN :from AND :to ORDER BY ABS(timestamp - :ts) LIMIT 1",
    )
    suspend fun findTransferPair(
        cardKey: String, amountMinor: Long, ruleIds: List<String>, ruleId: String, from: Long, to: Long, ts: Long,
    ): TransactionEntity?

    @Query(
        "UPDATE transactions SET pairedSmsId = :smsId, merchant = :merchant, counterpartyKey = :counterpartyKey, " +
            "availableLimitMinor = :availableLimitMinor, timestamp = :timestamp, type = :type WHERE id = :id",
    )
    suspend fun mergePair(
        id: Long, smsId: Long, merchant: String, counterpartyKey: String?, availableLimitMinor: Long?, timestamp: Long, type: String,
    )

    @Query("DELETE FROM transactions WHERE smsId IS NOT NULL")
    suspend fun deleteAllSmsTxns()

    @Query("DELETE FROM statements")
    suspend fun deleteAllStatements()

    @Query(
        "SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to " +
            "AND (:cardKey IS NULL OR cardKey = :cardKey) ORDER BY timestamp DESC",
    )
    fun txns(from: Long, to: Long, cardKey: String?): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp >= :from AND timestamp < :to")
    fun txnsBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    // --- statements
    @Insert
    suspend fun insertStatement(s: StatementEntity): Long

    @Query("DELETE FROM statements WHERE smsId = :smsId")
    suspend fun deleteStatementsForSms(smsId: Long)

    @Query("SELECT * FROM statements ORDER BY receivedAt DESC")
    fun statements(): Flow<List<StatementEntity>>

    // --- cards
    /** IGNORE keeps any settings you've changed on an existing card. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCard(c: CardEntity)

    @Query("SELECT * FROM cards WHERE archived = 0 ORDER BY cardType, bank, last4")
    fun cards(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE last4 = :last4")
    suspend fun cardsByLast4(last4: String): List<CardEntity>

    @Query("UPDATE cards SET countInSpending = :counted WHERE cardKey = :key")
    suspend fun setCardCounted(key: String, counted: Boolean)

    @Query("UPDATE cards SET cardType = :type, countInSpending = :counted WHERE cardKey = :key")
    suspend fun setCardType(key: String, type: String, counted: Boolean)

    @Query(
        "UPDATE cards SET nickname = :nickname, creditLimitMinor = :limitMinor, statementDay = :statementDay, " +
            "dueDay = :dueDay, remindersEnabled = :reminders WHERE cardKey = :key",
    )
    suspend fun updateCardProfile(key: String, nickname: String?, limitMinor: Long?, statementDay: Int?, dueDay: Int?, reminders: Boolean)

    @Query("SELECT * FROM cards")
    suspend fun allCards(): List<CardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCards(c: List<CardEntity>)

    // --- categories & learning
    @Query("SELECT * FROM categories WHERE archived = 0 ORDER BY sortOrder, name")
    fun categories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories")
    suspend fun allCategories(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun categoryCount(): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM categories")
    suspend fun maxCategoryId(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategories(c: List<CategoryEntity>)

    @Query("SELECT categoryId FROM merchant_rules WHERE merchantKey = :key")
    suspend fun ruleFor(key: String): Long?

    @Query("SELECT * FROM merchant_rules")
    suspend fun allRules(): List<MerchantRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRules(r: List<MerchantRuleEntity>)

    @Query("SELECT * FROM txn_overrides WHERE dedupKey = :dedupKey")
    suspend fun overrideFor(dedupKey: String): TxnOverrideEntity?

    @Query("SELECT * FROM txn_overrides")
    suspend fun allOverrides(): List<TxnOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverrides(o: List<TxnOverrideEntity>)

    @Query("SELECT dedupKey FROM sms WHERE id = :smsId")
    suspend fun dedupKeyOf(smsId: Long): String?

    @Query("UPDATE transactions SET categoryId = :categoryId, categoryUserSet = 1 WHERE id = :id")
    suspend fun setTxnCategory(id: Long, categoryId: Long?)

    /** Apply a learned rule to other transactions from the same merchant that you haven't set by hand. */
    @Query(
        "UPDATE transactions SET categoryId = :categoryId WHERE merchantKey = :key AND categoryUserSet = 0 " +
            "AND type IN ('PURCHASE', 'REFUND')",
    )
    suspend fun applyRule(key: String, categoryId: Long)

    @Query("SELECT * FROM transactions WHERE merchantKey IS NULL")
    suspend fun txnsWithoutMerchantKey(): List<TransactionEntity>

    @Query("UPDATE transactions SET merchantKey = :key, categoryId = :categoryId WHERE id = :id")
    suspend fun setMerchantKeyAndCategory(id: Long, key: String, categoryId: Long?)

    @Query("SELECT * FROM transactions WHERE timestamp >= :from ORDER BY timestamp")
    suspend fun txnsSince(from: Long): List<TransactionEntity>

    // --- goals
    @Query("SELECT * FROM savings_goals ORDER BY createdAt")
    fun goals(): Flow<List<GoalEntity>>

    @Query("SELECT * FROM savings_goals")
    suspend fun allGoals(): List<GoalEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(g: GoalEntity): Long

    @Query("DELETE FROM savings_goals WHERE id = :id")
    suspend fun deleteGoal(id: Long)

    // --- exchange rates
    @Query("SELECT * FROM fx_rates ORDER BY currency")
    fun rates(): Flow<List<FxRateEntity>>

    @Query("SELECT * FROM fx_rates")
    suspend fun allRates(): List<FxRateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRates(r: List<FxRateEntity>)

    @Query("SELECT * FROM transactions WHERE currency != 'AED'")
    suspend fun foreignTxns(): List<TransactionEntity>

    @Query("UPDATE transactions SET amountAedMinor = :aedMinor, fxEstimated = 1 WHERE id = :id")
    suspend fun setAed(id: Long, aedMinor: Long?)

    // --- backup
    @Query("SELECT * FROM sms ORDER BY receivedAt")
    suspend fun allSms(): List<SmsEntity>

    @Query("SELECT * FROM transactions ORDER BY timestamp")
    suspend fun allTxns(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE source = 'MANUAL' ORDER BY timestamp")
    suspend fun manualTxns(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE source = 'MANUAL' AND timestamp = :ts AND amountMinor = :amountMinor AND merchant = :merchant")
    suspend fun countManual(ts: Long, amountMinor: Long, merchant: String): Int

    @Query("SELECT * FROM statements")
    suspend fun allStatements(): List<StatementEntity>
}

data class StatusCount(val status: String, val n: Int)

@Database(
    entities = [
        SmsEntity::class, TransactionEntity::class, StatementEntity::class, CardEntity::class,
        CategoryEntity::class, MerchantRuleEntity::class, TxnOverrideEntity::class, GoalEntity::class, FxRateEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "cardtracker.db")
                .addMigrations(*Migrations.ALL)
                // v1 was a pre-release test build; its data is simply re-imported by Sync.
                .fallbackToDestructiveMigrationFrom(1)
                .build()
    }
}
