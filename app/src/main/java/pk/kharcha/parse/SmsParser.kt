package pk.kharcha.parse

import pk.kharcha.data.Direction
import pk.kharcha.data.Txn
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Turns a bank alert into a Txn, or null if the message isn't a transaction.
 *
 * The patterns below are starting points. Every bank words its alerts
 * differently and changes them without warning. Use the Unparsed screen in
 * Settings to see what is falling through, then tighten these.
 */
object SmsParser {

    private const val AMOUNT =
        """(?:PKR|Rs\.?)\s*([\d,]+(?:\.\d{1,2})?)|([\d,]+(?:\.\d{1,2})?)\s*(?:PKR|Rs\.?)"""

    private val ACCOUNTS = mapOf(
        "HBL" to listOf("""\bHBL\b""", "HBLBANK"),
        "StanChart" to listOf("""\bSCB?\b""", "StanChart", """Standard\s*Chartered"""),
        "Meezan" to listOf("Meezan", "MEEZANBNK"),
        "SadaPay" to listOf("SadaPay", """com\.sadapay"""),
        "JazzCash" to listOf("JazzCash", """com\.techlogix\.mobilink""")
    ).mapValues { (_, v) -> v.map { it.toRegex(RegexOption.IGNORE_CASE) } }

    private val IGNORE = listOf(
        """\b(OTP|one[- ]time|verification code|do not share)\b""",
        """available balance is|balance inquiry|mini statement""",
        """(promo|offer|discount|cashback offer|\bwin\b)"""
    ).map { it.toRegex(RegexOption.IGNORE_CASE) }

    private val DEBIT = listOf(
        """(?:debited|spent|paid|purchase|withdraw\w*|transferred to|sent to).{0,40}?$AMOUNT""",
        """$AMOUNT.{0,40}?(?:has been debited|was debited|debited from)"""
    ).map { it.toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }

    private val CREDIT = listOf(
        """(?:credited|received|deposit\w*|refund\w*).{0,40}?$AMOUNT""",
        """$AMOUNT.{0,40}?(?:has been credited|was credited|credited to)"""
    ).map { it.toRegex(setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)) }

    private val MERCHANT = listOf(
        """(?:at|to|from)\s+([A-Z0-9][A-Za-z0-9 &.\-*']{2,40}?)(?:\s+on\b|\s+for\b|[.,]|$)""",
        """merchant[:\s]+([A-Za-z0-9 &.\-*']{2,40})"""
    ).map { it.toRegex() }

    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)

    fun parse(sender: String, body: String, timestamp: Long, source: String): Txn? {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        if (IGNORE.any { it.containsMatchIn(text) }) return null

        val (direction, paisa) = extract(text) ?: return null
        val raw = merchant(text)

        return Txn(
            timestamp = timestamp,
            monthKey = monthFmt.format(Date(timestamp)),
            account = account(sender),
            direction = direction,
            amountPaisa = paisa,
            merchant = normalise(raw),
            rawMerchant = raw,
            category = null,
            isTransfer = false,
            source = source,
            body = text,
            fingerprint = fingerprint(account(sender), direction, paisa, timestamp)
        )
    }

    private fun extract(text: String): Pair<Direction, Long>? {
        DEBIT.firstNotNullOfOrNull { it.find(text) }?.let { return Direction.DEBIT to paisa(it) }
        CREDIT.firstNotNullOfOrNull { it.find(text) }?.let { return Direction.CREDIT to paisa(it) }
        return null
    }

    /** Parse to integer paisa so no rupee is ever lost to float rounding. */
    private fun paisa(m: MatchResult): Long {
        val raw = m.groupValues.drop(1).first { it.isNotEmpty() }.replace(",", "")
        val parts = raw.split(".")
        val rupees = parts[0].toLong()
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0L
        return rupees * 100 + fraction
    }

    private fun account(sender: String) =
        ACCOUNTS.entries.firstOrNull { (_, pats) -> pats.any { it.containsMatchIn(sender) } }
            ?.key ?: "Unknown"

    private fun merchant(text: String) =
        MERCHANT.firstNotNullOfOrNull { it.find(text) }?.groupValues?.get(1)?.trim(' ', '.', ',', '-')
            ?: ""

    /** Strips card suffixes and reference numbers so "K-ELECTRIC*4471" groups with "K-ELECTRIC". */
    private fun normalise(raw: String) = raw
        .replace(Regex("""[*#]\d{3,}"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()

    /**
     * Same account, same amount, same direction, same minute = one event.
     * A card swipe usually fires both an SMS and an app notification; this
     * collapses them, and makes the historical backfill re-runnable.
     */
    private fun fingerprint(account: String, dir: Direction, paisa: Long, ts: Long): String {
        val minute = ts / 60_000
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$account|$dir|$paisa|$minute".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    fun isKnownSender(sender: String) = account(sender) != "Unknown"
}
