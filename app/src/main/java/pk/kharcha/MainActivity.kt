package pk.kharcha

import android.Manifest
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import pk.kharcha.data.*
import pk.kharcha.sms.Backfill
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

    /**
     * Saves the rule first, then applies it to the whole history. One tap
     * categorises every Foodpanda charge you have ever made.
     */
    fun categorise(txn: Txn, category: String) = viewModelScope.launch {
        val match = txn.merchant.ifBlank { txn.rawMerchant.lowercase() }
        if (match.isBlank()) return@launch
        db.rules().upsert(Rule(match, category))
        db.txns().applyRule(match, category)
    }

    fun importHistory(onDone: () -> Unit = {}) = viewModelScope.launch {
        Backfill.run(getApplication<Application>().contentResolver, db)
        onDone()
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KharchaTheme {
                val vm: MainViewModel = viewModel()
                val state by vm.state.collectAsState()
                var sheetFor by remember { mutableStateOf<Txn?>(null) }
                var granted by remember { mutableStateOf(false) }

                val ask = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    granted = result.values.all { it }
                    // First grant is also first import: pull the whole inbox.
                    if (granted) vm.importHistory()
                }

                LaunchedEffect(Unit) {
                    ask.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS))
                }

                HomeScreen(
                    state = state,
                    onMonthStep = vm::stepMonth,
                    onCategorise = { sheetFor = it }
                )

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
