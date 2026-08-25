package pk.kharcha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pk.kharcha.data.CurrencyToken
import pk.kharcha.data.Direction
import pk.kharcha.data.IgnoreRule
import pk.kharcha.data.MatchType
import pk.kharcha.data.ParseRule
import pk.kharcha.data.SenderRule
import pk.kharcha.parse.Explanation

@Composable
fun SettingsScreen(
    senders: List<SenderRule>,
    rules: List<ParseRule>,
    ignores: List<IgnoreRule>,
    currencies: List<CurrencyToken>,
    accounts: List<String>,
    testResult: Explanation?,
    importSummary: String,
    onTest: (String, String) -> Unit,
    onAddSender: (String, String) -> Unit,
    onDeleteSender: (SenderRule) -> Unit,
    onAddRule: (String?, Direction, MatchType, String) -> Unit,
    onDeleteRule: (ParseRule) -> Unit,
    onAddIgnore: (String) -> Unit,
    onDeleteIgnore: (IgnoreRule) -> Unit,
    onAddCurrency: (String) -> Unit,
    onDeleteCurrency: (CurrencyToken) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Ink.Ground)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 18.dp).padding(top = 4.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(999.dp)).clickable { onBack() },
                contentAlignment = Alignment.Center
            ) { Text("‹", color = Ink.Faint, fontSize = 20.sp) }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge, color = Ink.Chalk)
        }

        // The test box comes first because it is how you build every rule
        // below: paste a real message, read the verdict, fix, paste again.
        SectionCard {
            SectionLabel("Test a message")
            Hint("Paste a real alert and see exactly what the parser decides.")

            var testSender by remember { mutableStateOf("") }
            var testBody by remember { mutableStateOf("") }

            Field(testSender, { testSender = it }, "Sender, e.g. 8558 or a bank's short code")
            Spacer(Modifier.height(8.dp))
            Field(testBody, { testBody = it }, "Paste the full SMS text", lines = 4)
            Spacer(Modifier.height(10.dp))
            Action("Test") { onTest(testSender, testBody) }

            testResult?.let { r ->
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Ink.Sunk).padding(14.dp)
                ) {
                    Text(
                        if (r.ok) "Parsed" else "Not parsed",
                        color = if (r.ok) Ink.Jade else Ink.Clay, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Kv("Account", r.account)
                    r.direction?.let { Kv("Direction", it.name.lowercase()) }
                    r.amountPaisa?.let { Kv("Amount", it.asRupees()) }
                    if (r.merchant.isNotBlank()) Kv("Merchant", r.merchant)
                    r.firedRule?.let { Kv("Rule", it) }
                    Spacer(Modifier.height(6.dp))
                    Text(r.reason, color = Ink.Muted, fontSize = 12.sp)
                }
            }
        }

        SectionCard {
            SectionLabel("Senders")
            Hint(
                "Match a sender ID or short code to an account. Plain text, not a pattern — " +
                    "\"8558\" or \"hblpk\". Pre-filled with a few example banks — delete these " +
                    "and add your own if they don't match."
            )
            senders.forEachIndexed { index, s ->
                RowItem("${s.pattern}  →  ${s.account}") { onDeleteSender(s) }
                if (index < senders.lastIndex) HorizontalDivider(color = Ink.Line, thickness = 1.dp)
            }

            var newPattern by remember { mutableStateOf("") }
            var newAccount by remember { mutableStateOf("") }
            Spacer(Modifier.height(10.dp))
            Field(newPattern, { newPattern = it }, "Sender text or number")
            Spacer(Modifier.height(8.dp))
            Field(newAccount, { newAccount = it }, "Account name, e.g. your bank's name")
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Chips(accounts) { newAccount = it }
            }
            Spacer(Modifier.height(10.dp))
            Action("Add sender") {
                if (newPattern.isNotBlank() && newAccount.isNotBlank()) {
                    onAddSender(newPattern.trim().lowercase(), newAccount.trim())
                    newPattern = ""; newAccount = ""
                }
            }
        }

        SectionCard {
            SectionLabel("Debit and credit rules")
            Hint("A keyword matches anywhere in the message. Use regex when a keyword isn't enough — the first capture group becomes the amount.")
            rules.forEachIndexed { index, r ->
                val scope = r.account ?: "all accounts"
                RowItem("${r.direction.name.lowercase()} · $scope · ${r.value}") { onDeleteRule(r) }
                if (index < rules.lastIndex) HorizontalDivider(color = Ink.Line, thickness = 1.dp)
            }

            var ruleValue by remember { mutableStateOf("") }
            var ruleAccount by remember { mutableStateOf("") }
            var ruleDebit by remember { mutableStateOf(true) }
            var ruleRegex by remember { mutableStateOf(false) }

            Spacer(Modifier.height(10.dp))
            Field(ruleValue, { ruleValue = it }, "Keyword or regex")
            Spacer(Modifier.height(8.dp))
            Field(ruleAccount, { ruleAccount = it }, "Account, or blank for all")
            Spacer(Modifier.height(10.dp))
            Row {
                Toggle("Debit", ruleDebit) { ruleDebit = true }
                Spacer(Modifier.width(8.dp))
                Toggle("Credit", !ruleDebit) { ruleDebit = false }
                Spacer(Modifier.width(16.dp))
                Toggle("Regex", ruleRegex) { ruleRegex = !ruleRegex }
            }
            Spacer(Modifier.height(10.dp))
            Action("Add rule") {
                if (ruleValue.isNotBlank()) {
                    onAddRule(
                        ruleAccount.trim().ifBlank { null },
                        if (ruleDebit) Direction.DEBIT else Direction.CREDIT,
                        if (ruleRegex) MatchType.REGEX else MatchType.KEYWORD,
                        ruleValue.trim()
                    )
                    ruleValue = ""
                }
            }
        }

        SectionCard {
            SectionLabel("Currency")
            Hint("Symbols or codes the parser looks for next to a number, e.g. $ or USD.")
            currencies.forEachIndexed { index, c ->
                RowItem(c.token) { onDeleteCurrency(c) }
                if (index < currencies.lastIndex) HorizontalDivider(color = Ink.Line, thickness = 1.dp)
            }

            var newCurrency by remember { mutableStateOf("") }
            Spacer(Modifier.height(10.dp))
            Field(newCurrency, { newCurrency = it }, "Symbol or code, e.g. USD")
            Spacer(Modifier.height(10.dp))
            Action("Add currency") {
                if (newCurrency.isNotBlank()) {
                    onAddCurrency(newCurrency.trim()); newCurrency = ""
                }
            }
        }

        SectionCard {
            SectionLabel("Ignore these")
            Hint("Messages containing any of these are skipped entirely.")
            ignores.forEachIndexed { index, i ->
                RowItem(i.phrase) { onDeleteIgnore(i) }
                if (index < ignores.lastIndex) HorizontalDivider(color = Ink.Line, thickness = 1.dp)
            }

            var newIgnore by remember { mutableStateOf("") }
            Spacer(Modifier.height(10.dp))
            Field(newIgnore, { newIgnore = it }, "Phrase to ignore")
            Spacer(Modifier.height(10.dp))
            Action("Add phrase") {
                if (newIgnore.isNotBlank()) {
                    onAddIgnore(newIgnore.trim().lowercase()); newIgnore = ""
                }
            }
        }

        SectionCard {
            SectionLabel("Rescan")
            Hint("Clears imported transactions and reads the inbox again with the current rules. Your categories are kept and reapplied.")
            Spacer(Modifier.height(4.dp))
            Action("Clear and rescan inbox", accent = true) { onRescan() }

            if (importSummary.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(Ink.Sunk).padding(13.dp)
                ) {
                    Text(
                        importSummary, color = Ink.Muted, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace, lineHeight = 17.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(t: String) {
    Text(t, style = MaterialTheme.typography.labelSmall, color = Ink.Faint)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Hint(t: String) {
    Text(t, color = Ink.Ghost, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = Ink.Faint, fontSize = 12.sp, modifier = Modifier.width(88.dp))
        Text(v, color = Ink.Chalk, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RowItem(label: String, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ink.Chalk2, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            "Remove", color = Ink.Clay, fontSize = 12.sp,
            modifier = Modifier.clickable { onDelete() }
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, lines: Int = 1) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, color = Ink.Ghost, fontSize = 13.sp) },
        singleLine = lines == 1,
        minLines = lines,
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

@Composable
private fun Action(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (accent) Ink.Marigold else Ink.Surface)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp)
    ) {
        Text(label, color = if (accent) Ink.Sunk else Ink.Chalk, fontSize = 13.sp)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp))
            .background(if (on) Ink.Marigold.copy(alpha = 0.2f) else Ink.Surface)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = if (on) Ink.Marigold else Ink.Faint, fontSize = 12.sp)
    }
}

@Composable
private fun Chips(items: List<String>, onPick: (String) -> Unit) {
    // Wraps rather than scrolling — a horizontal scroller would hide accounts
    // off the right edge with no hint they were there.
    FlowRowCompat(Modifier.fillMaxWidth()) {
        items.forEach {
            Box(
                Modifier.padding(end = 8.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Ink.Surface).clickable { onPick(it) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) { Text(it, color = Ink.Muted, fontSize = 12.sp) }
        }
    }
}
