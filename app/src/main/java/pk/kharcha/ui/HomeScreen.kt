package pk.kharcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import pk.kharcha.data.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(state: MonthState, onMonthStep: (Int) -> Unit, onCategorise: (Txn) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Ink.Ground),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item { MonthHeader(state, onMonthStep) }
        item { Spine(state.categories, state.totalPaisa) }
        item { Legend(state.categories) }
        item { Accounts(state.accounts, state.accounts.maxOfOrNull { it.totalPaisa } ?: 1L) }
        item { DayHeader(state.uncategorised) }
        items(state.txns, key = { it.id }) { TxnRow(it, onCategorise) }

        if (state.txns.isEmpty()) item { EmptyMonth() }
    }
}

@Composable
private fun MonthHeader(state: MonthState, onStep: (Int) -> Unit) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.label, style = MaterialTheme.typography.titleLarge, color = Ink.Chalk)
            Spacer(Modifier.weight(1f))
            Text("‹", color = Ink.Faint, modifier = Modifier
                .clickable { onStep(-1) }.padding(horizontal = 10.dp))
            Text("›", color = Ink.Faint, modifier = Modifier
                .clickable { onStep(1) }.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text("Spent", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                state.totalPaisa.asRupees(),
                style = MaterialTheme.typography.displayLarge,
                color = Ink.Chalk
            )
            state.deltaPercent?.let {
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (it >= 0) "+$it%" else "$it%") + " vs " + state.previousLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (it >= 0) Ink.Marigold else Ink.Jade,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

/**
 * The month spine. One bar, the whole month's outflow, segmented by category.
 * This is the screen's centre of gravity: everything below it is detail.
 */
@Composable
private fun Spine(categories: List<CategoryTotal>, total: Long) {
    if (total <= 0L) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(11.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        categories.forEach { c ->
            Box(
                Modifier
                    .weight(c.totalPaisa.toFloat().coerceAtLeast(1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(colorFor(c.category))
            )
        }
    }
}

@Composable
private fun Legend(categories: List<CategoryTotal>) {
    FlowRowCompat(Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp)) {
        categories.take(5).forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 14.dp, bottom = 6.dp)) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(2.dp)).background(colorFor(c.category)))
                Spacer(Modifier.width(5.dp))
                Text(
                    "${c.category ?: "Uncategorised"} ${c.totalPaisa.asRupees()}",
                    style = MaterialTheme.typography.labelSmall, color = Ink.Muted
                )
            }
        }
    }
}

@Composable
private fun Accounts(accounts: List<AccountTotal>, max: Long) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text("Accounts", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        Spacer(Modifier.height(10.dp))
        accounts.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 5.dp)) {
                Text(a.account, style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Chalk2, modifier = Modifier.width(78.dp))
                Box(
                    Modifier.weight(1f).height(3.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Ink.Line)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(a.totalPaisa.toFloat() / max.coerceAtLeast(1L))
                            .fillMaxHeight().background(Ink.Jade)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(a.totalPaisa.asRupees(), fontFamily = Numeral,
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Muted)
            }
        }
    }
}

@Composable
private fun DayHeader(uncategorised: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Transactions", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        Spacer(Modifier.weight(1f))
        if (uncategorised > 0) {
            Text("$uncategorised need a category",
                style = MaterialTheme.typography.labelSmall, color = Ink.Marigold)
        }
    }
}

private val dayFmt = SimpleDateFormat("d MMM", Locale.US)

@Composable
private fun TxnRow(txn: Txn, onCategorise: (Txn) -> Unit) {
    val dimmed = txn.isTransfer
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = !dimmed) { onCategorise(txn) }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(if (dimmed) Ink.Surface else colorFor(txn.category).copy(alpha = 0.16f))
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                txn.merchant.ifBlank { "Unknown" }.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) Ink.Ghost else Ink.Chalk,
                textDecoration = if (dimmed) TextDecoration.LineThrough else null
            )
            Text(
                when {
                    dimmed -> "Transfer, not counted"
                    txn.category == null -> "Tap to categorise"
                    else -> "${txn.account} · ${dayFmt.format(Date(txn.timestamp))}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (txn.category == null && !dimmed) Ink.Marigold else Ink.Faint
            )
        }
        Text(
            txn.amountPaisa.asRupees(), fontFamily = Numeral,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dimmed) Ink.Ghost else Ink.Chalk
        )
    }
}

@Composable
private fun EmptyMonth() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Nothing here for this month",
            style = MaterialTheme.typography.bodyLarge, color = Ink.Chalk2)
        Spacer(Modifier.height(6.dp))
        Text("Step back a month, or import your SMS history from Settings.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Faint)
    }
}

/** Categorising once creates a rule and back-fills every past match. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriseSheet(txn: Txn, categories: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(txn.rawMerchant.ifBlank { "Unknown merchant" },
                style = MaterialTheme.typography.titleMedium, color = Ink.Chalk)
            Spacer(Modifier.height(4.dp))
            Text("Everything matching this name gets the same category, past and future.",
                style = MaterialTheme.typography.bodyMedium, color = Ink.Faint)
            Spacer(Modifier.height(18.dp))
            FlowRowCompat {
                categories.forEach { c ->
                    Box(
                        Modifier.padding(end = 8.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colorFor(c).copy(alpha = 0.18f))
                            .clickable { onPick(c) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(c, style = MaterialTheme.typography.bodyMedium, color = colorFor(c))
                    }
                }
            }
        }
    }
}

/** Minimal wrap layout so the file has no extra dependency on the flow-layout artifact. */
@Composable
private fun FlowRowCompat(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0; var y = 0; var rowHeight = 0
        val positions = placeables.map { p ->
            if (x + p.width > constraints.maxWidth) { x = 0; y += rowHeight; rowHeight = 0 }
            val pos = x to y
            x += p.width; rowHeight = maxOf(rowHeight, p.height)
            pos
        }
        layout(constraints.maxWidth, y + rowHeight) {
            placeables.forEachIndexed { i, p -> p.place(positions[i].first, positions[i].second) }
        }
    }
}

data class MonthState(
    val label: String,
    val previousLabel: String,
    val totalPaisa: Long,
    val deltaPercent: Int?,
    val categories: List<CategoryTotal>,
    val accounts: List<AccountTotal>,
    val txns: List<Txn>,
    val uncategorised: Int
)
