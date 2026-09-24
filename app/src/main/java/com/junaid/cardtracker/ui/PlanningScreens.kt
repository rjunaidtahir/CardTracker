package com.junaid.cardtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junaid.cardtracker.core.BudgetStatus
import com.junaid.cardtracker.core.FixedSchedule
import com.junaid.cardtracker.data.CardEntity
import com.junaid.cardtracker.data.CategoryEntity
import com.junaid.cardtracker.data.FixedPaymentEntity
import com.junaid.cardtracker.parser.Money
import java.time.LocalDate
import java.time.YearMonth

private fun budgetColor(pct: Int) = when {
    pct >= 100 -> Ink.red
    pct >= 80 -> Ink.amber
    else -> Ink.green
}

// ================================================================ budgets

/** Overview panel: this month's budgets with progress bars. */
@Composable
fun BudgetsPanel(status: List<BudgetStatus>, names: Map<Long, String>, onEdit: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Budgets", style = MaterialTheme.typography.titleMedium)
                Text(YearMonth.now().format(monthFmt), style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            }
            TextButton(onClick = onEdit) { Text(if (status.isEmpty()) "Set budgets" else "Edit") }
        }
        if (status.isEmpty()) {
            Text("Set a monthly limit for any category and get a warning at 80% and 100%.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        }
        status.forEach { b ->
            val color = budgetColor(b.percent)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(CategoryStyle.icon(b.categoryId), CategoryStyle.color(b.categoryId), 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(names[b.categoryId] ?: "Category", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${b.percent}%", fontWeight = FontWeight.SemiBold, color = color)
                    }
                    ShareBar(b.percent / 100f, color)
                    Text(
                        "${fmtMoney(b.spentMinor)} of ${fmtMoney(b.limitMinor)} · " +
                            if (b.remainingMinor >= 0) "${fmtMoney(b.remainingMinor)} left" else "${fmtMoney(-b.remainingMinor)} over",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                    )
                }
            }
        }
    }
}

