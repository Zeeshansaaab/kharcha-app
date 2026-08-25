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

/** Live capture of incoming SMS. Registered in the manifest, fires while the app is closed. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // A long alert arrives as several parts; join them before parsing.
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

/**
 * SadaPay and JazzCash are notification-first and don't reliably SMS every
 * transaction, so we listen to their notifications too. Duplicates against
 * SMS are collapsed by the fingerprint.
 */
class TxnNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (!SmsParser.isKnownSender(pkg)) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ")

        val txn = SmsParser.parse(pkg, body, sbn.postTime, source = "notification") ?: return
        val db = Repo.db(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            db.txns().insertAll(listOf(txn))
            db.txns().detectTransfers(txn.monthKey)
        }
    }
}

object Backfill {

    /**
     * Reads the entire SMS inbox and imports everything that parses. Safe to
     * run repeatedly: fingerprints make re-imports a no-op. Run this once on
     * first launch and you have years of history before you've spent a rupee.
     */
    suspend fun run(resolver: ContentResolver, db: Db, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val batch = mutableListOf<Txn>()
        val months = mutableSetOf<String>()

        resolver.query(Telephony.Sms.Inbox.CONTENT_URI, cols, null, null, "${Telephony.Sms.DATE} ASC")
            ?.use { cursor ->
                val total = cursor.count
                val addr = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                var seen = 0

                while (cursor.moveToNext()) {
                    seen++
                    val sender = cursor.getString(addr).orEmpty()
                    if (SmsParser.isKnownSender(sender)) {
                        SmsParser.parse(sender, cursor.getString(body).orEmpty(),
                            cursor.getLong(date), source = "sms")
                            ?.let { batch += it; months += it.monthKey }
                    }
                    if (batch.size >= 500) {
                        db.txns().insertAll(batch); batch.clear()
                    }
                    if (seen % 200 == 0) onProgress(seen, total)
                }
                onProgress(total, total)
            }

        if (batch.isNotEmpty()) db.txns().insertAll(batch)
        months.forEach { db.txns().detectTransfers(it) }
        applyRules(db)
    }

    /** Re-applies every saved merchant rule across the whole history. */
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
