package com.junaid.cardtracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import com.junaid.cardtracker.core.Period
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.junaid.cardtracker.core.DueState
import com.junaid.cardtracker.core.Utilisation
import com.junaid.cardtracker.data.CardDue
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
import com.junaid.cardtracker.data.CategoryEntity
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

// DecimalFormat isn't thread-safe and fmtMoney is also used by the widget and reminder worker.
private val moneyFmt = ThreadLocal.withInitial { DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.ENGLISH)) }
fun fmtMoney(minor: Long, currency: String = "AED") = "$currency ${moneyFmt.get()!!.format(Money.fromMinor(minor))}"

private val dateTimeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
val monthFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
fun fmtDateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTimeFmt)
fun fmtEpochDay(day: Long): String = LocalDate.ofEpochDay(day).format(dateFmt)

private fun typeLabel(t: String) = when (t) {
    CardTypes.DEBIT -> "Debit card"
    CardTypes.ACCOUNT -> "Bank account"
    else -> "Credit card"
}

// ---------------------------------------------------------- transactions tab

private val dayHeaderFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH)
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

@Composable
fun TransactionsScreen(
    vm: MainViewModel,
    period: Period,
    cardFilter: String?,
    cards: List<CardEntity>,
    excluded: Set<String>,
    txns: List<TransactionEntity>,
    syncing: Boolean,
    lastSyncAt: Long?,
    liveOn: Boolean,
    onSync: () -> Unit,
    categories: List<CategoryEntity> = emptyList(),
    categoryFilter: Long? = null,
) {
    val query by vm.search.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val catNames = categories.associate { it.id to it.name }
    val cardNames = cards.associate { it.cardKey to CardArts.displayName(it) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    if (adding) QuickAddDialog(onAdd = { text -> vm.addManual(text) }, onDismiss = { adding = false })

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp), modifier = Modifier.fillMaxSize()) {
        // ---- sync + quick add
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSync, enabled = !syncing, shape = RoundedCornerShape(50)) {
                    if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink.onAccent)
                    else Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (syncing) "Syncing…" else "Sync")
                }
                OutlinedButton(onClick = { adding = true }, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (lastSyncAt == null) "Never synced" else "Synced ${fmtDateTime(lastSyncAt)}",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 1,
                    )
                    Text(if (liveOn) "Live listening on" else "Live listening off", style = MaterialTheme.typography.bodySmall, color = Ink.faint, maxLines = 1)
                }
            }
        }
        // ---- search
        item {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                singleLine = true,
                shape = RoundedCornerShape(50),
                placeholder = { Text("Search: talabat, groceries, 9940…", color = Ink.faint) },
                leadingIcon = { Icon(Icons.Filled.Search, null, tint = Ink.muted) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.setSearch("") }) { Icon(Icons.Filled.Close, "Clear search", tint = Ink.muted) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Ink.surface, focusedContainerColor = Ink.surface,
                    unfocusedBorderColor = Ink.border, focusedBorderColor = Ink.green,
                ),
            )
        }
        // ---- period
        item { PeriodSelector(period, vm::selectPeriodKind, vm::shiftPeriod, vm::setCustomPeriod) }
        // ---- card filter
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Pill("All cards", cardFilter == null, onClick = { vm.selectCard(null) }, tint = Ink.violet) }
                items(cards.filter { it.countInSpending || it.cardKey == cardFilter }, key = { it.cardKey }) { c ->
                    Pill(CardArts.displayName(c), cardFilter == c.cardKey, onClick = { vm.selectCard(c.cardKey) }, tint = Ink.violet)
                }
            }
        }
        if (categoryFilter != null) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Pill(
                        "${catNames[categoryFilter] ?: "Category"}  ✕", true, onClick = { vm.selectCategory(null) },
                        icon = CategoryStyle.icon(categoryFilter), tint = CategoryStyle.color(categoryFilter),
                    )
                }
            }
        }
        // ---- summary
        item {
            val selected = cards.firstOrNull { it.cardKey == cardFilter }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (selected != null && selected.cardType == CardTypes.ACCOUNT) AccountSummary(selected, txns)
                else SpendSummary(txns, excluded)
            }
        }
        if (txns.isEmpty()) {
            item {
                Text(
                    when {
                        lastSyncAt == null -> "Tap Sync to import your bank messages, or Add to type one."
                        query.isNotBlank() -> "Nothing matches \"$query\" in this period."
                        else -> "No transactions for this filter."
                    },
                    Modifier.padding(24.dp),
                    color = Ink.muted,
                )
            }
        }
        // ---- list, grouped by day (newest first)
        val byDay = txns.groupBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
        byDay.forEach { (day, list) ->
            item(key = "day-$day") {
                val daySpend = spendingTotal(list, excluded)
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(dayHeaderFmt) },
                        style = MaterialTheme.typography.labelLarge, color = Ink.muted, modifier = Modifier.weight(1f),
                    )
                    if (daySpend != 0L) Text(fmtMoney(daySpend), style = MaterialTheme.typography.labelLarge, color = Ink.faint)
                }
            }
            items(list, key = { it.id }) { t ->
                TransactionRow(vm, t, counted = t.cardKey == null || t.cardKey !in excluded, categories = categories, catNames = catNames, cardNames = cardNames)
            }
        }
    }
}

