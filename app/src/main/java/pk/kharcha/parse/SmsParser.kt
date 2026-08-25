package pk.kharcha.parse

import pk.kharcha.data.Db
import pk.kharcha.data.Direction
import pk.kharcha.data.MatchType
import pk.kharcha.data.ParseRule
import pk.kharcha.data.Rule
import pk.kharcha.data.SenderRule
import pk.kharcha.data.Txn
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Never matches — the safe default before the first config reload completes. */
private val NO_AMOUNT_MATCH = Regex("(?!)")

/**
 * Builds the amount-detection regex from the user's Currency tokens
 * (Settings), so recognising "$45", "Rs 45" or "45 EUR" is configuration,
 * not code — any currency works once its symbol/code is in the list.
 */
private fun buildAmountRegex(tokens: List<String>): Regex {
    if (tokens.isEmpty()) return NO_AMOUNT_MATCH
    val alt = tokens.joinToString("|") { Regex.escape(it) }
    return """(?:$alt)\s*([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s*(?:$alt)"""
        .toRegex(RegexOption.IGNORE_CASE)
}

/** In-memory snapshot of the rules, so the SMS receiver never touches disk. */
data class ParserConfig(
    val senders: List<SenderRule> = emptyList(),
    val rules: List<ParseRule> = emptyList(),
    val ignores: List<String> = emptyList(),
    val merchantRules: List<Rule> = emptyList(),
    val amountRegex: Regex = NO_AMOUNT_MATCH
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
            ignores = db.config().ignores().map { it.phrase.lowercase() },
            merchantRules = db.rules().all(),
            amountRegex = buildAmountRegex(db.config().currencyTokens().map { it.token })
        )
    }

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
            category = categoryFor(e.merchant),
            isTransfer = false,
            source = source,
            body = clean(body),
            fingerprint = fingerprint(e.account, e.direction, e.amountPaisa, timestamp)
        )
    }

    /**
     * Same substring match as TxnDao.applyRule, so a message that matches a
     * merchant you've already categorised arrives pre-categorised instead of
     * waiting for the next rescan.
     */
    private fun categoryFor(rawMerchant: String): String? {
        val raw = rawMerchant.lowercase()
        return config.merchantRules.firstOrNull { it.match in raw }?.category
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

        val why = if (config.amountRegex.containsMatchIn(text))
            "Found an amount but no matching debit or credit rule"
        else
            "No amount found in this message"
        return Explanation(false, acct, null, null, "", null, why)
    }

    private fun genericAmount(text: String): Long? =
        config.amountRegex.find(text)?.let { m ->
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
