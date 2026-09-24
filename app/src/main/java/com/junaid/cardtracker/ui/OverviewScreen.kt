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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junaid.cardtracker.core.Bucket
import com.junaid.cardtracker.core.DueState
import com.junaid.cardtracker.core.TimePoint
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
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val weekdayDayMonth = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val monthYearShort = DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH)

/** Axis label and tooltip for a timeline bucket. */
fun TimePoint.toChartPoint(bucket: Bucket): ChartPoint = when (bucket) {
    Bucket.DAY -> ChartPoint(start.format(dayMonth), start.format(weekdayDayMonth), amountMinor)
    Bucket.WEEK -> ChartPoint(start.format(dayMonth), "Week of " + start.format(dayMonth), amountMinor)
    Bucket.MONTH -> ChartPoint(start.format(monthYearShort), start.format(monthFmt), amountMinor)
}

// ================================================================ Overview (dashboard)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(
    vm: MainViewModel,
    onOpenCategory: (Long?) -> Unit,
    onOpenCard: (String) -> Unit,
    onOpenFixed: () -> Unit = {},
) {
    val budgetStatus by vm.budgetStatus.collectAsStateWithLifecycle()
    val budgetLimits by vm.budgetLimits.collectAsStateWithLifecycle()
    val fixed by vm.fixedPayments.collectAsStateWithLifecycle()
    val autoPaid by vm.fixedAutoPaid.collectAsStateWithLifecycle()
    var editingBudgets by remember { mutableStateOf(false) }
    val o by vm.overview.collectAsStateWithLifecycle()
    val dues by vm.dues.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val goals by vm.goals.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val names = categories.associate { it.id to it.name }
    if (editingBudgets) {
        BudgetDialog(categories, budgetLimits, onSave = { vm.saveBudgets(it) }, onDismiss = { editingBudgets = false })
    }
    val cardNames = cards.associate { it.cardKey to CardArts.displayName(it) }
    var chartMode by rememberSaveable { mutableStateOf(0) } // 0 = arc, 1 = trend
    var selectedCat by remember(o.period) { mutableStateOf<Long?>(null) }
    var hasSel by remember(o.period) { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- hero
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                Eyebrow("Total spent")
                Text(fmtMoney(o.spentMinor), style = heroNumberStyle)
                val prev = o.previous
                if (prev != null && o.previousMinor > 0) {
                    val diff = o.spentMinor - o.previousMinor
                    val pct = kotlin.math.abs(diff) * 100 / o.previousMinor
                    val up = diff > 0
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Icon(if (up) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown, null, Modifier.size(16.dp), tint = if (up) Ink.red else Ink.green)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$pct% ${if (up) "more" else "less"} than ${prev.label()} · ${fmtMoney(o.previousMinor)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.muted,
                            maxLines = 2,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("Per day", fmtCompactMoney(o.avgPerDayMinor), Modifier.weight(1f))
                    MiniStat("Spends", o.txnCount.toString(), Modifier.weight(1f))
                    MiniStat("Money in", fmtCompactMoney(o.moneyInMinor), Modifier.weight(1f), Ink.green)
                }
            }
        }

        // ---- period chips
        item {
            PeriodSelector(
                period = o.period,
                onKind = vm::selectPeriodKind,
                onShift = vm::shiftPeriod,
                onCustom = vm::setCustomPeriod,
            )
        }

        // ---- chart card: arc (categories) or trend (over time)
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (chartMode == 0) "By category" else "Over time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    ModeToggle(chartMode) { chartMode = it }
                }
                Spacer(Modifier.height(8.dp))
                if (chartMode == 0) {
                    val slices = o.byCategory.map { (id, v) ->
                        DonutSlice(id, v, CategoryStyle.color(id), id?.let { names[it] } ?: "Uncategorised")
                    }
                    val sel = slices.firstOrNull { hasSel && it.key == selectedCat }
                    DonutChart(
                        slices = slices,
                        selectedKey = selectedCat,
                        hasSelection = hasSel,
                        onSelect = { s -> if (s == null) { hasSel = false } else { selectedCat = s.key; hasSel = true } },
                        centerTitle = sel?.label ?: "Total",
                        centerValue = fmtCompactMoney(sel?.value ?: o.spentMinor),
                        centerSubtitle = sel?.let { s -> if (o.spentMinor > 0) "${s.value * 100 / o.spentMinor}% of spend" else null }
                            ?: if (slices.isEmpty()) "No spending" else "${slices.size} categories",
                    )
                    if (sel != null) {
                        TextButton(onClick = { onOpenCategory(sel.key) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text("See ${sel.label} transactions")
                        }
                    }
                    if (slices.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            slices.forEach { s ->
                                Pill(
                                    text = s.label,
                                    selected = hasSel && selectedCat == s.key,
                                    onClick = { if (hasSel && selectedCat == s.key) hasSel = false else { selectedCat = s.key; hasSel = true } },
                                    icon = CategoryStyle.icon(s.key),
                                    tint = s.color,
                                )
                            }
                        }
                    }
                } else {
                    AreaChart(
                        points = o.timeline.map { it.toChartPoint(o.bucket) },
                        idleTitle = when (o.bucket) { Bucket.DAY -> "Daily spend"; Bucket.WEEK -> "Weekly spend"; Bucket.MONTH -> "Monthly spend" },
                        idleValue = "avg " + fmtMoney(if (o.timeline.isNotEmpty()) o.spentMinor / o.timeline.size else 0L),
                    )
                    Text("Touch or drag across the chart to read a value", style = MaterialTheme.typography.bodySmall, color = Ink.faint, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        // ---- category list with amounts and shares
        if (o.byCategory.isNotEmpty()) {
            item {
                Panel(Modifier.padding(horizontal = 16.dp), padding = PaddingValues(vertical = 8.dp)) {
                    val max = o.byCategory.maxOf { it.second }.coerceAtLeast(1)
                    o.byCategory.forEach { (id, amount) ->
                        val color = CategoryStyle.color(id)
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenCategory(id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(CategoryStyle.icon(id), color, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(id?.let { names[it] } ?: "Uncategorised", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(fmtMoney(amount), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ShareBar(amount.toFloat() / max, color, Modifier.weight(1f))
                                    Spacer(Modifier.width(10.dp))
                                    Text(if (o.spentMinor > 0) "${amount * 100 / o.spentMinor}%" else "", style = MaterialTheme.typography.labelMedium, color = Ink.muted)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- budgets and fixed payments
        item { BudgetsPanel(budgetStatus, names, onEdit = { editingBudgets = true }, modifier = Modifier.padding(horizontal = 16.dp)) }
        item { FixedPaymentsPanel(fixed, autoPaid, onManage = onOpenFixed, onPaid = { vm.markFixedPaid(it) }, modifier = Modifier.padding(horizontal = 16.dp)) }

        // ---- upcoming dues
        val open = dues.filter { it.card.countInSpending }.filter { it.status.state == DueState.UNPAID || it.status.state == DueState.OVERDUE || it.status.state == DueState.MIN_PAID }
        item { SectionHeader("Payments due", Modifier.padding(horizontal = 20.dp)) }
        // Fixed payments (e.g. car EMI) not yet paid and due within 10 days.
        val today = LocalDate.now()
        val soonFixed = fixed.filter { f ->
            f.active && f.id !in autoPaid && !com.junaid.cardtracker.core.FixedSchedule.paidThisMonth(f.lastPaidYm.toYm(), today) &&
                com.junaid.cardtracker.core.FixedSchedule.daysLeft(com.junaid.cardtracker.core.FixedSchedule.nextDue(f.dayOfMonth, today, f.lastPaidYm.toYm()), today) <= 10
        }
        if (soonFixed.isNotEmpty()) {
            item {
                Panel(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), padding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    soonFixed.forEach { f -> FixedRow(f, today, onPaid = { vm.markFixedPaid(f) }, onClick = onOpenFixed) }
                }
            }
        }
        if (open.isEmpty() && soonFixed.isEmpty()) {
            item { Muted(if (dues.none { it.card.countInSpending }) "No statement SMS yet." else "All statements paid.", Modifier.padding(horizontal = 20.dp)) }
        }
        items(open, key = { "due-" + it.card.cardKey }) { d -> DueRow(d, Modifier.padding(horizontal = 16.dp)) { onOpenCard(d.card.cardKey) } }

        // ---- 12-month history
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Text("Last 12 months", style = MaterialTheme.typography.titleMedium)
                val selIdx = o.history.indexOfFirst { o.period.kind == com.junaid.cardtracker.core.PeriodKind.MONTH && o.period.start?.let(YearMonth::from) == it.month }
                val sel = o.history.getOrNull(selIdx)
                Spacer(Modifier.height(6.dp))
                BarChart(
                    data = o.history.map { it.month.format(shortMonth).take(3) to it.amountMinor },
                    selectedIndex = selIdx.takeIf { it >= 0 },
                    onSelect = { i -> vm.selectMonth(o.history[i].month) },
                    headline = sel?.let { "${it.month.format(monthFmt)}: ${fmtMoney(it.amountMinor)}" } ?: "Tap a month to open it",
                )
            }
        }

        // ---- top merchants
        if (o.topMerchants.isNotEmpty()) {
            item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    Text("Top merchants", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    val max = o.topMerchants.maxOf { it.second }.coerceAtLeast(1)
                    o.topMerchants.forEachIndexed { i, (m, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Ink.faint, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                Row {
                                    Text(m, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(fmtMoney(v), fontWeight = FontWeight.SemiBold)
                                }
                                ShareBar(v.toFloat() / max, Ink.green)
                            }
                        }
                    }
                }
            }
        }

        // ---- by card
        if (o.byCard.isNotEmpty()) {
            item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    Text("By card", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    val cardMax = o.byCard.maxOf { it.amountMinor }.coerceAtLeast(1)
                    o.byCard.forEach { s ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = s.key != "Typed entries") { onOpenCard(s.key) }.padding(vertical = 4.dp)) {
                            Row {
                                Text(cardNames[s.key] ?: s.key, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(fmtMoney(s.amountMinor), fontWeight = FontWeight.SemiBold)
                            }
                            ShareBar(s.amountMinor.toFloat() / cardMax, Ink.violet)
                        }
                    }
                }
            }
        }

        // ---- recurring
        item {
            Panel(Modifier.padding(horizontal = 16.dp)) {
                Text("Recurring payments", style = MaterialTheme.typography.titleMedium)
                if (o.recurring.isEmpty()) Muted("None detected yet (needs 3+ roughly monthly charges).", Modifier.padding(top = 6.dp))
                o.recurring.forEach { r ->
                    val catName = r.categoryId?.let { names[it] }
                    val tracked = fixed.any { it.cardKey == r.cardKey && kotlin.math.abs(it.amountMinor - r.averageMinor) * 20 <= r.averageMinor }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (r.categoryId != null) IconBadge(CategoryStyle.icon(r.categoryId), CategoryStyle.color(r.categoryId), 34.dp)
                        else IconBadge(Icons.Filled.Repeat, Ink.violet, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            // Generic texts ("Account debit", "Transfer to ·2001") read better with the category you gave them.
                            val generic = r.merchantKey.startsWith("ACCOUNT DEBIT") || r.merchantKey.startsWith("TRANSFER") || r.merchantKey.startsWith("PAYMENT TO")
                            Text(if (generic && catName != null) catName else r.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (generic && catName != null) r.merchant + " · " else "") +
                                    "${r.occurrences}× · next about ${r.nextExpected.format(dateFmt)}" + (r.cardKey?.let { " · " + (cardNames[it] ?: it) } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = Ink.muted,
                                maxLines = 2,
                            )
                            if (!tracked) {
                                Text(
                                    "+ Track as fixed payment", color = Ink.green, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 2.dp).clip(RoundedCornerShape(50)).clickable { vm.trackRecurring(r) }.padding(vertical = 4.dp),
                                )
                            } else {
                                Text("In fixed payments: due reminders on", style = MaterialTheme.typography.labelMedium, color = Ink.faint, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                        Text(fmtMoney(r.averageMinor), fontWeight = FontWeight.SemiBold)
                    }
                }
                if (o.recurring.isNotEmpty()) {
                    Text(
                        "Tip: give a bank-account debit a category (Transactions → tap it → Change, e.g. \"Car EMI\") and it is recognised here by its amount.",
                        style = MaterialTheme.typography.bodySmall, color = Ink.faint, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        // ---- currencies
        if (o.byCurrency.isNotEmpty()) {
            item {
                Panel(Modifier.padding(horizontal = 16.dp)) {
                    Text("Foreign currency (in AED)", style = MaterialTheme.typography.titleMedium)
                    o.byCurrency.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text("${fmtMoney(c.originalMinor, c.currency)} · ${c.count} txns", modifier = Modifier.weight(1f), color = Ink.muted)
                            Text("≈ " + fmtMoney(c.aedMinor), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ---- goals
        item { Box(Modifier.padding(horizontal = 16.dp)) { GoalsSection(goals, onSave = { vm.saveGoal(it) }, onDelete = { vm.deleteGoal(it) }) } }
    }
}

/** AED 12.3K style for tight spaces (full amounts elsewhere). */
fun fmtCompactMoney(minor: Long): String = if (kotlin.math.abs(minor) < 100_000_00) fmtMoney(minor).substringBeforeLast('.') else "AED " + fmtCompact(minor)

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Ink.text) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Ink.surface).border(1.dp, Ink.border, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Eyebrow(label)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Two round icon buttons: arc chart / trend chart. */
@Composable
private fun ModeToggle(mode: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Ink.surfaceHigh).border(1.dp, Ink.border, RoundedCornerShape(50)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(Icons.Filled.DonutLarge to "Categories", Icons.Filled.ShowChart to "Trend").forEachIndexed { i, (icon, desc) ->
            val on = mode == i
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(if (on) Ink.green.copy(alpha = 0.2f) else Color.Transparent).clickable { onChange(i) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, desc, Modifier.size(18.dp), tint = if (on) Ink.green else Ink.muted)
            }
        }
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
}

@Composable
private fun DueRow(d: CardDue, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val attention = d.status.state == DueState.UNPAID || d.status.state == DueState.OVERDUE
    val accent = if (attention) Ink.red else Ink.amber
    Panel(modifier.fillMaxWidth(), onClick = onClick, padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 44.dp).clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Due ${fmtEpochDay(d.statement.dueDateEpochDay)}" + (d.statement.minimumDueMinor?.let { " · min ${fmtMoney(it, d.statement.currency)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.muted,
                )
                Text(dueStateText(d), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = accent)
            }
            Text(fmtMoney(d.status.remainingMinor, d.statement.currency), style = MaterialTheme.typography.titleMedium, color = Ink.text)
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
            Text("Savings goals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { adding = true }) { Text("Add goal") }
        }
        if (goals.isEmpty()) Muted("Set a target and log what you put aside.")
        goals.forEach { g ->
            val frac = if (g.targetMinor > 0) g.savedMinor.toFloat() / g.targetMinor else 0f
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
                Column {
                    Row {
                        Text(g.name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("${(frac * 100).toInt()}%", fontWeight = FontWeight.SemiBold, color = Ink.green)
                    }
                    ShareBar(frac, Ink.green)
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
    applyLabel: String? = null,
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
                        Text(applyLabel ?: "Apply to all \"${merchant.take(28)}\" (past and future)", style = MaterialTheme.typography.bodyMedium)
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