/** Bank account view: Net on top, then Money in / Money out as two equal tiles (readable on any width). */
@Composable
private fun AccountSummary(account: CardEntity, txns: List<TransactionEntity>) {
    val moneyIn = txns.filter { it.type == TxnType.TRANSFER_IN.name || it.type == TxnType.REFUND.name }.sumOf { it.amountAedMinor ?: 0L }
    val moneyOut = txns.filter { it.type == TxnType.TRANSFER_OUT.name || it.type == TxnType.PURCHASE.name }.sumOf { it.amountAedMinor ?: 0L }
    val net = moneyIn - moneyOut
    val spend = spendingTotal(txns, emptySet())
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Net · ${CardArts.displayName(account)}")
        Text(
            (if (net >= 0) "+" else "−") + fmtMoney(kotlin.math.abs(net)),
            style = MaterialTheme.typography.headlineMedium,
            color = if (net >= 0) Ink.green else Ink.red,
            maxLines = 1,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowTile("Money in", moneyIn, Icons.Filled.SouthWest, Ink.green, Modifier.weight(1f))
            FlowTile("Money out", moneyOut, Icons.Filled.NorthEast, Ink.violet, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "${txns.size} transactions · " + if (account.countInSpending) "counted as spending: ${fmtMoney(spend)} (EMIs, bills, ATM)"
            else "not counted in spending (switch on in Cards to include EMIs, bills and ATM)",
            style = MaterialTheme.typography.bodySmall,
            color = Ink.muted,
        )
    }
}

