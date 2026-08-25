package pk.kharcha.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class Direction { DEBIT, CREDIT }
enum class MatchType { KEYWORD, REGEX }

@Entity(
    tableName = "txn",
    indices = [Index(value = ["fingerprint"], unique = true), Index("monthKey")]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val monthKey: String,
    val account: String,
    val sender: String,
    val direction: Direction,
    val amountPaisa: Long,
    val merchant: String,
    val rawMerchant: String,
    val category: String?,
    val isTransfer: Boolean,
    val source: String,
    val body: String,
    val fingerprint: String
)

/** merchant substring -> category. Grows as you tap to categorise. */
@Entity(tableName = "rule")
data class Rule(
    @PrimaryKey val match: String,
    val category: String
)

/**
 * Maps a sender to an account. `pattern` is a plain lowercase substring, so
 * "8558", "hblpk" and "meezan" all work without any regex knowledge.
 */
@Entity(tableName = "sender")
data class SenderRule(
    @PrimaryKey val pattern: String,
    val account: String,
    val enabled: Boolean = true
)

/**
 * How to recognise a debit or credit. KEYWORD matches a plain phrase anywhere
 * in the message. REGEX is the escape hatch: if the pattern has a capture
 * group, that group becomes the amount instead of the generic amount pattern.
 * A null account means the rule applies to every bank.
 */
@Entity(tableName = "parse_rule")
data class ParseRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val account: String?,
    val direction: Direction,
    val type: MatchType,
    val value: String,
    val enabled: Boolean = true
)

/** Phrases that disqualify a message outright: OTPs, balance alerts, promos. */
@Entity(tableName = "ignore_rule")
data class IgnoreRule(
    @PrimaryKey val phrase: String,
    val enabled: Boolean = true
)

data class CategoryTotal(val category: String?, val totalPaisa: Long)
data class AccountTotal(val account: String, val totalPaisa: Long)

@Dao
interface TxnDao {

    /** IGNORE on conflict is what makes re-running the import safe. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(txns: List<Txn>): List<Long>

    @Query("SELECT * FROM txn WHERE monthKey = :month ORDER BY timestamp DESC")
    fun forMonth(month: String): Flow<List<Txn>>

    @Query(
        """SELECT category, SUM(amountPaisa) AS totalPaisa FROM txn
           WHERE monthKey = :month AND direction = 'DEBIT' AND isTransfer = 0
           GROUP BY category ORDER BY totalPaisa DESC"""
    )
    fun categoryTotals(month: String): Flow<List<CategoryTotal>>

    @Query(
        """SELECT account, SUM(amountPaisa) AS totalPaisa FROM txn
           WHERE monthKey = :month AND direction = 'DEBIT' AND isTransfer = 0
           GROUP BY account ORDER BY totalPaisa DESC"""
    )
    fun accountTotals(month: String): Flow<List<AccountTotal>>

    @Query("SELECT COUNT(*) FROM txn WHERE monthKey = :month AND category IS NULL AND isTransfer = 0")
    fun uncategorisedCount(month: String): Flow<Int>

    /** Applying a rule retroactively is the point of tapping to categorise. */
    @Query("UPDATE txn SET category = :category WHERE lower(rawMerchant) LIKE '%' || :match || '%'")
    suspend fun applyRule(match: String, category: String)

    @Query("UPDATE txn SET isTransfer = 1 WHERE id IN (:ids)")
    suspend fun markTransfers(ids: List<Long>)

    @Query("SELECT * FROM txn WHERE isTransfer = 0 AND monthKey = :month")
    suspend fun forTransferScan(month: String): List<Txn>

    @Query("DELETE FROM txn WHERE id = :id")
    suspend fun delete(id: Long)

    /** Used before a rescan: changed rules produce different fingerprints. */
    @Query("DELETE FROM txn")
    suspend fun deleteAll()
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rule")
    suspend fun all(): List<Rule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: Rule)
}

