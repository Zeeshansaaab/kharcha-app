package pk.kharcha.parse

import pk.kharcha.data.Db
import pk.kharcha.data.Direction
import pk.kharcha.data.MatchType
import pk.kharcha.data.ParseRule
import pk.kharcha.data.SenderRule
import pk.kharcha.data.Txn
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory snapshot of the rules, so the SMS receiver never touches disk. */
data class ParserConfig(
    val senders: List<SenderRule> = emptyList(),
    val rules: List<ParseRule> = emptyList(),
    val ignores: List<String> = emptyList()
)

/** What the parser decided, and why. Drives the test box in Settings. */
data class Explanation(
    val ok: Boolean,
    val account: String,
    val direction: Direction?,
    val amountPaisa: Long?,
    val merchant: String,
    val firedRule: String?,
    val reason: String
)

object SmsParser {

    @Volatile
    var config: ParserConfig = ParserConfig()
        private set

    suspend fun reload(db: Db) {
        config = ParserConfig(
            senders = db.config().senders(),
            rules = db.config().parseRules(),
            ignores = db.config().ignores().map { it.phrase.lowercase() }
        )
    }

    private val AMOUNT =
        """(?:PKR|RS|Rs\.?)\s*([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s*(?:PKR|RS|Rs\.?)"""
            .toRegex(RegexOption.IGNORE_CASE)

    private val MERCHANT = listOf(
        """(?:at|to|from)\s+([A-Z0-9][A-Za-z0-9 &.\-*']{2,40}?)(?:\s+on\b|\s+for\b|[.,]|$)""",
        """merchant[:\s]+([A-Za-z0-9 &.\-*']{2,40})"""
    ).map { it.toRegex() }

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

    fun account(sender: String): String {
        val s = sender.lowercase()
        return config.senders.firstOrNull { it.enabled && it.pattern.lowercase() in s }
            ?.account ?: "Unknown"
    }

    fun isKnownSender(sender: String) = account(sender) != "Unknown"

    fun parse(sender: String, body: String, timestamp: Long, source: String): Txn? {
        val e = explain(sender, body)
        if (!e.ok) return null

        return Txn(
            timestamp = timestamp,
            monthKey = monthFmt.format(Date(timestamp)),
            account = e.account,
            sender = sender,
            direction = e.direction!!,
            amountPaisa = e.amountPaisa!!,
            merchant = normalise(e.merchant),
            rawMerchant = e.merchant,
            category = null,
            isTransfer = false,
            source = source,
            body = clean(body),
            fingerprint = fingerprint(e.account, e.direction, e.amountPaisa, timestamp)
        )
    }

    /**
     * The single decision path. parse(), the import report and the Settings
     * test box all call this, so what the test box shows is exactly what the
     * importer does.
     */
    fun explain(sender: String, body: String): Explanation {
        val text = clean(body)
        val lower = text.lowercase()
        val acct = account(sender)

        config.ignores.firstOrNull { it in lower }?.let {
            return Explanation(false, acct, null, null, "", null, "Ignored: matched \"$it\"")
        }

        val applicable = config.rules.filter {
            it.enabled && (it.account == null || it.account == acct)
        }
        if (applicable.isEmpty()) {
            return Explanation(false, acct, null, null, "", null, "No parse rules apply to $acct")
        }

        for (rule in applicable) {
            val amount = when (rule.type) {
                MatchType.KEYWORD ->
                    if (rule.value.lowercase() in lower) genericAmount(text) else null

                MatchType.REGEX -> runCatching {
                    val m = rule.value.toRegex(
                        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                    ).find(text)
                    when {
                        m == null -> null
                        m.groupValues.size > 1 && m.groupValues[1].isNotBlank() ->
                            toPaisa(m.groupValues[1])
                        else -> genericAmount(text)
                    }
                }.getOrNull()
            } ?: continue

            return Explanation(
                ok = true,
                account = acct,
                direction = rule.direction,
                amountPaisa = amount,
                merchant = merchant(text),
                firedRule = "${rule.direction} · ${rule.type} · ${rule.value}",
                reason = if (acct == "Unknown")
                    "Parsed, but no sender rule matched — filed under Unknown"
                else "Parsed"
            )
        }

        val why = if (AMOUNT.containsMatchIn(text))
            "Found an amount but no matching debit or credit rule"
        else
            "No amount found in this message"
        return Explanation(false, acct, null, null, "", null, why)
    }

    private fun genericAmount(text: String): Long? =
        AMOUNT.find(text)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.let { toPaisa(it) }
        }

    /** Integer paisa, never Double: float rounding loses rupees over time. */
    private fun toPaisa(raw: String): Long? = runCatching {
        val parts = raw.replace(",", "").trim().split(".")
        val rupees = parts[0].toLong()
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0L
        rupees * 100 + fraction
    }.getOrNull()

    private fun clean(body: String) = body.replace(Regex("""\s+"""), " ").trim()

    private fun merchant(text: String) =
        MERCHANT.firstNotNullOfOrNull { it.find(text) }?.groupValues?.get(1)
            ?.trim(' ', '.', ',', '-') ?: ""

    /** Strips card suffixes so "K-ELECTRIC*4471" groups with "K-ELECTRIC". */
    private fun normalise(raw: String) = raw
        .replace(Regex("""[*#]\d{3,}"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()

    /**
     * Same account, amount, direction and minute = one event. Collapses the
     * SMS/notification double-fire and makes re-importing a no-op.
     */
    private fun fingerprint(account: String, dir: Direction, paisa: Long, ts: Long): String {
        val minute = ts / 60_000
        return MessageDigest.getInstance("SHA-256")
            .digest("$account|$dir|$paisa|$minute".toByteArray())
            .joinToString("") { "%02x".format(it) }.take(24)
    }
}