@Composable
private fun FlowTile(label: String, minor: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.30f), RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = color)
            Spacer(Modifier.width(6.dp))
            Eyebrow(label, color = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(fmtMoney(minor), style = MaterialTheme.typography.titleMedium, color = Ink.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SpendSummary(txns: List<TransactionEntity>, excluded: Set<String>) {
    val spend = spendingTotal(txns, excluded)
    // Card payments: PAYMENT SMS from the card's bank, plus transfers from your account to your own cards.
    val payments = txns.filter { it.type == TxnType.PAYMENT.name || it.counterpartyKey != null }.sumOf { it.amountAedMinor ?: 0L }
    val notCounted = txns.count { it.cardKey != null && it.cardKey in excluded }
    val moneyIn = txns.filter { it.type == TxnType.TRANSFER_IN.name }.sumOf { it.amountAedMinor ?: 0L }
    val moneyOut = txns.filter { it.type == TxnType.TRANSFER_OUT.name }.sumOf { it.amountAedMinor ?: 0L }
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Spent")
        Text(fmtMoney(spend), style = MaterialTheme.typography.headlineMedium, color = Ink.green, maxLines = 1)
        val extra = mutableListOf("${txns.size} transactions")
        if (payments > 0) extra += "card payments ${fmtMoney(payments)}"
        if (moneyIn > 0 || moneyOut > 0) extra += "account in ${fmtMoney(moneyIn)} / out ${fmtMoney(moneyOut)}"
        if (notCounted > 0) extra += "some cards switched off (not in total)"
        Text(extra.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}

@Composable
private fun QuickAddDialog(onAdd: (String) -> Boolean, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("Add a transaction") },
        text = {
            Column {
                Text("Type it like you'd say it. Add #1234 for a card, or \"refund\".", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { text = it }, singleLine = true, placeholder = { Text("lunch 45 aed") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank() && onAdd(text)) onDismiss() }),
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank() && onAdd(text)) onDismiss() }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TransactionRow(
    vm: MainViewModel,
    t: TransactionEntity,
    counted: Boolean,
    categories: List<CategoryEntity>,
    catNames: Map<Long, String>,
    cardNames: Map<String, String>,
) {
    var expanded by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        CategoryPickerDialog(
            merchant = t.merchant,
            applyLabel = if (com.junaid.cardtracker.parser.CategoryRules.isAmountSpecific(t.merchant, t.txnType()))
                "Apply to every \"${t.merchant.take(24)}\" of ${fmtMoney(t.amountMinor, t.currency)} (past and future)" else null,
            current = t.categoryId,
            categories = categories,
            onPick = { id, all -> vm.setCategory(t, id, all); picking = false },
            onCreate = { name, all -> vm.addCategoryAndSet(t, name, all); picking = false },
            onDismiss = { picking = false },
        )
    }
    val spendType = t.type == TxnType.PURCHASE.name || t.type == TxnType.REFUND.name
    // Money leaving an account (transfers, EMIs by transfer) can be given a category too.
    val canCategorise = spendType || t.type == TxnType.TRANSFER_OUT.name
    var raw by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) { if (expanded && raw == null && t.smsId != null) raw = vm.rawSms(t.smsId) }
    val (icon, color) = t.visual()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (expanded) Ink.surfaceHigh else Ink.surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (counted) color else Ink.faint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.merchant, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val card = t.cardKey?.let { cardNames[it] ?: it } ?: if (t.cardLast4 != null) "${t.bank} ·${t.cardLast4}" else t.bank
                val kind = when (t.type) {
                    TxnType.REFUND.name -> "Refund"
                    TxnType.PAYMENT.name -> "Card payment"
                    TxnType.TRANSFER_IN.name -> "Money in"
                    TxnType.TRANSFER_OUT.name -> if (t.counterpartyKey != null) "Card payment" else "Money out"
                    else -> null
                }
                val first = when {
                    t.type == TxnType.PURCHASE.name -> t.categoryId?.let { catNames[it] } ?: "Uncategorised"
                    t.type == TxnType.TRANSFER_OUT.name && t.categoryId != null -> "${catNames[t.categoryId] ?: ""} · ${kind ?: ""}"
                    else -> kind ?: ""
                }
                val tags = listOfNotNull(first.ifBlank { null }, card, if (t.source == "MANUAL") "typed" else null, if (!counted) "not counted" else null)
                Text(tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                val out = t.type == TxnType.PURCHASE.name || t.type == TxnType.TRANSFER_OUT.name
                val amtColor = when {
                    !counted -> Ink.muted
                    out -> Ink.text
                    else -> Ink.green
                }
                Text((if (out) "−" else "+") + fmtMoney(t.amountMinor, t.currency), color = amtColor, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    if (t.currency != "AED") (t.amountAedMinor?.let { "≈ " + fmtMoney(it) } ?: "no AED rate")
                    else Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault()).format(timeFmt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.faint,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Ink.border)
            Spacer(Modifier.height(6.dp))
            Text(fmtDateTime(t.timestamp) + " · " + t.bank, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
            t.availableLimitMinor?.let { Text("Available limit/balance after: ${fmtMoney(it)}", style = MaterialTheme.typography.bodySmall, color = Ink.muted) }
            if (canCategorise) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Category: ${t.categoryId?.let { catNames[it] } ?: "none"}" + if (t.categoryUserSet) " (set by you)" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) { Text("Change") }
                }
            }
            t.ruleId?.let { Text("Rule: $it", style = MaterialTheme.typography.bodySmall, color = Ink.faint) }
            raw?.let {
                SelectionContainer {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Ink.muted,
                        modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(Ink.bg).padding(10.dp),
                    )
                }
            }
            TextButton(onClick = { vm.deleteTransaction(t) }) { Text("Delete", color = Ink.red) }
        }
    }
}

// ----------------------------------------------------------------- cards tab

