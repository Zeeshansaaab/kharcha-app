package pk.kharcha.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class Direction { DEBIT, CREDIT }

@Entity(
    tableName = "txn",
    indices = [Index(value = ["fingerprint"], unique = true), Index("monthKey")]
)
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val monthKey: String,          // "2026-08", the grouping key
    val account: String,           // HBL, SadaPay, Meezan, JazzCash, StanChart
    val direction: Direction,
    val amountPaisa: Long,         // integer paisa; never store money as Double
    val merchant: String,          // normalised counterparty, "" if unparsed
    val rawMerchant: String,       // exactly as it appeared, for rule-building
    val category: String?,         // null = needs categorising, surfaced in UI
    val isTransfer: Boolean,
    val source: String,            // "sms" or "notification"
    val body: String,
    val fingerprint: String        // dedupe key, see SmsParser.fingerprint
)

/** merchant substring -> category. Grows as the user taps to categorise. */
@Entity(tableName = "rule")
data class Rule(
    @PrimaryKey val match: String,   // lowercase substring
    val category: String
)

data class CategoryTotal(val category: String?, val totalPaisa: Long)
data class AccountTotal(val account: String, val totalPaisa: Long)

@Dao
interface TxnDao {

    /** IGNORE on conflict is what makes re-running the backfill safe. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(txns: List<Txn>): List<Long>

    @Query("SELECT DISTINCT monthKey FROM txn ORDER BY monthKey DESC")
    fun months(): Flow<List<String>>

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

    /** Applying a rule retroactively is the whole point of tapping to categorise. */
    @Query("UPDATE txn SET category = :category WHERE lower(rawMerchant) LIKE '%' || :match || '%'")
    suspend fun applyRule(match: String, category: String)

    @Query("SELECT MAX(timestamp) FROM txn")
    suspend fun latestTimestamp(): Long?

    @Query("UPDATE txn SET isTransfer = 1 WHERE id IN (:ids)")
    suspend fun markTransfers(ids: List<Long>)

    @Query("SELECT * FROM txn WHERE isTransfer = 0 AND monthKey = :month")
    suspend fun forTransferScan(month: String): List<Txn>
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rule")
    suspend fun all(): List<Rule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: Rule)
}

@Database(entities = [Txn::class, Rule::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun txns(): TxnDao
    abstract fun rules(): RuleDao
}

/**
 * Matches a debit in one account against a credit of the same amount in another
 * within a short window, and flags both as an internal transfer. Without this,
 * every rupee you move between your own accounts is counted as spending.
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