@Composable
fun BudgetDialog(categories: List<CategoryEntity>, limits: Map<Long, Long>, onSave: (Map<Long, String>) -> Unit, onDismiss: () -> Unit) {
    val texts = remember {
        mutableStateMapOf<Long, String>().apply {
            categories.forEach { c -> put(c.id, limits[c.id]?.let { Money.fromMinor(it).stripTrailingZeros().toPlainString() } ?: "") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("Monthly budgets (AED)") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item { Text("Leave empty for no budget.", style = MaterialTheme.typography.bodySmall, color = Ink.muted) }
                items(categories, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(CategoryStyle.icon(c.id), CategoryStyle.color(c.id), 30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(
                            texts[c.id] ?: "", { texts[c.id] = it }, singleLine = true, modifier = Modifier.width(110.dp),
                            placeholder = { Text("—", color = Ink.faint) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(texts.toMap()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ fixed payments

/** Overview panel: fixed payments this month (unpaid first). */
@Composable
fun FixedPaymentsPanel(items: List<FixedPaymentEntity>, autoPaid: Set<Long>, onManage: () -> Unit, onPaid: (FixedPaymentEntity) -> Unit, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val active = items.filter { it.active }
    Panel(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fixed payments", style = MaterialTheme.typography.titleMedium)
                val unpaid = active.filterNot { FixedSchedule.paidThisMonth(it.lastPaidYm.toYm(), today) || it.id in autoPaid }
                Text(
                    if (active.isEmpty()) "Rent, school fees, loans without SMS"
                    else "${fmtMoney(unpaid.sumOf { it.amountMinor })} still to pay this month",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
            }
            TextButton(onClick = onManage) { Text(if (active.isEmpty()) "Add" else "Manage") }
        }
        active.sortedWith(compareBy({ FixedSchedule.paidThisMonth(it.lastPaidYm.toYm(), today) || it.id in autoPaid }, { FixedSchedule.nextDue(it.dayOfMonth, today, it.lastPaidYm.toYm()) }))
            .take(6).forEach { f -> FixedRow(f, today, bySms = f.id in autoPaid, onPaid = { onPaid(f) }) }
    }
}

fun String?.toYm(): YearMonth? = this?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

@Composable
fun FixedRow(f: FixedPaymentEntity, today: LocalDate, bySms: Boolean = false, onPaid: (() -> Unit)?, onClick: (() -> Unit)? = null) {
    val paid = bySms || FixedSchedule.paidThisMonth(f.lastPaidYm.toYm(), today)
    val due = FixedSchedule.nextDue(f.dayOfMonth, today, if (bySms) YearMonth.from(today) else f.lastPaidYm.toYm())
    val left = FixedSchedule.daysLeft(due, today)
    val status = when {
        bySms -> "Paid (seen in your bank SMS) · next ${due.format(dateFmt)}"
        paid -> "Paid for ${YearMonth.from(today).format(monthFmt)} · next ${due.format(dateFmt)}"
        left < 0 -> "Overdue by ${-left} days (${due.format(dateFmt)})"
        left == 0L -> "Due today"
        left == 1L -> "Due tomorrow"
        else -> "Due ${due.format(dateFmt)} · in $left days"
    }
    val color = when { paid -> Ink.green; left < 0 -> Ink.red; left <= 3 -> Ink.amber; else -> Ink.muted }
    var m = Modifier.fillMaxWidth().padding(top = 10.dp)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        IconBadge(if (paid) Icons.Filled.CheckCircle else Icons.Filled.EventRepeat, if (paid) Ink.green else CategoryStyle.color(f.categoryId), 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.bodySmall, color = color)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtMoney(f.amountMinor), fontWeight = FontWeight.SemiBold)
            if (!paid && onPaid != null) {
                Text(
                    "Mark paid", color = Ink.green, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onPaid).padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
fun FixedPaymentsScreen(vm: MainViewModel) {
    val items by vm.fixedPayments.collectAsStateWithLifecycle()
    val autoPaid by vm.fixedAutoPaid.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<FixedPaymentEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    if (adding || editing != null) {
        FixedPaymentDialog(
            existing = editing, categories = categories, cards = cards,
            onSave = { vm.saveFixedPayment(it); adding = false; editing = null },
            onDelete = { id -> vm.deleteFixedPayment(id); editing = null },
            onDismiss = { adding = false; editing = null },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Monthly payments with a due date: rent, school fees, a car EMI. You get reminders 3 days before, the day before and on the day " +
                    "(with Due-date reminders on in Settings). If a bank SMS this month shows the same amount (within 5%) from the chosen card or account, " +
                    "it is marked paid automatically. For payments without an SMS, \"Mark paid\" adds it to your transactions so it counts in spending and budgets.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted,
            )
        }
        item {
            Button(onClick = { adding = true }, shape = RoundedCornerShape(50)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add fixed payment")
            }
        }
        if (items.isNotEmpty()) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Row {
                        Eyebrow("Monthly total", Modifier.weight(1f))
                        Text(fmtMoney(items.filter { it.active }.sumOf { it.amountMinor }), fontWeight = FontWeight.SemiBold, color = Ink.green)
                    }
                    items.forEach { f ->
                        Box(Modifier.fillMaxWidth()) {
                            Column {
                                FixedRow(
                                    f, today, bySms = f.id in autoPaid,
                                    onPaid = if (f.active) ({ vm.markFixedPaid(f); Unit }) else null,
                                    onClick = { editing = f },
                                )
                                if (!f.active) Text("Paused", style = MaterialTheme.typography.labelSmall, color = Ink.faint, modifier = Modifier.padding(start = 48.dp))
                                if (FixedSchedule.paidThisMonth(f.lastPaidYm.toYm(), today)) {
                                    Text(
                                        "Undo paid", style = MaterialTheme.typography.labelMedium, color = Ink.muted,
                                        modifier = Modifier.padding(start = 48.dp).clickable { vm.unmarkFixedPaid(f) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FixedPaymentDialog(
    existing: FixedPaymentEntity?,
    categories: List<CategoryEntity>,
    cards: List<CardEntity>,
    onSave: (FixedPaymentEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var amount by remember { mutableStateOf(existing?.amountMinor?.let { Money.fromMinor(it).toPlainString() } ?: "") }
    var day by remember { mutableStateOf(existing?.dayOfMonth?.toString() ?: "1") }
    var cat by remember { mutableStateOf(existing?.categoryId) }
    var card by remember { mutableStateOf(existing?.cardKey) }
    var remind by remember { mutableStateOf(existing?.remind ?: true) }
    var active by remember { mutableStateOf(existing?.active ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text(if (existing == null) "New fixed payment" else "Edit fixed payment") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(name, { name = it }, label = { Text("Name (e.g. Rent)") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(amount, { amount = it }, label = { Text("Amount (AED)") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(day, { day = it.filter(Char::isDigit).take(2) }, label = { Text("Day") }, singleLine = true, modifier = Modifier.width(80.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
                item { Eyebrow("Category") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        categories.forEach { c ->
                            Pill(c.name, cat == c.id, onClick = { cat = if (cat == c.id) null else c.id }, icon = CategoryStyle.icon(c.id), tint = CategoryStyle.color(c.id))
                        }
                    }
                }
                item { Eyebrow("Paid from (optional)") }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill("None", card == null, onClick = { card = null }, tint = Ink.violet)
                        cards.forEach { c -> Pill(CardArts.displayName(c), card == c.cardKey, onClick = { card = c.cardKey }, tint = Ink.violet) }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Remind me", modifier = Modifier.weight(1f)); Switch(remind, { remind = it })
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Active", modifier = Modifier.weight(1f)); Switch(active, { active = it })
                    }
                }
                error?.let { e -> item { Text(e, color = Ink.red) } }
                if (existing != null) item { TextButton(onClick = { onDelete(existing.id) }) { Text("Delete", color = Ink.red) } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.replace(",", "").trim().toBigDecimalOrNull()
                val d = day.toIntOrNull()
                when {
                    name.isBlank() -> error = "Give it a name"
                    a == null || a.signum() <= 0 -> error = "Enter an amount"
                    d == null || d !in 1..31 -> error = "Day must be 1–31"
                    else -> onSave(
                        (existing ?: FixedPaymentEntity(name = "", amountMinor = 0, dayOfMonth = 1))
                            .copy(name = name.trim(), amountMinor = Money.toMinor(a), dayOfMonth = d, categoryId = cat, cardKey = card, remind = remind, active = active),
                    )
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ settings pieces

/** Theme choice: a preview tile per theme. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemePicker(selected: String, onPick: (String) -> Unit) {
    Column {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Text("Colours for the whole app. Card tiles keep their own looks.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppThemes.all.forEach { p ->
                val on = p.id == selected
                Column(
                    Modifier.width(100.dp).clip(RoundedCornerShape(16.dp))
                        .border(if (on) 2.5.dp else 1.dp, if (on) Ink.green else Ink.border, RoundedCornerShape(16.dp))
                        .clickable { onPick(p.id) },
                ) {
                    Box(Modifier.fillMaxWidth().height(64.dp).background(Brush.verticalGradient(listOf(p.bgTop, p.bg))).padding(8.dp)) {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(p.surface).padding(6.dp)) {
                            Box(Modifier.width(34.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.muted))
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.width(50.dp).height(9.dp).clip(RoundedCornerShape(3.dp)).background(p.accent))
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(p.accent2))
                                Box(Modifier.size(8.dp).clip(CircleShape).background(p.amber))
                                Box(Modifier.size(8.dp).clip(CircleShape).background(p.red))
                            }
                        }
                    }
                    Text(
                        p.name, style = MaterialTheme.typography.labelMedium, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().background(Ink.surfaceHigh).padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun AlertsSettings(vm: MainViewModel, onToggle: (Boolean) -> Unit) {
    val on by vm.alertsOn.collectAsStateWithLifecycle()
    val big by vm.bigSpendMinor.collectAsStateWithLifecycle()
    val acc by vm.lowAccountMinor.collectAsStateWithLifecycle()
    val card by vm.lowCardMinor.collectAsStateWithLifecycle()
    val budgets by vm.budgetAlertsOn.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spending alerts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A notification when a new SMS shows a big spend, a low balance, or a budget at 80% / 100%. Checked only when SMS arrive (live listening) or you Sync.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(on, onToggle)
        }
        if (on) {
            AmountSetting("Big spend at or above (AED)", big) { vm.setAlertAmount("big", it) }
            AmountSetting("Account balance below (AED)", acc) { vm.setAlertAmount("account", it) }
            AmountSetting("Card available limit below (AED)", card) { vm.setAlertAmount("card", it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Budget warnings (80% and 100%)", modifier = Modifier.weight(1f))
                Switch(budgets, vm::setBudgetAlerts)
            }
            Text("Leave an amount at 0 to turn that alert off.", style = MaterialTheme.typography.bodySmall, color = Ink.faint)
        }
    }
}

@Composable
private fun AmountSetting(label: String, minor: Long, onSave: (String) -> Unit) {
    var text by remember(minor) { mutableStateOf(Money.fromMinor(minor).stripTrailingZeros().toPlainString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        TextButton(onClick = { onSave(text) }, enabled = text != Money.fromMinor(minor).stripTrailingZeros().toPlainString()) { Text("Save") }
    }
}

/** Export choice for the selected period. */
@Composable
fun ReportDialog(periodLabel: String, onPdf: () -> Unit, onCsv: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("Export report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("For $periodLabel (change the period on Overview first).", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                OutlinedButton(onClick = { onDismiss(); onPdf() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.PictureAsPdf, null); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("PDF report")
                        Text("Summary, category chart, budgets, cards and every transaction", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    }
                }
                OutlinedButton(onClick = { onDismiss(); onCsv() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Filled.TableChart, null); Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Excel (CSV)")
                        Text("The same figures as a spreadsheet", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
