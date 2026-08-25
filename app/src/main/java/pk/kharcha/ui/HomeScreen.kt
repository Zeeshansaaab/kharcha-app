package pk.kharcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.kharcha.data.AccountTotal
import pk.kharcha.data.CategoryTotal
import pk.kharcha.data.Direction
import pk.kharcha.data.Txn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    state: MonthState,
    onMonthStep: (Int) -> Unit,
    onCategorise: (Txn) -> Unit,
    onOpenSettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Ink.Ground),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { MonthHeader(state, onMonthStep, onOpenSettings) }

        if (state.totalPaisa > 0L) {
            item {
                SectionCard(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Spine(state.categories, state.totalPaisa)
                    Legend(state.categories)
                }
            }
        }

        if (state.accounts.isNotEmpty()) {
            item {
                SectionCard(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    Accounts(state.accounts, state.accounts.maxOfOrNull { it.totalPaisa } ?: 1L)
                }
            }
        }

        item { DayHeader(state.uncategorised) }

        itemsIndexed(state.txns, key = { _, t -> t.id }) { index, txn ->
            TxnRow(txn, onCategorise)
            if (index < state.txns.lastIndex) {
                HorizontalDivider(
                    color = Ink.Line, thickness = 1.dp,
                    modifier = Modifier.padding(start = 67.dp, end = 18.dp)
                )
            }
        }

        if (state.txns.isEmpty()) item { EmptyMonth(onOpenSettings) }
    }
}

@Composable
private fun MonthHeader(state: MonthState, onStep: (Int) -> Unit, onSettings: () -> Unit) {
    Column(Modifier.padding(start = 18.dp, end = 8.dp, top = 16.dp, bottom = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                state.label,
                style = MaterialTheme.typography.titleLarge,
                color = Ink.Chalk,
                modifier = Modifier.weight(1f)
            )
            // 44dp touch targets: small padded glyphs were awkward one-handed.
            IconGlyph("‹") { onStep(-1) }
            IconGlyph("›") { onStep(1) }
            IconGlyph("⚙", size = 17) { onSettings() }
        }
        Spacer(Modifier.height(20.dp))
        Text("Spent", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 10.dp)) {
            Text(
                state.totalPaisa.asRupees(),
                style = MaterialTheme.typography.displayLarge,
                color = Ink.Chalk
            )
            state.deltaPercent?.let {
                Spacer(Modifier.width(10.dp))
                DeltaPill(it, state.previousLabel)
            }
        }
    }
}

@Composable
private fun DeltaPill(percent: Int, previousLabel: String) {
    val up = percent >= 0
    val tone = if (up) Ink.Marigold else Ink.Jade
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tone.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            (if (up) "+$percent%" else "$percent%") + " vs ${previousLabel}",
            style = MaterialTheme.typography.bodyMedium,
            color = tone
        )
    }
}

@Composable
private fun IconGlyph(glyph: String, size: Int = 20, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, color = Ink.Faint, fontSize = size.sp)
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
        Modifier.fillMaxWidth().height(14.dp),
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
    if (categories.isEmpty()) return
    FlowRowCompat(Modifier.padding(top = 14.dp)) {
        categories.take(5).forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 14.dp, bottom = 6.dp)
            ) {
                Box(
                    Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                        .background(colorFor(c.category))
                )
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
    if (accounts.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text("Accounts", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        Spacer(Modifier.height(12.dp))
        accounts.forEach { a ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 5.dp)
            ) {
                Text(
                    a.account, style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Chalk2, modifier = Modifier.width(78.dp)
                )
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
                Text(
                    a.totalPaisa.asRupees(), fontFamily = Numeral,
                    style = MaterialTheme.typography.bodyMedium, color = Ink.Muted
                )
            }
        }
    }
}

@Composable
private fun DayHeader(uncategorised: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Transactions", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
        Spacer(Modifier.weight(1f))
        if (uncategorised > 0) {
            Text(
                "$uncategorised need a category",
                style = MaterialTheme.typography.labelSmall, color = Ink.Marigold
            )
        }
    }
}

private val dayFmt = SimpleDateFormat("d MMM", Locale.US)
private val fullDateFmt = SimpleDateFormat("d MMM, h:mm a", Locale.US)

