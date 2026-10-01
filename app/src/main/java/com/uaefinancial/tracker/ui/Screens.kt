package com.uaefinancial.tracker.ui

import com.uaefinancial.tracker.toJava
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
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
import com.uaefinancial.tracker.core.Period
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uaefinancial.tracker.core.DueState
import com.uaefinancial.tracker.core.Utilisation
import com.uaefinancial.tracker.data.CardDue
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import com.uaefinancial.tracker.data.CardEntity
import com.uaefinancial.tracker.data.CategoryEntity
import com.uaefinancial.tracker.data.CardTypes
import com.uaefinancial.tracker.data.SmsEntity
import com.uaefinancial.tracker.data.TransactionEntity
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.Money
import com.uaefinancial.tracker.parser.TxnType
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
private val wholeFmt = ThreadLocal.withInitial { DecimalFormat("#,##0", DecimalFormatSymbols(Locale.ENGLISH)) }

/**
 * Your home currency, as the screens see it: every amount is totalled and shown in it. Reading it in a screen redraws
 * the screen when you change it (More → Home currency).
 */
object Home {
    var code: String by androidx.compose.runtime.mutableStateOf(com.uaefinancial.tracker.parser.SmsParser.homeCurrency)
}

fun fmtMoney(minor: Long, currency: String = Home.code) = "$currency ${fmtAmount(minor, currency)}"

