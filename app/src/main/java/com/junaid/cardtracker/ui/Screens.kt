package com.junaid.cardtracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.junaid.cardtracker.data.CardEntity
import com.junaid.cardtracker.data.CardTypes
import com.junaid.cardtracker.data.SmsEntity
import com.junaid.cardtracker.data.TransactionEntity
import com.junaid.cardtracker.parser.CardType
import com.junaid.cardtracker.parser.Money
import com.junaid.cardtracker.parser.TxnType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- formatting

private val moneyFmt = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ENGLISH))
fun fmtMoney(minor: Long, currency: String = "AED") = "$currency ${moneyFmt.format(Money.fromMinor(minor))}"

private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
fun fmtDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
fun fmtEpochDay(day: Long): String = LocalDate.ofEpochDay(day).format(dateFmt)

private fun typeLabel(t: String) = if (t == CardTypes.DEBIT) "Debit" else "Credit"

// ---------------------------------------------------------- transactions tab

@Composable
fun TransactionsScreen(
    vm: MainViewModel,
    month: YearMonth?,
    cardFilter: String?,
    cards: List<CardEntity>,
    excluded: Set<String>,
    txns: List<TransactionEntity>,
    syncing: Boolean,
    lastSyncAt: Long?,
    liveOn: Boolean,
    onSync: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val submit: () -> Unit = {
        if (input.isNotBlank() && vm.addManual(input)) {
            input = ""
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onSync, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (syncing) "Syncing…" else "Sync SMS")
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (lastSyncAt == null) "Never synced" else "Last sync ${fmtDateTime(lastSyncAt)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (liveOn) "Live listening on" else "Live listening off",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Add by typing") },
                    placeholder = { Text("lunch 45 aed") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                IconButton(onClick = { submit() }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                TextButton(onClick = vm::toggleAllMonths, modifier = Modifier.weight(1f)) {
                    Text(month?.format(monthFmt) ?: "All months", style = MaterialTheme.typography.titleMedium)
                }
                IconButton(onClick = vm::nextMonth) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = cardFilter == null, onClick = { vm.selectCard(null) }, label = { Text("All cards") }) }
                items(cards, key = { it.cardKey }) { c ->
                    FilterChip(selected = cardFilter == c.cardKey, onClick = { vm.selectCard(c.cardKey) }, label = { Text(c.cardKey) })
                }
            }
        }
        item {
            val spend = spendingTotal(txns, excluded)
            val payments = txns.filter { it.type == TxnType.PAYMENT.name }.sumOf { it.amountAedMinor ?: 0L }
            val notCounted = txns.count { it.cardKey != null && it.cardKey in excluded }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Spent ${fmtMoney(spend)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val extra = mutableListOf("${txns.size} transactions")
                if (payments > 0) extra += "card payments ${fmtMoney(payments)}"
                if (notCounted > 0) extra += "$notCounted not counted"
                Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
        if (txns.isEmpty()) {
            item {
                Text(
                    if (lastSyncAt == null) "Tap Sync SMS to import your bank messages, or add one above." else "No transactions for this filter.",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(txns, key = { it.id }) { t ->
            TransactionRow(vm, t, counted = t.cardKey == null || t.cardKey !in excluded)
        }
    }
}

@Composable
private fun TransactionRow(vm: MainViewModel, t: TransactionEntity, counted: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) { if (expanded && raw == null && t.smsId != null) raw = vm.rawSms(t.smsId) }

    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.merchant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val card = t.cardKey ?: if (t.cardLast4 != null) "${t.bank} ·${t.cardLast4}" else t.bank
                val tags = mutableListOf(card, fmtDateTime(t.timestamp))
                if (t.type == TxnType.REFUND.name) tags += "refund"
                if (t.type == TxnType.PAYMENT.name) tags += "card payment"
                if (t.source == "MANUAL") tags += "typed"
                if (!counted) tags += "not counted"
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                val sign = if (t.type == TxnType.PURCHASE.name) "" else "+"
                val color = when {
                    !counted -> MaterialTheme.colorScheme.onSurfaceVariant
                    t.type == TxnType.PURCHASE.name -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.primary
                }
                Text(sign + fmtMoney(t.amountMinor, t.currency), color = color, fontWeight = FontWeight.Medium)
                if (t.currency != "AED") {
                    Text(
                        t.amountAedMinor?.let { "≈ " + fmtMoney(it) } ?: "no AED rate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            t.availableLimitMinor?.let { Text("Available limit/balance after: ${fmtMoney(it)}", style = MaterialTheme.typography.bodySmall) }
            t.ruleId?.let { Text("Rule: $it", style = MaterialTheme.typography.bodySmall) }
            raw?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                }
            }
            TextButton(onClick = { vm.deleteTransaction(t) }) { Text("Delete") }
        }
    }
    HorizontalDivider()
}

// ----------------------------------------------------------------- cards tab