@Composable
fun CardsScreen(
    summaries: List<CardSummary>,
    period: Period,
    onOpenCard: (String) -> Unit,
    onToggleCounted: (String, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val periodLabel = period.label()
    var arranging by rememberSaveable { mutableStateOf(false) }
    val withLimit = summaries.filter { it.card.cardType == CardTypes.CREDIT && it.card.creditLimitMinor != null && it.latestBalanceMinor != null }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (summaries.isEmpty()) {
            item { Text("Cards appear here after your first Sync.", color = Ink.muted) }
        }
        if (!arranging) {
            if (withLimit.isNotEmpty()) {
                item { BalancesPanel(withLimit) }
            } else if (summaries.any { it.card.cardType == CardTypes.CREDIT }) {
                item {
                    Text(
                        "Tip: open a credit card and enter its limit to see balances side by side with utilisation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink.muted,
                    )
                }
            }
        }
        if (summaries.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow(if (arranging) "Drag the handles to reorder" else "Figures for $periodLabel", Modifier.weight(1f))
                    if (arranging) {
                        Button(onClick = { arranging = false }, shape = RoundedCornerShape(50)) { Text("Done") }
                    } else {
                        OutlinedButton(onClick = { arranging = true }, shape = RoundedCornerShape(50)) {
                            Icon(Icons.Filled.DragHandle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Arrange")
                        }
                    }
                }
            }
        }
        if (arranging) {
            item { ArrangeCards(summaries.map { it.card }, onReorder) }
        } else {
            items(summaries, key = { it.card.cardKey }) { s ->
                CardTile(s, periodLabel, onClick = { onOpenCard(s.card.cardKey) }, onToggle = { onToggleCounted(s.card.cardKey, it) })
            }
        }
    }
}

/**
 * Drag-to-reorder list. Rows have a fixed height, so a row swaps with its neighbour once it has been
 * dragged more than half a row. The new order is saved when you let go.
 */
