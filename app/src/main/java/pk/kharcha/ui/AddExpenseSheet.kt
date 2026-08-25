package pk.kharcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.kharcha.data.Direction

/**
 * Cash, or anything else that never sends an SMS. Writes straight into the
 * same txn table as the parser, so it shows up in totals and the spine like
 * anything else — no separate "manual" bucket to reconcile later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    categories: List<String>,
    accounts: List<String>,
    onAdd: (Direction, Long, String, String?, String) -> Unit,
    onDismiss: () -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("Cash") }
    var category by remember { mutableStateOf<String?>(null) }
    var direction by remember { mutableStateOf(Direction.DEBIT) }

    val amountPaisa = parsePaisa(amountText)
    val canSave = amountPaisa != null && amountPaisa > 0

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Ink.Surface) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("Add expense", style = MaterialTheme.typography.titleMedium, color = Ink.Chalk)
            Spacer(Modifier.height(4.dp))
            Text(
                "For cash, or anything that never sends an SMS.",
                style = MaterialTheme.typography.labelSmall, color = Ink.Faint
            )

            Spacer(Modifier.height(18.dp))
            Row {
                DirectionToggle("Spent", direction == Direction.DEBIT) { direction = Direction.DEBIT }
                Spacer(Modifier.width(8.dp))
                DirectionToggle("Received", direction == Direction.CREDIT) { direction = Direction.CREDIT }
            }

            Spacer(Modifier.height(14.dp))
            SheetField(
                amountText, { amountText = it }, "Amount",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )

            Spacer(Modifier.height(10.dp))
            SheetField(merchant, { merchant = it }, "What was it for?")

            Spacer(Modifier.height(10.dp))
            SheetField(account, { account = it }, "Account, e.g. Cash")
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRowCompat {
                    accounts.forEach { a ->
                        Box(
                            Modifier.padding(end = 8.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Ink.Sunk).clickable { account = a }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) { Text(a, color = Ink.Muted, fontSize = 12.sp) }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Category", style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
            Spacer(Modifier.height(10.dp))
            FlowRowCompat {
                categories.forEach { c ->
                    val selected = c == category
                    Box(
                        Modifier.padding(end = 8.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colorFor(c).copy(alpha = if (selected) 0.34f else 0.15f))
                            .clickable { category = c }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(c, style = MaterialTheme.typography.bodyMedium, color = colorFor(c))
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (canSave) Ink.Marigold else Ink.Surface)
                    .clickable(enabled = canSave) {
                        onAdd(direction, amountPaisa!!, merchant, category, account)
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Save",
                    color = if (canSave) Ink.Sunk else Ink.Ghost,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun DirectionToggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) Ink.Marigold.copy(alpha = 0.2f) else Ink.Sunk)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (on) Ink.Marigold else Ink.Faint, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Integer paisa, never Double: float rounding loses rupees over time. */
private fun parsePaisa(raw: String): Long? = runCatching {
    val parts = raw.replace(",", "").trim().split(".")
    val whole = parts[0].toLong()
    val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLong() ?: 0L
    whole * 100 + fraction
}.getOrNull()

@Composable
private fun SheetField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, color = Ink.Ghost) },
        singleLine = true,
        keyboardOptions = keyboardOptions,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink.Chalk,
            unfocusedTextColor = Ink.Chalk,
            focusedBorderColor = Ink.Marigold,
            unfocusedBorderColor = Ink.LineStrong,
            cursorColor = Ink.Marigold
        ),
        modifier = Modifier.fillMaxWidth()
    )
}