@Composable
private fun TxnRow(txn: Txn, onCategorise: (Txn) -> Unit) {
    val dimmed = txn.isTransfer
    Row(
        Modifier.fillMaxWidth()
            .clickable { onCategorise(txn) }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                .background(if (dimmed) Ink.Surface else colorFor(txn.category).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                txn.merchant.ifBlank { txn.rawMerchant }.trim().firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.bodyMedium,
                color = if (dimmed) Ink.Ghost else colorFor(txn.category)
            )
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                txn.merchant.ifBlank { "Unknown" }.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                color = if (dimmed) Ink.Ghost else Ink.Chalk,
                textDecoration = if (dimmed) TextDecoration.LineThrough else null
            )
            Spacer(Modifier.height(2.dp))
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
        Spacer(Modifier.width(8.dp))
        Text(
            txn.amountPaisa.asRupees(), fontFamily = Numeral,
            style = MaterialTheme.typography.bodyLarge,
            color = if (dimmed) Ink.Ghost else Ink.Chalk
        )
    }
}

@Composable
private fun EmptyMonth(onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Nothing here for this month",
            style = MaterialTheme.typography.bodyLarge, color = Ink.Chalk2
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Step back a month, or check which senders and rules are matching.",
            style = MaterialTheme.typography.bodyMedium, color = Ink.Faint
        )
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.clip(RoundedCornerShape(999.dp)).background(Ink.Surface)
                .clickable { onOpenSettings() }
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text("Open settings", color = Ink.Chalk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * Tapping a transaction opens this. Two jobs: assign a category, and show the
 * message the row came from, so a mis-parse is visible rather than silently
 * sitting in the month total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriseSheet(
    txn: Txn,
    categories: List<String>,
    explanation: String?,
    onPick: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    txn.rawMerchant.ifBlank { "Unknown merchant" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.Chalk,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    txn.amountPaisa.asRupees(),
                    fontFamily = Numeral,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (txn.direction == Direction.CREDIT) Ink.Jade else Ink.Chalk
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${txn.direction.name.lowercase()} · ${txn.account} · " +
                    "${fullDateFmt.format(Date(txn.timestamp))} · via ${txn.source}",
                style = MaterialTheme.typography.labelSmall, color = Ink.Faint
            )

            if (txn.source != "manual") {
                Spacer(Modifier.height(20.dp))
                Text("Original message", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Ink.Sunk).padding(13.dp)
                ) {
                    Text(
                        txn.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.Chalk2,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "From ${txn.sender}",
                        style = MaterialTheme.typography.labelSmall, color = Ink.Ghost
                    )
                    explanation?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.Muted)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("Category", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
            Spacer(Modifier.height(4.dp))
            Text(
                "Applies to every past and future message from this merchant.",
                style = MaterialTheme.typography.labelSmall, color = Ink.Ghost
            )
            Spacer(Modifier.height(14.dp))
            FlowRowCompat {
                categories.forEach { c ->
                    val selected = c == txn.category
                    Box(
                        Modifier.padding(end = 8.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colorFor(c).copy(alpha = if (selected) 0.34f else 0.15f))
                            .clickable { onPick(c) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(c, style = MaterialTheme.typography.bodyMedium, color = colorFor(c))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            // Two taps, because deleting is the one irreversible action here.
            if (!confirmDelete) {
                Text(
                    "This isn't a real transaction",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.Clay,
                    modifier = Modifier.clickable { confirmDelete = true }.padding(vertical = 6.dp)
                )
            } else {
                Text(
                    "Removing it here won't stop it coming back on the next rescan — " +
                        "fix the rule in Settings for that.",
                    style = MaterialTheme.typography.labelSmall, color = Ink.Muted
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(Ink.Clay)
                            .clickable { onDelete() }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text("Remove", color = Ink.Chalk, fontSize = 13.sp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp))
                            .clickable { confirmDelete = false }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) { Text("Cancel", color = Ink.Faint, fontSize = 13.sp) }
                }
            }
        }
    }
}

/** Minimal wrapping layout, so there's no extra dependency for one helper. */
@Composable
fun FlowRowCompat(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = placeables.map { p ->
            if (x + p.width > constraints.maxWidth) {
                x = 0; y += rowHeight; rowHeight = 0
            }
            val pos = x to y
            x += p.width
            rowHeight = maxOf(rowHeight, p.height)
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