/** 1,234.56 without a currency (whole numbers for currencies without cents, like JPY or IDR). */
fun fmtAmount(minor: Long, currency: String = Home.code): String {
    val f = if (currency in com.uaefinancial.tracker.parser.Currencies.noDecimals) wholeFmt else moneyFmt
    return f.get()!!.format(Money.fromMinor(minor).toJava())
}

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
        // ---- add + sync status
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(onClick = { adding = true }, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add", maxLines = 1, softWrap = false)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            syncing -> "Importing bank messages…"
                            lastSyncAt == null -> "Not synced yet: tap ↻ to import"
                            else -> "Synced ${fmtDateTime(lastSyncAt)}"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (liveOn) "New messages are recorded automatically" else "Automatic recording off",
                        style = MaterialTheme.typography.bodySmall, color = Ink.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (lastSyncAt == null && !syncing) OutlinedButton(onClick = onSync, shape = RoundedCornerShape(50)) { Text("Sync") }
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
                placeholder = { Text("Search merchant, category, card, amount", color = Ink.faint) },
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
                        lastSyncAt == null -> "Tap ↻ at the top to import your bank messages, or Add to type one."
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
                        style = MaterialTheme.typography.labelLarge, color = Ink.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (daySpend != 0L) Text(fmtMoney(daySpend), style = MaterialTheme.typography.labelLarge, color = Ink.faint, maxLines = 1, softWrap = false)
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
            "${txns.size} transactions · " + if (account.countInSpending) "counted as spending: ${fmtMoney(spend)}"
            else "not counted in spending",
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
        val text = fmtMoney(minor)
        Text(
            text, style = if (text.length <= 13) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            color = Ink.text, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
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
        Spacer(Modifier.height(8.dp))
        // Small stat chips instead of one long sentence, so nothing is squeezed onto a half-empty second line.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatChip("Transactions", txns.size.toString())
            if (payments > 0) StatChip("Card payments", fmtMoney(payments))
            if (moneyIn > 0) StatChip("Account in", fmtMoney(moneyIn))
            if (moneyOut > 0) StatChip("Account out", fmtMoney(moneyOut))
            if (notCounted > 0) StatChip("Not in total", "$notCounted txns")
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Ink.surfaceHigh).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.muted, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, color = Ink.text, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
                Text(
                    "Type it like you'd say it: \"lunch 45\", \"taxi 30 aed\", \"usd 20 netflix #1234\" (#1234 = card's last 4 digits), \"refund amazon 50\".",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
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
            applyLabel = if (com.uaefinancial.tracker.parser.CategoryRules.isAmountSpecific(t.merchant, t.txnType()))
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
    val canCategorise = spendType || t.type == TxnType.TRANSFER_OUT.name || t.type == TxnType.TRANSFER_IN.name
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (counted) color else Ink.faint, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.merchant, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    (t.type == TxnType.TRANSFER_OUT.name || t.type == TxnType.TRANSFER_IN.name) && t.categoryId != null -> "${catNames[t.categoryId] ?: ""} · ${kind ?: ""}"
                    else -> kind ?: ""
                }
                val tags = listOfNotNull(first.ifBlank { null }, card, if (t.source == "MANUAL") "typed" else null, if (!counted) "not counted" else null)
                Text(tags.joinToString(" • "), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                val out = t.type == TxnType.PURCHASE.name || t.type == TxnType.TRANSFER_OUT.name
                val amtColor = when {
                    !counted -> Ink.muted
                    out -> Ink.text
                    else -> Ink.green
                }
                // Home-currency amounts without the currency (everything is in it unless shown), so the row stays on one line.
                val amountText = if (t.currency == Home.code) fmtAmount(t.amountMinor) else fmtMoney(t.amountMinor, t.currency)
                Text((if (out) "−" else "+") + amountText, color = amtColor, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Text(
                    if (t.currency != Home.code) (t.amountAedMinor?.let { "≈ " + fmtMoney(it) } ?: "no rate")
                    else Instant.ofEpochMilli(t.timestamp).atZone(ZoneId.systemDefault()).format(timeFmt),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                    color = Ink.faint,
                    maxLines = 1,
                    softWrap = false,
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
            when {
                t.ruleId?.startsWith("auto-") == true -> Text("Read by the smart reader: check it looks right.", style = MaterialTheme.typography.bodySmall, color = Ink.faint)
                t.ruleId == com.uaefinancial.tracker.data.Repository.FIX_RULE -> Text("Your fix from Needs review.", style = MaterialTheme.typography.bodySmall, color = Ink.faint)
                t.ruleId == com.uaefinancial.tracker.parser.LearnedFormats.RULE_ID -> Text("Read like a similar message you fixed.", style = MaterialTheme.typography.bodySmall, color = Ink.faint)
            }
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
    onImportStatement: () -> Unit = {},
    onAddCard: () -> Unit = {},
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
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("No cards yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Your cards and bank accounts appear here after their first bank SMS is imported. You can also add one yourself.",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(onClick = onAddCard, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 10.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add a card or account")
                    }
                }
            }
        }
        if (!arranging) {
            val unbilled = summaries.filter { it.sinceStatementStart != null && it.card.countInSpending }
            if (unbilled.isNotEmpty()) item { SinceStatementPanel(unbilled, onOpenCard) }
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
                        IconButton(onClick = onImportStatement) { Icon(Icons.Filled.PictureAsPdf, "Check a statement PDF from any bank", tint = Ink.text) }
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
            Eyebrow(typeLabel(c.cardType) + if (c.owner == com.uaefinancial.tracker.data.CardOwner.FAMILY) " · family" else "", color = fgMuted, modifier = Modifier.weight(1f))
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
                    s.sinceStatementStart?.let { d ->
                        Text("Since statement (${d.format(dateFmt)}): ${fmtMoney(s.sinceStatementMinor)}", style = MaterialTheme.typography.bodySmall,
                            color = fg, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
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

/** Spend on each credit card since its last statement: what goes on the next one. */
@Composable
private fun SinceStatementPanel(cards: List<CardSummary>, onOpenCard: (String) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Since last statement")
        Text(fmtMoney(cards.sumOf { it.sinceStatementMinor }), style = MaterialTheme.typography.headlineMedium, color = Ink.green, maxLines = 1)
        Text("Spent on your credit cards since each card's last statement (goes on the next one).", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        cards.sortedByDescending { it.sinceStatementMinor }.forEach { s ->
            Row(Modifier.fillMaxWidth().clickable { onOpenCard(s.card.cardKey) }.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CardThumb(s.card, 44.dp, 28.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(CardArts.displayName(s.card), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("since ${s.sinceStatementStart?.format(dateFmt)} · ${s.sinceStatementCount} spends", style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 1)
                }
                Text(fmtAmount(s.sinceStatementMinor), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
        Text("$label (${s.balanceBasis ?: "latest SMS"}): ${fmtMoney(it)}", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
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
    onCheckStatement: () -> Unit = {},
    onShowSinceStatement: (LocalDate) -> Unit = {},
    onSetFamily: (Boolean) -> Unit = {},
    onDelete: () -> Unit = {},
) {
    if (summary == null) {
        Text("Card not found.", Modifier.padding(24.dp))
        return
    }
    val c = summary.card
    var nickname by rememberSaveable(c.cardKey) { mutableStateOf(c.nickname ?: "") }
    var limit by rememberSaveable(c.cardKey) { mutableStateOf(c.creditLimitMinor?.let { Money.fromMinor(it).toPlainString() } ?: "") }
    // Pre-filled from the statement SMS of the last 30 days when you haven't set them; you can still change them.
    val fromSms = remember(summary.latestStatement?.id) {
        summary.latestStatement?.let { st ->
            val received = Instant.ofEpochMilli(st.receivedAt).atZone(ZoneId.systemDefault()).toLocalDate()
            com.uaefinancial.tracker.core.CardDays.fromStatements(
                listOf(Triple(st.statementDateEpochDay?.let { LocalDate.ofEpochDay(it) }, LocalDate.ofEpochDay(st.dueDateEpochDay), received)),
                LocalDate.now(),
            )
        }
    }
    var statementDay by rememberSaveable(c.cardKey, c.statementDay) { mutableStateOf((c.statementDay ?: fromSms?.statementDay)?.toString() ?: "") }
    var dueDay by rememberSaveable(c.cardKey, c.dueDay) { mutableStateOf((c.dueDay ?: fromSms?.dueDay)?.toString() ?: "") }
    var reminders by rememberSaveable(c.cardKey) { mutableStateOf(c.remindersEnabled) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { CardTile(summary, periodLabel, onClick = null, onToggle = null) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShowTransactions, shape = RoundedCornerShape(50)) { Text("Transactions", maxLines = 1) }
                OutlinedButton(onClick = onCheckStatement, shape = RoundedCornerShape(50)) {
                    Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Check statement", maxLines = 1)
                }
            }
        }
        // --- usage since the last statement
        summary.sinceStatementStart?.let { start ->
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Eyebrow("Since last statement · ${start.format(dateFmt)} to today")
                    Text(fmtMoney(summary.sinceStatementMinor), style = MaterialTheme.typography.headlineSmall, color = Ink.green, maxLines = 1)
                    Text(
                        "${summary.sinceStatementCount} spends, will appear on the next statement" +
                            (c.creditLimitMinor?.let { l -> " · ${if (l > 0) summary.sinceStatementMinor * 100 / l else 0}% of your limit" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                    )
                    TextButton(onClick = { onShowSinceStatement(start) }) { Text("Show these transactions") }
                }
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
                    "Use your own picture of the card (e.g. a screenshot from your wallet app or the bank's website, cropped to the card), " +
                        "or pick a look below. Automatic uses the bank's colour.",
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
                            "When off, this card is hidden from the Activity tab and left out of all totals and charts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ink.muted,
                        )
                    }
                    Switch(checked = c.countInSpending, onCheckedChange = onToggleCounted)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Someone else's card I pay for", style = MaterialTheme.typography.titleSmall)
                        Text("e.g. a family member's card. Its spends go to the \"Family\" category.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    }
                    Switch(checked = c.owner == com.uaefinancial.tracker.data.CardOwner.FAMILY, onCheckedChange = onSetFamily)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text("Profile", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (c.cardType == CardTypes.CREDIT) {
                    OutlinedTextField(
                        limit, { limit = it }, label = { Text("Credit limit (${Home.code})") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
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
                    fromSms?.let { d ->
                        val same = statementDay == d.statementDay?.toString() && dueDay == d.dueDay.toString()
                        Text(
                            if (same) "Filled from the statement SMS of ${d.fromDate.format(dateFmt)}. Change them if needed."
                            else "Statement SMS of ${d.fromDate.format(dateFmt)} says: statement day ${d.statementDay ?: "?"}, due day ${d.dueDay}.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp),
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
        if (summary.monthTxnCount == 0 && summary.latestStatement == null && summary.balanceHistory.isEmpty()) {
            item {
                TextButton(onClick = onDelete) { Text("Remove this card", color = Ink.red) }
            }
        }
    }
}
