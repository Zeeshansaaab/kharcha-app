package pk.kharcha

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pk.kharcha.data.*
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
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KharchaTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsState()
                val report by vm.report.collectAsState()
                val importing by vm.importing.collectAsState()
                var sheetFor by remember { mutableStateOf<Txn?>(null) }
                var showDebug by remember { mutableStateOf(false) }

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

                if (showDebug) {
                    DebugScreen(report, importing, onBack = { showDebug = false })
                } else {
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(
                            state = state,
                            onMonthStep = vm::stepMonth,
                            onCategorise = { sheetFor = it }
                        )
                        // Tucked in the corner rather than in the nav bar: this
                        // is a tool for building the app, not for using it.
                        Text(
                            "debug",
                            color = Ink.Ghost,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .align(androidx.compose.ui.Alignment.TopEnd)
                                .clickable { showDebug = true }
                                .padding(14.dp)
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

@Composable
private fun DebugScreen(report: ImportReport?, importing: Boolean, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Ground)
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Text("Import report", color = Ink.Chalk, fontSize = 20.sp,
            modifier = Modifier.clickable { onBack() })
        Spacer(Modifier.height(4.dp))
        Text("Tap the title to go back", color = Ink.Faint, fontSize = 12.sp)
        Spacer(Modifier.height(20.dp))

        when {
            importing -> Text("Reading inbox…", color = Ink.Marigold, fontSize = 14.sp)
            report == null -> Text("No import has run.", color = Ink.Faint, fontSize = 14.sp)
            report.error != null -> Text(report.error!!, color = Ink.Clay, fontSize = 14.sp)
            else -> {
                Line("Messages in inbox", report.inboxTotal.toString())
                Line("From a known sender", report.knownSender.toString())
                Line("Parse attempted", report.attempted.toString())
                Line("Imported", report.imported.toString())

                Spacer(Modifier.height(22.dp))
                Text("Senders seen", color = Ink.Faint, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                report.senders.forEach { (s, n) -> Line(s, n.toString()) }

                Spacer(Modifier.height(22.dp))
                Text("Messages that did not parse", color = Ink.Faint, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                report.samples.forEach {
                    Text(it, color = Ink.Chalk2, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 12.dp))
                }
                if (report.samples.isEmpty()) {
                    Text("None captured.", color = Ink.Ghost, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Ink.Chalk2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Ink.Chalk, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}