@Composable
private fun ArrangeCards(cards: List<CardEntity>, onReorder: (List<String>) -> Unit) {
    val byKey = cards.associateBy { it.cardKey }
    val order = remember(cards.map { it.cardKey }) { mutableStateListOf<String>().apply { addAll(cards.map { it.cardKey }) } }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowPx = with(density) { (64.dp + 8.dp).toPx() }
    Column {
        order.forEach { k ->
            val c = byKey[k] ?: return@forEach
            key(k) {
                val art = CardArts.forCard(c)
                val isDragged = dragging == k
                Row(
                    Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) offset else 0f
                            shadowElevation = if (isDragged) 16f else 0f
                            scaleX = if (isDragged) 1.02f else 1f
                            scaleY = if (isDragged) 1.02f else 1f
                        }
                        .fillMaxWidth()
                        .height(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isDragged) Ink.surfaceHighest else Ink.surface)
                        .border(1.dp, if (isDragged) Ink.green else Ink.border, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardThumb(c, 56.dp, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(CardArts.displayName(c), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${c.bank} · •••• ${c.last4 ?: "----"}", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    }
                    Icon(
                        Icons.Filled.DragHandle, "Drag to move",
                        tint = if (isDragged) Ink.green else Ink.muted,
                        modifier = Modifier
                            .size(44.dp)
                            .padding(10.dp)
                            .pointerInput(k, order) {
                                detectDragGestures(
                                    onDragStart = { dragging = k; offset = 0f },
                                    onDragEnd = { dragging = null; offset = 0f; onReorder(order.toList()) },
                                    onDragCancel = { dragging = null; offset = 0f; onReorder(order.toList()) },
                                ) { change, amount ->
                                    change.consume()
                                    offset += amount.y
                                    val i = order.indexOf(k)
                                    if (i < 0) return@detectDragGestures
                                    if (offset > rowPx / 2 && i < order.lastIndex) {
                                        order.removeAt(i); order.add(i + 1, k); offset -= rowPx
                                    } else if (offset < -rowPx / 2 && i > 0) {
                                        order.removeAt(i); order.add(i - 1, k); offset += rowPx
                                    }
                                }
                            },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** A card drawn in its own look: gradient + pattern, bank badge, name, masked number, period figure, available / balance, due strip. */
@Composable
fun CardTile(s: CardSummary, periodLabel: String, onClick: (() -> Unit)?, onToggle: ((Boolean) -> Unit)?) {
    val c = s.card
    val art = CardArts.forCard(c)
    val image = rememberCardImage(c)
    val onPicture = image != null
    val fg = if (onPicture) Color.White else art.content
    val fgMuted = if (onPicture) Color.White.copy(alpha = 0.85f) else art.contentMuted
    val shape = RoundedCornerShape(24.dp)
    var m = Modifier.fillMaxWidth().alpha(if (c.countInSpending) 1f else 0.6f).clip(shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m) {
    CardBackground(c, art, image)
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankBadge(c.bank, onDarkCard = onPicture || art.lightText)
            Spacer(Modifier.width(8.dp))
            Eyebrow(typeLabel(c.cardType), color = fgMuted, modifier = Modifier.weight(1f))
            if (onToggle != null) {
                Switch(
                    checked = c.countInSpending, onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = fg, checkedTrackColor = fg.copy(alpha = 0.35f),
                        uncheckedThumbColor = fg.copy(alpha = 0.7f), uncheckedTrackColor = Color.Black.copy(alpha = 0.2f),
                        uncheckedBorderColor = fg.copy(alpha = 0.4f), checkedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(CardArts.displayName(c), style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("•••• " + (c.last4 ?: "----"), style = MaterialTheme.typography.bodyMedium, color = fgMuted, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if (c.cardType == CardTypes.ACCOUNT) {
                    val net = s.monthInMinor - s.monthOutMinor
                    Eyebrow("Net · $periodLabel", color = fgMuted)
                    Text((if (net >= 0) "+" else "−") + fmtMoney(kotlin.math.abs(net)), style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1)
                    Text("in ${fmtMoney(s.monthInMinor)} · out ${fmtMoney(s.monthOutMinor)}", style = MaterialTheme.typography.bodySmall, color = fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Eyebrow("Spent · $periodLabel", color = fgMuted)
                    Text(fmtMoney(s.monthSpendAedMinor), style = MaterialTheme.typography.titleLarge, color = fg, maxLines = 1)
                    Text(
                        "${s.monthTxnCount} txns" + (if (s.monthPaidInMinor > 0) " · paid in ${fmtMoney(s.monthPaidInMinor)}" else "") + if (c.countInSpending) "" else " · not in totals",
                        style = MaterialTheme.typography.bodySmall, color = fgMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                s.latestBalanceMinor?.let {
                    Eyebrow(if (c.cardType == CardTypes.ACCOUNT) "Balance" else "Available", color = fgMuted)
                    Text(fmtMoney(it), style = MaterialTheme.typography.titleSmall, color = fg, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                }
                NetworkMark(art.network, fg)
            }
        }
        s.due?.let { d ->
            Spacer(Modifier.height(12.dp))
            val attention = d.status.state == DueState.UNPAID || d.status.state == DueState.OVERDUE
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Statement ${fmtMoney(d.statement.balanceMinor, d.statement.currency)} · due ${fmtEpochDay(d.statement.dueDateEpochDay)}", style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(dueStateText(d), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = if (attention) Color(0xFFFFB4B4) else Color(0xFFB9F6CA))
                }
            }
        }
    }
    }
}

/** Credit cards side by side: available, limit and utilisation. */
@Composable
private fun BalancesPanel(cards: List<CardSummary>) {
    val totalLimit = cards.sumOf { it.card.creditLimitMinor ?: 0L }
    val totalAvail = cards.sumOf { it.latestBalanceMinor ?: 0L }
    val overall = Utilisation(totalLimit, totalAvail)
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Available credit")
        Text(fmtMoney(totalAvail), style = MaterialTheme.typography.headlineMedium, color = Ink.green)
        Text("of ${fmtMoney(totalLimit)} · ${overall.percent}% used", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        Spacer(Modifier.height(10.dp))
        cards.forEach { s ->
            val u = Utilisation(s.card.creditLimitMinor ?: 0L, s.latestBalanceMinor ?: 0L)
            Column(Modifier.padding(vertical = 4.dp)) {
                Row {
                    Text(CardArts.displayName(s.card), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${u.percent}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                ShareBar(u.percent / 100f, utilColor(u.percent))
                Text(
                    "Available ${fmtMoney(u.availableMinor)} · used ${fmtMoney(u.usedMinor)} of ${fmtMoney(u.limitMinor)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.muted,
                )
            }
        }
    }
}

private fun utilColor(pct: Int) = when {
    pct < 30 -> Ink.green
    pct < 70 -> Ink.amber
    else -> Ink.red
}

@Composable
private fun CardFigures(s: CardSummary, periodLabel: String) {
    if (s.card.cardType == CardTypes.ACCOUNT) {
        Text("$periodLabel: in ${fmtMoney(s.monthInMinor)} · out ${fmtMoney(s.monthOutMinor)} · ${s.monthTxnCount} txns")
    } else {
        val suffix = if (s.card.countInSpending) "" else " (not in totals)"
        Text("$periodLabel: ${fmtMoney(s.monthSpendAedMinor)} · ${s.monthTxnCount} txns$suffix")
    }
    if (s.monthPaidInMinor > 0) {
        Text("Payments received: ${fmtMoney(s.monthPaidInMinor)}", style = MaterialTheme.typography.bodyMedium, color = Ink.green)
    }
    s.latestBalanceMinor?.let {
        val label = if (s.card.cardType == CardTypes.ACCOUNT) "Balance" else "Available"
        Text("$label (latest SMS): ${fmtMoney(it)}", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
    }
}

fun dueStateText(d: CardDue): String = when (d.status.state) {
    DueState.PAID -> "Paid in full"
    DueState.MIN_PAID -> "Minimum paid · ${fmtMoney(d.status.remainingMinor, d.statement.currency)} still to pay"
    DueState.OVERDUE -> "Overdue by ${-d.status.daysLeft} days"
    DueState.NOTHING_DUE -> "Nothing due"
    DueState.UNPAID -> when {
        d.status.daysLeft == 0L -> "Due today"
        d.status.daysLeft == 1L -> "Due tomorrow"
        else -> "Due in ${d.status.daysLeft} days"
    }
}

@Composable
private fun DueLines(d: CardDue?) {
    d ?: return
    val st = d.statement
    Spacer(Modifier.height(8.dp))
    Text("Statement balance ${fmtMoney(st.balanceMinor, st.currency)}", style = MaterialTheme.typography.bodyMedium)
    st.minimumDueMinor?.let { Text("Minimum due ${fmtMoney(it, st.currency)}", style = MaterialTheme.typography.bodyMedium) }
    Text("Due ${fmtEpochDay(st.dueDateEpochDay)}", style = MaterialTheme.typography.bodyMedium)
    if (d.status.paidMinor > 0) {
        Text("Paid so far ${fmtMoney(d.status.paidMinor, st.currency)}", style = MaterialTheme.typography.bodyMedium)
    }
    val attention = d.status.state == DueState.UNPAID || d.status.state == DueState.OVERDUE
    Text(
        dueStateText(d),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (attention) Ink.red else Ink.green,
    )
}

/** Thin horizontal meter (kept for older call sites): primary colour on a muted track, 4dp rounded ends. */
@Composable
fun MeterBar(fraction: Float, modifier: Modifier = Modifier) = ShareBar(fraction, Ink.green, modifier)

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** Card profile: tile, balance history, statement status, payments, then settings. */
@Composable
fun CardDetailScreen(
    summary: CardSummary?,
    periodLabel: String,
    onSetType: (CardType) -> Unit,
    onToggleCounted: (Boolean) -> Unit,
    onShowTransactions: () -> Unit,
    onSaveProfile: (nickname: String, limit: String, statementDay: String, dueDay: String, reminders: Boolean) -> Unit,
    onSetTheme: (String?) -> Unit = {},
    onPickImage: () -> Unit = {},
) {
    if (summary == null) {
        Text("Card not found.", Modifier.padding(24.dp))
        return
    }
    val c = summary.card
    var nickname by rememberSaveable(c.cardKey) { mutableStateOf(c.nickname ?: "") }
    var limit by rememberSaveable(c.cardKey) { mutableStateOf(c.creditLimitMinor?.let { Money.fromMinor(it).toPlainString() } ?: "") }
    var statementDay by rememberSaveable(c.cardKey) { mutableStateOf(c.statementDay?.toString() ?: "") }
    var dueDay by rememberSaveable(c.cardKey) { mutableStateOf(c.dueDay?.toString() ?: "") }
    var reminders by rememberSaveable(c.cardKey) { mutableStateOf(c.remindersEnabled) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CardTile(summary, periodLabel, onClick = null, onToggle = null) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowTransactions, shape = RoundedCornerShape(50)) { Text("Show transactions") }
            }
        }
        // --- balance history
        if (summary.balanceHistory.size >= 2) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    val isAccount = c.cardType == CardTypes.ACCOUNT
                    Text(if (isAccount) "Balance history" else "Available limit history", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    AreaChart(
                        points = summary.balanceHistory.map { (d, v) -> ChartPoint(d.format(shortDate), d.format(dateFmt), v) },
                        idleTitle = "Latest",
                        idleValue = fmtMoney(summary.balanceHistory.last().second),
                        color = if (isAccount) Ink.green else Ink.violet,
                    )
                }
            }
        }
        // --- status
        item {
            Panel(Modifier.fillMaxWidth()) {
                CardFigures(summary, periodLabel)
                if (c.cardType == CardTypes.CREDIT && c.creditLimitMinor != null && summary.latestBalanceMinor != null) {
                    val u = Utilisation(c.creditLimitMinor, summary.latestBalanceMinor)
                    Spacer(Modifier.height(8.dp))
                    Text("Utilisation ${u.percent}% · outstanding ${fmtMoney(u.usedMinor)}", style = MaterialTheme.typography.bodyMedium)
                    ShareBar(u.percent / 100f, utilColor(u.percent))
                }
                DueLines(summary.due)
            }
        }
        summary.due?.let { d ->
            if (d.payments.isNotEmpty()) {
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("Payments to this card", style = MaterialTheme.typography.titleMedium)
                        d.payments.sortedByDescending { it.date }.take(12).forEach { p ->
                            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.date.format(dateFmt))
                                    Text(
                                        if (p.fromBankSms) "confirmed by the card's bank" else "transfer from your account",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Ink.muted,
                                    )
                                }
                                Text(fmtMoney(p.amountMinor, d.statement.currency), fontWeight = FontWeight.SemiBold, color = Ink.green)
                            }
                        }
                    }
                }
            }
        }
        // --- look
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text("Card look", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Best: use your own picture of the card, e.g. a screenshot of it from Samsung Wallet or the bank's website, cropped to the card. " +
                        "Or pick a drawn look below (Automatic uses this card's last 4 digits or the bank's colours).",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickImage, shape = RoundedCornerShape(50)) {
                        Icon(Icons.Filled.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                        Text(if (c.themeKey?.startsWith(CardImages.PREFIX) == true) "Change picture" else "Use my picture")
                    }
                    if (c.themeKey?.startsWith(CardImages.PREFIX) == true) {
                        OutlinedButton(onClick = { onSetTheme(null) }, shape = RoundedCornerShape(50)) { Text("Remove") }
                    }
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSetTheme(null) }) {
                            Box(
                                Modifier.size(64.dp, 40.dp).clip(RoundedCornerShape(8.dp)).background(Ink.surfaceHigh)
                                    .border(if (c.themeKey == null) 2.5.dp else 1.dp, if (c.themeKey == null) Ink.green else Ink.border, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text("Auto", style = MaterialTheme.typography.labelMedium) }
                            Text("Automatic", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Ink.muted, maxLines = 1)
                        }
                    }
                    items(CardArts.presets, key = { it.key }) { a ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable { onSetTheme(a.key) }) {
                            CardSwatch(a, selected = c.themeKey == a.key)
                            Text(a.name, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        // --- settings
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text("Card type", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Credit", c.cardType == CardTypes.CREDIT, onClick = { onSetType(CardType.CREDIT) })
                    Pill("Debit", c.cardType == CardTypes.DEBIT, onClick = { onSetType(CardType.DEBIT) })
                    Pill("Account", c.cardType == CardTypes.ACCOUNT, onClick = { onSetType(CardType.ACCOUNT) })
                }
                Text(
                    "Changing the type resets \"Show & count\" to its default (on for credit, off for debit and accounts).",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink.muted,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show & count", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "When off, this card is hidden from the Transactions tab and left out of all totals and charts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.muted,
                        )
                    }
                    Switch(checked = c.countInSpending, onCheckedChange = onToggleCounted)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text("Profile", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (c.cardType == CardTypes.CREDIT) {
                    OutlinedTextField(
                        limit, { limit = it }, label = { Text("Credit limit (AED)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            statementDay, { statementDay = it }, label = { Text("Statement day") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            dueDay, { dueDay = it }, label = { Text("Due day") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Due-date reminders for this card", modifier = Modifier.weight(1f))
                        Switch(checked = reminders, onCheckedChange = { reminders = it })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onSaveProfile(nickname, limit, statementDay, dueDay, reminders) }, shape = RoundedCornerShape(50)) { Text("Save") }
            }
        }
    }
}

// ---------------------------------------------------------------- review tab

@Composable
fun ReviewScreen(
    failed: List<SmsEntity>,
    counts: Map<String, Int>,
    onDismiss: (Long) -> Unit,
    onReparse: () -> Unit,
    onShare: () -> Unit,
) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onReparse) { Text("Re-parse all SMS") }
                    OutlinedButton(onClick = onShare, enabled = failed.isNotEmpty()) { Text("Share unparsed SMS") }
                }
            }
        }
        if (failed.isEmpty()) item { Text("Nothing to review.", fontWeight = FontWeight.Medium) }
        items(failed, key = { it.id }) { s ->
            Panel(Modifier.fillMaxWidth()) {
                Column {
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
    vm: MainViewModel,
    liveOn: Boolean,
    lastSyncAt: Long?,
    onLiveToggle: (Boolean) -> Unit,
    onShowSamsungTip: () -> Unit,
    onRemindersToggle: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onOpenRates: () -> Unit,
    biometricAvailable: Boolean,
    versionName: String,
    onAlertsToggle: (Boolean) -> Unit = {},
    onOpenFixed: () -> Unit = {},
    onExportReport: () -> Unit = {},
) {
    val themeId by vm.themeId.collectAsStateWithLifecycle()
    val remindersOn by vm.remindersOn.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val bioOn by vm.biometricOn.collectAsStateWithLifecycle()
    val timeout by vm.lockTimeoutMs.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }

    if (settingPin) {
        SetPinDialog(onSet = { pin -> if (vm.enableLock(pin)) settingPin = false }, onDismiss = { settingPin = false })
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Restore a backup?") },
            text = { Text("SMS, typed entries and settings from the backup are added to what's already here (nothing is deleted). Then everything is re-parsed.") },
            confirmButton = { TextButton(onClick = { confirmImport = false; onImport() }) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ThemePicker(themeId) { vm.setTheme(it) } }
        item { HorizontalDivider(color = Ink.border) }
        item { SettingSwitch("Live listening", "Capture bank SMS the moment they arrive. When off, nothing runs in the background: use Sync instead.", liveOn, onLiveToggle) }
        if (liveOn) item { TextButton(onClick = onShowSamsungTip) { Text("Samsung: keep the app from sleeping") } }
        item {
            SettingSwitch(
                "Due-date reminders",
                "Notification 3 days before, 1 day before and on the due day while the minimum isn't paid. Checked twice a day in the background while on.",
                remindersOn, onRemindersToggle,
            )
        }
        item { AlertsSettings(vm, onAlertsToggle) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenFixed) { Text("Fixed payments") }
                OutlinedButton(onClick = onExportReport) { Text("Export report") }
            }
        }
        item { HorizontalDivider() }
        item {
            SettingSwitch("App lock", "PIN (4–8 digits), with fingerprint/face if available. Locks again after the app has been in the background.", lockOn) { on ->
                if (on) settingPin = true else vm.disableLock()
            }
        }
        if (lockOn) {
            item {
                Text("Lock again after", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0L to "Immediately", 60_000L to "1 min", 300_000L to "5 min", 900_000L to "15 min").forEach { (ms, label) ->
                        FilterChip(selected = timeout == ms, onClick = { vm.setLockTimeout(ms) }, label = { Text(label) })
                    }
                }
            }
            if (biometricAvailable) item { SettingSwitch("Use fingerprint / face", "PIN still works as a fallback.", bioOn) { vm.setBiometric(it) } }
            item { TextButton(onClick = { settingPin = true }) { Text("Change PIN") } }
        }
        item { HorizontalDivider() }
        item {
            Text("Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                if (lastSyncAt == null) "Never synced. The first Sync imports all existing bank SMS."
                else "Last successful sync: ${fmtDateTime(lastSyncAt)}. The next Sync reads from there onward.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { vm.resetSyncPointer() }) { Text("Re-read inbox") }
                OutlinedButton(onClick = { vm.reparseAll() }) { Text("Re-parse all SMS") }
            }
        }
        item {
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Text(
                "A .zip of CSV files: raw SMS, typed entries, cards, categories, learned rules, goals and rates. all_transactions.csv opens in Excel.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = onExport) { Text("Export backup") }
                OutlinedButton(onClick = { confirmImport = true }) { Text("Restore backup") }
            }
        }
        item {
            Text("Currencies", style = MaterialTheme.typography.titleMedium)
            Text("All reports are in AED. Foreign amounts use your exchange rates.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenRates, modifier = Modifier.padding(top = 6.dp)) { Text("Exchange rates") }
        }
        item { HorizontalDivider() }
        item {
            Text(
                "Card Tracker $versionName · all data stays on this phone",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
