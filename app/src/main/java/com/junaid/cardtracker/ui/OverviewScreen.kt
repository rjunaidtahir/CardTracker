package com.junaid.cardtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junaid.cardtracker.core.DueState
import com.junaid.cardtracker.data.CardDue
import com.junaid.cardtracker.data.CategoryEntity
import com.junaid.cardtracker.data.FxRateEntity
import com.junaid.cardtracker.data.GoalEntity
import com.junaid.cardtracker.parser.Money
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val shortMonth = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

// ================================================================ Overview (Phases 2-4 dashboard)

@Composable
fun OverviewScreen(
    vm: MainViewModel,
    onOpenCategory: (Long?) -> Unit,
    onOpenCard: (String) -> Unit,
) {
    val o by vm.overview.collectAsStateWithLifecycle()
    val dues by vm.dues.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val names = categories.associate { it.id to it.name }
    val cardNames = cards.associate { it.cardKey to (it.nickname ?: it.cardKey) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---- month + hero number
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::previousMonth) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous month") }
                Text(o.month.format(monthFmt), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = vm::nextMonth) { Icon(Icons.Filled.KeyboardArrowRight, "Next month") }
            }
            Text("Spent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(fmtMoney(o.spentMinor), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            if (o.previousMonthMinor > 0) {
                val diff = o.spentMinor - o.previousMonthMinor
                val pct = (kotlin.math.abs(diff) * 100 / o.previousMonthMinor)
                Text(
                    (if (diff >= 0) "$pct% more" else "$pct% less") + " than ${o.month.minusMonths(1).format(monthFmt)} (${fmtMoney(o.previousMonthMinor)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- upcoming dues
        val open = dues.filter { it.status.state == DueState.UNPAID || it.status.state == DueState.OVERDUE || it.status.state == DueState.MIN_PAID }
        item { SectionTitle("Card payments due") }
        if (open.isEmpty()) {
            item { Muted(if (dues.isEmpty()) "No statement SMS yet." else "All statements paid.") }
        }
        items(open, key = { "due-" + it.card.cardKey }) { d -> DueRow(d) { onOpenCard(d.card.cardKey) } }

        // ---- by category
        item { SectionTitle("Spending by category") }
        if (o.byCategory.isEmpty()) item { Muted("No spending this month.") }
        val catMax = o.byCategory.maxOfOrNull { it.second } ?: 1L
        items(o.byCategory, key = { "cat-" + it.first }) { (id, amount) ->
            BarRow(
                label = id?.let { names[it] } ?: "Uncategorised",
                value = fmtMoney(amount),
                share = if (o.spentMinor > 0) "${amount * 100 / o.spentMinor}%" else "",
                fraction = amount.toFloat() / catMax,
                onClick = { onOpenCategory(id) },
            )
        }

        // ---- 12-month history
        item {
            SectionTitle("Last 12 months")
            MonthBars(o.history.map { it.month to it.amountMinor }, selected = o.month, onSelect = { vm.selectMonth(it) })
        }

        // ---- by card
        if (o.byCard.isNotEmpty()) {
            item { SectionTitle("By card") }
            val cardMax = o.byCard.maxOf { it.amountMinor }
            items(o.byCard, key = { "card-" + it.key }) { s ->
                BarRow(cardNames[s.key] ?: s.key, fmtMoney(s.amountMinor), "", s.amountMinor.toFloat() / cardMax, onClick = null)
            }
        }

        // ---- recurring
        item { SectionTitle("Recurring payments") }
        if (o.recurring.isEmpty()) item { Muted("None detected yet (needs 3+ roughly monthly charges).") }
        items(o.recurring, key = { "rec-${it.merchantKey}|${it.cardKey}|${it.averageMinor}|${it.lastDate}" }) { r ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(r.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${r.occurrences}× · next about ${r.nextExpected.format(dateFmt)}" + (r.cardKey?.let { " · " + (cardNames[it] ?: it) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(fmtMoney(r.averageMinor), fontWeight = FontWeight.Medium)
            }
        }

        // ---- currencies
        if (o.byCurrency.isNotEmpty()) {
            item { SectionTitle("Foreign currency spending (in AED)") }
            items(o.byCurrency, key = { "cur-" + it.currency }) { c ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${fmtMoney(c.originalMinor, c.currency)} · ${c.count} txns", modifier = Modifier.weight(1f))
                    Text("≈ " + fmtMoney(c.aedMinor), fontWeight = FontWeight.Medium)
                }
            }
        }

        // ---- goals
        item { GoalsSection(goals, onSave = { vm.saveGoal(it) }, onDelete = { vm.deleteGoal(it) }) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DueRow(d: CardDue, onClick: () -> Unit) {
    val attention = d.status.state == DueState.UNPAID || d.status.state == DueState.OVERDUE
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.label, fontWeight = FontWeight.Medium)
                Text(
                    "Due ${fmtEpochDay(d.statement.dueDateEpochDay)}" + (d.statement.minimumDueMinor?.let { " · min ${fmtMoney(it, d.statement.currency)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    dueStateText(d),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
            Text(fmtMoney(d.status.remainingMinor, d.statement.currency), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** One horizontal bar: label and value in text colours, the bar itself in a single hue (magnitude only). */
@Composable
private fun BarRow(label: String, value: String, share: String, fraction: Float, onClick: (() -> Unit)?) {
    val m = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Column(m.fillMaxWidth().padding(vertical = 2.dp)) {
        Row {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (share.isNotEmpty()) Text(share, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
            Text(value, fontWeight = FontWeight.Medium)
        }
        MeterBar(fraction)
    }
}

/** Vertical bars for monthly totals; tap a bar to pick that month. The selected month is labelled. */
@Composable
private fun MonthBars(data: List<Pair<YearMonth, Long>>, selected: YearMonth, onSelect: (YearMonth) -> Unit) {
    val max = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val sel = data.firstOrNull { it.first == selected }
    Column {
        Text(
            sel?.let { "${it.first.format(monthFmt)}: ${fmtMoney(it.second)}" } ?: "Tap a month",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            data.forEach { (m, v) ->
                val isSel = m == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onSelect(m) },
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val frac = (v.toFloat() / max).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(if (v > 0) (0.02f + 0.78f * frac) else 0.01f)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    )
                    Text(
                        m.format(shortMonth).take(1),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ================================================================ savings goals (Phase 4)

@Composable
private fun GoalsSection(goals: List<GoalEntity>, onSave: (GoalEntity) -> Unit, onDelete: (Long) -> Unit) {
    var editing by remember { mutableStateOf<GoalEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var contributing by remember { mutableStateOf<GoalEntity?>(null) }

    if (adding || editing != null) {
        GoalDialog(editing, onSave = { onSave(it); adding = false; editing = null }, onDismiss = { adding = false; editing = null })
    }
    contributing?.let { g ->
        AmountDialog(
            title = "Add to \"${g.name}\"",
            onDone = { minor -> onSave(g.copy(savedMinor = (g.savedMinor + minor).coerceAtLeast(0))); contributing = null },
            onDismiss = { contributing = null },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Savings goals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("Add goal") }
        }
        if (goals.isEmpty()) Muted("Set a target and log what you put aside.")
        goals.forEach { g ->
            val frac = if (g.targetMinor > 0) g.savedMinor.toFloat() / g.targetMinor else 0f
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Text(g.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${(frac * 100).toInt()}%", fontWeight = FontWeight.Medium)
                    }
                    MeterBar(frac)
                    val remaining = (g.targetMinor - g.savedMinor).coerceAtLeast(0)
                    val perMonth = g.targetDateEpochDay?.let { d ->
                        val months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.now(), YearMonth.from(LocalDate.ofEpochDay(d))).coerceAtLeast(1)
                        " · about ${fmtMoney(remaining / months)} a month to reach it by ${fmtEpochDay(d)}"
                    } ?: ""
                    Text(
                        "${fmtMoney(g.savedMinor)} of ${fmtMoney(g.targetMinor)}$perMonth",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { contributing = g }) { Text("Add money") }
                        TextButton(onClick = { editing = g }) { Text("Edit") }
                        TextButton(onClick = { onDelete(g.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalDialog(existing: GoalEntity?, onSave: (GoalEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var target by remember { mutableStateOf(existing?.targetMinor?.let { Money.fromMinor(it).toPlainString() } ?: "") }
    var saved by remember { mutableStateOf(existing?.savedMinor?.let { Money.fromMinor(it).toPlainString() } ?: "0") }
    var date by remember { mutableStateOf(existing?.targetDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New savings goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(target, { target = it }, label = { Text("Target (AED)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(saved, { saved = it }, label = { Text("Saved so far (AED)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(date, { date = it }, label = { Text("Target date (YYYY-MM-DD, optional)") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = target.replace(",", "").toBigDecimalOrNull()
                val s = saved.replace(",", "").ifBlank { "0" }.toBigDecimalOrNull()
                val d = date.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                when {
                    name.isBlank() -> error = "Give it a name"
                    t == null || t.signum() <= 0 -> error = "Enter a target amount"
                    s == null -> error = "Saved amount isn't a number"
                    date.isNotBlank() && d == null -> error = "Date must look like 2027-06-30"
                    else -> onSave(
                        (existing ?: GoalEntity(name = "", targetMinor = 0, savedMinor = 0, targetDateEpochDay = null, createdAt = System.currentTimeMillis()))
                            .copy(name = name.trim(), targetMinor = Money.toMinor(t), savedMinor = Money.toMinor(s), targetDateEpochDay = d?.toEpochDay()),
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AmountDialog(title: String, onDone: (Long) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                text, { text = it }, label = { Text("Amount (AED, use - to take out)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
        confirmButton = {
            TextButton(onClick = { text.replace(",", "").trim().toBigDecimalOrNull()?.let { onDone(Money.toMinor(it)) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ category picker (Phase 3 learning)

@Composable
fun CategoryPickerDialog(
    merchant: String,
    current: Long?,
    categories: List<CategoryEntity>,
    onPick: (Long, Boolean) -> Unit,
    onCreate: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    var applyAll by remember { mutableStateOf(true) }
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            LazyColumn(Modifier.height(420.dp)) {
                items(categories, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { selected = c.id }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected == c.id, onClick = { selected = c.id })
                        Text(c.name)
                    }
                }
                item {
                    OutlinedTextField(newName, { newName = it }, label = { Text("…or a new category") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                        Text("Apply to all \"${merchant.take(28)}\" (past and future)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sel = selected
                when {
                    newName.isNotBlank() -> onCreate(newName, applyAll)
                    sel != null -> onPick(sel, applyAll)
                    else -> onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ exchange rates (Phase 4)

@Composable
fun RatesScreen(rates: List<FxRateEntity>, onSave: (String, String) -> Unit) {
    var newCur by rememberSaveable { mutableStateOf("") }
    var newRate by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "1 unit of each currency in AED. Changing a rate recalculates the AED amount of every past transaction in that currency.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rates.filter { it.currency != "AED" }, key = { it.currency }) { r ->
            var text by rememberSaveable(r.currency, r.rateToAed) { mutableStateOf(r.rateToAed) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.currency, modifier = Modifier.width(56.dp), fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    text, { text = it }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(onClick = { onSave(r.currency, text) }, enabled = text != r.rateToAed) { Text("Save") }
            }
        }
        item {
            HorizontalDivider()
            Text("Add a currency", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newCur, { newCur = it.uppercase().take(3) }, label = { Text("Code") }, singleLine = true, modifier = Modifier.width(96.dp))
                OutlinedTextField(
                    newRate, { newRate = it }, label = { Text("AED per unit") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                TextButton(onClick = { if (newCur.length == 3) { onSave(newCur, newRate); newCur = ""; newRate = "" } }) { Text("Add") }
            }
        }
    }
}

// ================================================================ app lock (Phase 2)

@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("PIN (4–8 digits)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                OutlinedTextField(
                    again, { again = it.filter(Char::isDigit).take(8) }, label = { Text("Repeat PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                error = when {
                    pin.length < 4 -> "Use at least 4 digits"
                    pin != again -> "The PINs don't match"
                    else -> { onSet(pin); null }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Wraps the whole UI: shows the lock screen while locked. */
@Composable
fun AppLockGate(vm: MainViewModel, onBiometric: (() -> Unit)?, content: @Composable () -> Unit) {
    val locked by vm.locked.collectAsStateWithLifecycle()
    if (!locked) {
        content()
        return
    }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onBiometric?.invoke() }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null)
            Spacer(Modifier.height(12.dp))
            Text("Card Tracker is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                pin, { pin = it.filter(Char::isDigit).take(8); error = null }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (!vm.unlockWithPin(pin)) { error = "Wrong PIN"; pin = "" } }) { Text("Unlock") }
            if (onBiometric != null) {
                OutlinedButton(onClick = onBiometric, modifier = Modifier.padding(top = 8.dp)) { Text("Use fingerprint / face") }
            }
        }
    }
}