@Composable
fun CardsScreen(
    summaries: List<CardSummary>,
    month: YearMonth?,
    onOpenCard: (String) -> Unit,
    onToggleCounted: (String, Boolean) -> Unit,
) {
    val monthLabel = (month ?: YearMonth.now()).format(monthFmt)
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (summaries.isEmpty()) {
            item { Text("Cards appear here after your first Sync.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(summaries, key = { it.card.cardKey }) { s ->
            Card(Modifier.fillMaxWidth().clickable { onOpenCard(s.card.cardKey) }) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.card.cardKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${typeLabel(s.card.cardType)} card",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Switch(checked = s.card.countInSpending, onCheckedChange = { onToggleCounted(s.card.cardKey, it) })
                            Text("Count in spending", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val suffix = if (s.card.countInSpending) "" else " (not in totals)"
                    Text("$monthLabel: ${fmtMoney(s.monthSpendAedMinor)} · ${s.monthTxnCount} txns$suffix")
                    StatementLines(s)
                }
            }
        }
    }
}

@Composable
private fun StatementLines(s: CardSummary) {
    val st = s.latestStatement ?: return
    Spacer(Modifier.height(8.dp))
    Text("Statement balance ${fmtMoney(st.balanceMinor, st.currency)}", style = MaterialTheme.typography.bodyMedium)
    st.minimumDueMinor?.let { Text("Minimum due ${fmtMoney(it, st.currency)}", style = MaterialTheme.typography.bodyMedium) }
    val overdue = LocalDate.ofEpochDay(st.dueDateEpochDay).isBefore(LocalDate.now())
    Text(
        "Due ${fmtEpochDay(st.dueDateEpochDay)}" + if (overdue) " (passed)" else "",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = if (overdue) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
    )
}

/** Phase 2 extends this into the full card profile. */
@Composable
fun CardDetailScreen(
    summary: CardSummary?,
    onSetType: (CardType) -> Unit,
    onToggleCounted: (Boolean) -> Unit,
    onShowTransactions: () -> Unit,
) {
    if (summary == null) {
        Text("Card not found.", Modifier.padding(24.dp))
        return
    }
    val c = summary.card
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(c.cardKey, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Card type", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = c.cardType == CardTypes.CREDIT, onClick = { onSetType(CardType.CREDIT) }, label = { Text("Credit") })
            FilterChip(selected = c.cardType == CardTypes.DEBIT, onClick = { onSetType(CardType.DEBIT) }, label = { Text("Debit") })
        }
        Text(
            "Changing the type resets \"Count in spending\" to its default (on for credit, off for debit).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Count in spending", style = MaterialTheme.typography.titleSmall)
                Text(
                    "When off, this card's transactions stay in the list but are left out of all totals and charts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = c.countInSpending, onCheckedChange = onToggleCounted)
        }
        HorizontalDivider()
        Text("This month: ${fmtMoney(summary.monthSpendAedMinor)} · ${summary.monthTxnCount} txns")
        StatementLines(summary)
        OutlinedButton(onClick = onShowTransactions) { Text("Show transactions") }
    }
}

// ---------------------------------------------------------------- review tab

@Composable
fun ReviewScreen(failed: List<SmsEntity>, counts: Map<String, Int>, onDismiss: (Long) -> Unit, onReparse: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Column {
                Text(
                    "Bank SMS stored: ${counts.values.sum()} · transactions ${counts["TRANSACTION"] ?: 0} · " +
                        "statements ${counts["STATEMENT"] ?: 0} · ignored ${counts["IGNORED"] ?: 0} · dismissed ${counts["DISMISSED"] ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "These bank SMS contain an amount but no rule matched. Add a rule in BankRules.kt, install the new build, then tap Re-parse.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onReparse, modifier = Modifier.padding(top = 8.dp)) { Text("Re-parse all SMS") }
            }
        }
        if (failed.isEmpty()) item { Text("Nothing to review.", fontWeight = FontWeight.Medium) }
        items(failed, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Text("${s.bank ?: s.sender} · ${fmtDateTime(s.receivedAt)}", style = MaterialTheme.typography.titleSmall)
                    s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.height(6.dp))
                    SelectionContainer { Text(s.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { onDismiss(s.id) }) { Text("Dismiss") }
                }
            }
        }
    }
}

// -------------------------------------------------------------- settings tab

@Composable
fun SettingsScreen(
    liveOn: Boolean,
    lastSyncAt: Long?,
    onLiveToggle: (Boolean) -> Unit,
    onShowSamsungTip: () -> Unit,
    onResetSync: () -> Unit,
    onReparse: () -> Unit,
    versionName: String,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Live listening", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Capture bank SMS the moment they arrive. When off, nothing runs in the background: use Sync instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = liveOn, onCheckedChange = onLiveToggle)
        }
        if (liveOn) {
            TextButton(onClick = onShowSamsungTip) { Text("Samsung: keep the app from sleeping") }
        }
        HorizontalDivider()

        Text("Sync", style = MaterialTheme.typography.titleMedium)
        Text(
            if (lastSyncAt == null) "Never synced. The first Sync imports all existing bank SMS."
            else "Last successful sync: ${fmtDateTime(lastSyncAt)}. The next Sync reads from there onward.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = onResetSync) { Text("Re-read whole inbox on next Sync") }
        OutlinedButton(onClick = onReparse) { Text("Re-parse all stored SMS") }
        HorizontalDivider()

        Text(
            "Card Tracker $versionName · all data stays on this phone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
