package pk.kharcha

package pk.kharcha

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pk.kharcha.data.*
import pk.kharcha.parse.Explanation
import pk.kharcha.parse.SmsParser
import pk.kharcha.sms.Backfill
import pk.kharcha.sms.ImportReport
import pk.kharcha.sms.Repo
import pk.kharcha.ui.*
import java.text.SimpleDateFormat
import java.util.*

val DefaultCategories = listOf(
    "Food", "Transport", "Bills", "Groceries", "Shopping",
    "Health", "Cash", "Family", "Subscriptions", "Other"
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = Repo.db(app)
    private val monthFmt = SimpleDateFormat("yyyy-MM", Locale.US)
    private val labelFmt = SimpleDateFormat("MMMM yyyy", Locale.US)

    private val cursor = MutableStateFlow(Calendar.getInstance())
    private val month = cursor.map { monthFmt.format(it.time) }.distinctUntilChanged()

    val report = MutableStateFlow<ImportReport?>(null)
    val importing = MutableStateFlow(false)
    val testResult = MutableStateFlow<Explanation?>(null)

    val senders = db.config().sendersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val parseRules = db.config().parseRulesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val ignores = db.config().ignoresFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts = senders.map { list -> list.map { it.account }.distinct().sorted() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<MonthState> = month.flatMapLatest { key ->
        val previous = (cursor.value.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        val previousKey = monthFmt.format(previous.time)

        combine(
            db.txns().forMonth(key),
            db.txns().categoryTotals(key),
            db.txns().accountTotals(key),
            db.txns().uncategorisedCount(key),
            db.txns().categoryTotals(previousKey)
        ) { txns, cats, accts, uncat, prevCats ->
            val total = cats.sumOf { it.totalPaisa }
            val prevTotal = prevCats.sumOf { it.totalPaisa }
            MonthState(
                label = labelFmt.format(cursor.value.time),
                previousLabel = SimpleDateFormat("MMMM", Locale.US).format(previous.time),
                totalPaisa = total,
                deltaPercent = if (prevTotal > 0)
                    (((total - prevTotal) * 100) / prevTotal).toInt() else null,
                categories = cats,
                accounts = accts,
                txns = txns,
                uncategorised = uncat
            )
        }
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        MonthState(labelFmt.format(Date()), "", 0, null, emptyList(), emptyList(), emptyList(), 0)
    )

    init {
        viewModelScope.launch {
            db.seedIfEmpty()
            SmsParser.reload(db)
        }
    }

    fun stepMonth(delta: Int) {
        cursor.value = (cursor.value.clone() as Calendar).apply { add(Calendar.MONTH, delta) }
    }

    fun categorise(txn: Txn, category: String) = viewModelScope.launch {
        val match = txn.merchant.ifBlank { txn.rawMerchant.lowercase() }
        if (match.isBlank()) return@launch
        db.rules().upsert(Rule(match, category))
        db.txns().applyRule(match, category)
    }

    /** Idempotent, so it runs on every launch rather than only after a grant. */
    fun importHistory() = viewModelScope.launch {
        if (importing.value) return@launch
        importing.value = true
        report.value = Backfill.run(getApplication<Application>().contentResolver, db)
        importing.value = false
    }

    fun rescan() = viewModelScope.launch {
        if (importing.value) return@launch
        importing.value = true
        report.value = Backfill.rescan(getApplication<Application>().contentResolver, db)
        importing.value = false
    }

    fun test(sender: String, body: String) {
        testResult.value = SmsParser.explain(sender, body)
    }

    // Every config change reloads the parser cache, so the test box and the
    // live SMS receiver always agree with what's on screen.
    private fun edit(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        SmsParser.reload(db)
    }

    fun addSender(pattern: String, account: String) =
        edit { db.config().upsertSender(SenderRule(pattern, account)) }

    fun deleteSender(s: SenderRule) = edit { db.config().deleteSender(s) }

    fun addParseRule(account: String?, dir: Direction, type: MatchType, value: String) =
        edit { db.config().upsertParseRule(ParseRule(0, account, dir, type, value)) }

    fun deleteParseRule(r: ParseRule) = edit { db.config().deleteParseRule(r) }

    fun addIgnore(phrase: String) = edit { db.config().upsertIgnore(IgnoreRule(phrase)) }

    fun deleteIgnore(i: IgnoreRule) = edit { db.config().deleteIgnore(i) }
}

private enum class Route { HOME, SETTINGS }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the system bars deliberately, then pay for it with
        // explicit inset padding below. The default was drawing behind them
        // without paying, which is why the header sat under the clock.
        enableEdgeToEdge()

        setContent {
            KharchaTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsState()
                val report by vm.report.collectAsState()
                val importing by vm.importing.collectAsState()
                val testResult by vm.testResult.collectAsState()
                val senders by vm.senders.collectAsState()
                val parseRules by vm.parseRules.collectAsState()
                val ignores by vm.ignores.collectAsState()
                val accounts by vm.accounts.collectAsState()

                var route by remember { mutableStateOf(Route.HOME) }
                var sheetFor by remember { mutableStateOf<Txn?>(null) }

                val ask = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { vm.importHistory() }

                LaunchedEffect(Unit) {
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.READ_SMS
                    ) == PackageManager.PERMISSION_GRANTED

                    if (granted) vm.importHistory()
                    else ask.launch(
                        arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                    )
                }

                // One inset barrier for the whole app. Screens below can assume
                // their 0,0 is the first usable pixel.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Ink.Ground)
                        .windowInsetsPadding(WindowInsets.systemBars)
                ) {
                    when (route) {
                        Route.SETTINGS -> SettingsScreen(
                            senders = senders,
                            rules = parseRules,
                            ignores = ignores,
                            accounts = accounts,
                            testResult = testResult,
                            importSummary = importSummary(report, importing),
                            onTest = vm::test,
                            onAddSender = vm::addSender,
                            onDeleteSender = vm::deleteSender,
                            onAddRule = vm::addParseRule,
                            onDeleteRule = vm::deleteParseRule,
                            onAddIgnore = vm::addIgnore,
                            onDeleteIgnore = vm::deleteIgnore,
                            onRescan = vm::rescan,
                            onBack = { route = Route.HOME }
                        )

                        Route.HOME -> HomeScreen(
                            state = state,
                            onMonthStep = vm::stepMonth,
                            onCategorise = { sheetFor = it },
                            onOpenSettings = { route = Route.SETTINGS }
                        )
                    }
                }

                sheetFor?.let { txn ->
                    CategoriseSheet(
                        txn = txn,
                        categories = DefaultCategories,
                        onPick = { vm.categorise(txn, it); sheetFor = null },
                        onDismiss = { sheetFor = null }
                    )
                }
            }
        }
    }
}

/**
 * The import counters plus the messages that didn't parse. A zero result should
 * always say why, rather than leaving you staring at an empty month.
 */
private fun importSummary(report: ImportReport?, importing: Boolean): String = when {
    importing -> "Reading inbox…"
    report == null -> ""
    report.error != null -> report.error!!
    else -> buildString {
        appendLine("inbox      ${report.inboxTotal}")
        appendLine("known      ${report.knownSender}")
        appendLine("imported   ${report.imported}")

        if (report.senders.isNotEmpty()) {
            appendLine()
            appendLine("senders matched")
            report.senders.forEach { (s, n) -> appendLine("  $s  ×$n") }
        }
        if (report.samples.isNotEmpty()) {
            appendLine()
            appendLine("did not parse")
            report.samples.forEach { appendLine("  $it") }
        }
    }
}