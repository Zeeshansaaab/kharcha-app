package pk.kharcha.sms

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pk.kharcha.data.Db
import pk.kharcha.data.Txn
import pk.kharcha.data.detectTransfers
import pk.kharcha.parse.SmsParser

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val sender = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val ts = messages.first().timestampMillis

        val txn = SmsParser.parse(sender, body, ts, source = "sms") ?: return
        val db = Repo.db(context)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                db.txns().insertAll(listOf(txn))
                db.txns().detectTransfers(txn.monthKey)
            } finally {
                pending.finish()
            }
        }
    }
}

class TxnNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!SmsParser.isKnownSender(sbn.packageName)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ")

        val txn = SmsParser.parse(sbn.packageName, body, sbn.postTime, "notification") ?: return
        val db = Repo.db(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            db.txns().insertAll(listOf(txn))
            db.txns().detectTransfers(txn.monthKey)
        }
    }
}

/** What the importer saw. Rendered by the diagnostics screen. */
data class ImportReport(
    val inboxTotal: Int = 0,
    val knownSender: Int = 0,
    val attempted: Int = 0,
    val imported: Int = 0,
    val senders: List<Pair<String, Int>> = emptyList(),
    val samples: List<String> = emptyList(),
    val error: String? = null
)

object Backfill {

    /**
     * Reads the whole inbox. Safe to run on every launch: fingerprints make
     * re-imports a no-op. Returns a report so a zero result is explainable
     * rather than mysterious.
     */
    suspend fun run(resolver: ContentResolver, db: Db): ImportReport {
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val batch = mutableListOf<Txn>()
        val months = mutableSetOf<String>()
        val senderCounts = mutableMapOf<String, Int>()
        val samples = mutableListOf<String>()
        var total = 0; var known = 0; var attempted = 0; var imported = 0

        try {
            resolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, cols, null, null,
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val a = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val b = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val d = c.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (c.moveToNext()) {
                    total++
                    val sender = c.getString(a).orEmpty()
                    val body = c.getString(b).orEmpty()
                    val ts = c.getLong(d)

                    if (SmsParser.isKnownSender(sender)) {
                        known++
                        senderCounts[sender] = (senderCounts[sender] ?: 0) + 1
                    }
                    if (!SmsParser.worthParsing(sender, body)) continue
                    attempted++

                    val txn = SmsParser.parse(sender, body, ts, "sms")
                    if (txn != null) {
                        imported++
                        batch += txn
                        months += txn.monthKey
                    } else if (samples.size < 12 && SmsParser.isKnownSender(sender)) {
                        // Keep the ones that got away — these are what the
                        // patterns need to be widened against.
                        samples += "[$sender · ${SmsParser.skipReason(sender, body)}] " +
                            body.replace(Regex("""\s+"""), " ").take(180)
                    }

                    if (batch.size >= 500) { db.txns().insertAll(batch); batch.clear() }
                }
            } ?: return ImportReport(error = "Inbox query returned null — SMS permission not granted.")
        } catch (e: SecurityException) {
            return ImportReport(error = "SecurityException: ${e.message}")
        }

        if (batch.isNotEmpty()) db.txns().insertAll(batch)
        months.forEach { db.txns().detectTransfers(it) }
        applyRules(db)

        return ImportReport(
            inboxTotal = total,
            knownSender = known,
            attempted = attempted,
            imported = imported,
            senders = senderCounts.entries.sortedByDescending { it.value }
                .take(15).map { it.key to it.value },
            samples = samples
        )
    }

    suspend fun applyRules(db: Db) {
        db.rules().all().forEach { db.txns().applyRule(it.match, it.category) }
    }
}

object Repo {
    @Volatile private var instance: Db? = null

    fun db(context: Context): Db = instance ?: synchronized(this) {
        instance ?: androidx.room.Room
            .databaseBuilder(context.applicationContext, Db::class.java, "kharcha.db")
            .build()
            .also { instance = it }
    }
}