@Dao
interface ConfigDao {

    @Query("SELECT * FROM sender ORDER BY account, pattern")
    fun sendersFlow(): Flow<List<SenderRule>>

    @Query("SELECT * FROM sender WHERE enabled = 1")
    suspend fun senders(): List<SenderRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSender(s: SenderRule)

    @Delete
    suspend fun deleteSender(s: SenderRule)

    @Query("SELECT * FROM parse_rule ORDER BY account, direction")
    fun parseRulesFlow(): Flow<List<ParseRule>>

    @Query("SELECT * FROM parse_rule WHERE enabled = 1")
    suspend fun parseRules(): List<ParseRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParseRule(r: ParseRule)

    @Delete
    suspend fun deleteParseRule(r: ParseRule)

    @Query("SELECT * FROM ignore_rule")
    fun ignoresFlow(): Flow<List<IgnoreRule>>

    @Query("SELECT * FROM ignore_rule WHERE enabled = 1")
    suspend fun ignores(): List<IgnoreRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIgnore(i: IgnoreRule)

    @Delete
    suspend fun deleteIgnore(i: IgnoreRule)

    @Query("SELECT COUNT(*) FROM sender")
    suspend fun senderCount(): Int
}

@Database(
    entities = [Txn::class, Rule::class, SenderRule::class, ParseRule::class, IgnoreRule::class],
    version = 3,
    exportSchema = false
)
abstract class Db : RoomDatabase() {
    abstract fun txns(): TxnDao
    abstract fun rules(): RuleDao
    abstract fun config(): ConfigDao
}

/**
 * Seeds a starting configuration on first launch. Deliberately conservative:
 * brand-name substrings only, no invented short codes. Add the real numeric
 * senders from your own inbox in Settings.
 */
suspend fun Db.seedIfEmpty() {
    if (config().senderCount() > 0) return

    listOf(
        "hbl" to "HBL",
        "scb" to "StanChart",
        "stanchart" to "StanChart",
        "meezan" to "Meezan",
        "sadapay" to "SadaPay",
        "jazzcash" to "JazzCash",
        "jazz" to "JazzCash"
    ).forEach { (p, a) -> config().upsertSender(SenderRule(p, a)) }

    listOf(
        "debited", "has been debited", "spent", "paid", "payment of",
        "purchase", "withdrawn", "transferred to", "sent to", "deducted"
    ).forEach {
        config().upsertParseRule(ParseRule(0, null, Direction.DEBIT, MatchType.KEYWORD, it))
    }

    listOf(
        "credited", "has been credited", "received", "deposited", "refund"
    ).forEach {
        config().upsertParseRule(ParseRule(0, null, Direction.CREDIT, MatchType.KEYWORD, it))
    }

    listOf(
        "otp", "one-time password", "do not share", "verification code",
        "available balance is", "mini statement", "lucky draw"
    ).forEach { config().upsertIgnore(IgnoreRule(it)) }
}

/**
 * Pairs a debit against a credit of equal value in a different account within
 * a short window and flags both. Without this, every rupee you move between
 * your own accounts is counted as spending.
 */
suspend fun TxnDao.detectTransfers(month: String, windowMillis: Long = 15 * 60_000L) {
    val txns = forTransferScan(month)
    val debits = txns.filter { it.direction == Direction.DEBIT }
    val credits = txns.filter { it.direction == Direction.CREDIT }
    val paired = mutableListOf<Long>()
    val usedCredits = mutableSetOf<Long>()

    for (d in debits) {
        val match = credits.firstOrNull { c ->
            c.id !in usedCredits &&
                c.account != d.account &&
                c.amountPaisa == d.amountPaisa &&
                kotlin.math.abs(c.timestamp - d.timestamp) <= windowMillis
        } ?: continue
        usedCredits += match.id
        paired += listOf(d.id, match.id)
    }
    if (paired.isNotEmpty()) markTransfers(paired)
}
