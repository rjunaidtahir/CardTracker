package com.uaefinancial.tracker.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uaefinancial.tracker.core.StatementLine

/**
 * "Check against statement": pick the bank's statement PDF for this card, see what matches,
 * what's missing from the app (tick and add), and what's in the app but not on the statement.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StatementCheckScreen(vm: MainViewModel) {
    val st by vm.statementCheck.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val summaries by vm.cardSummaries.collectAsStateWithLifecycle()
    val card = cards.firstOrNull { it.cardKey == st?.cardKey }
    val cardName = card?.let { CardArts.displayName(it) } ?: "Any bank statement"
    val ctx = LocalContext.current
    var password by rememberSaveable { mutableStateOf("") }
    var pickedUri by rememberSaveable { mutableStateOf<String?>(null) }
    var showMatched by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { pickedUri = uri.toString(); password = ""; vm.checkStatement(uri, null) }
    }
    val result = st?.result
    // Ticked "missing" lines, reset whenever a new result arrives.
    val selected = remember(result) { mutableStateListOf<StatementLine>().apply { result?.missing?.let { addAll(it) } } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(cardName, style = MaterialTheme.typography.titleLarge)
            Text(
                "Pick a statement PDF from any bank, including a card you pay for someone else (e.g. a family member's). The app reads it on the phone " +
                    "(nothing is uploaded), checks the statement date, due date, credit limit and amounts due, matches every line with your " +
                    "transactions by date (±3 days) and amount, and lists what's missing so you can add it.",
                style = MaterialTheme.typography.bodySmall, color = Ink.muted,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { picker.launch(arrayOf("application/pdf")) }, shape = RoundedCornerShape(50), enabled = st?.loading != true) {
                Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(if (pickedUri == null) "Choose statement PDF" else "Choose another PDF")
            }
        }
        if (st?.loading == true) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading the statement…") } }
        if (st?.needsPassword == true) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("This PDF has a password", style = MaterialTheme.typography.titleSmall)
                    Text("UAE banks usually use part of your date of birth, mobile number or card number. It's only used to open the file.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    OutlinedTextField(
                        password, { password = it }, label = { Text("PDF password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    Button(onClick = { pickedUri?.let { vm.checkStatement(Uri.parse(it), password) } }, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 8.dp)) { Text("Open") }
                }
            }
        }
        st?.error?.let { e ->
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(e, color = Ink.red)
                    if (st?.text != null) {
                        Text(
                            "This bank's layout isn't recognised yet. Share the extracted text with Claude (remove anything private first) so a rule can be added.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                        )
                        TextButton(onClick = { shareText(ctx, st?.text ?: "") }) { Text("Share extracted text") }
                    }
                }
            }
        }
        // ---- which card is this statement for?
        if (st != null && st?.loading != true && (st?.lines?.isNotEmpty() == true || st?.summary?.isEmpty == false)) {
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text(if (card == null) "Which card is this statement for?" else "Card", style = MaterialTheme.typography.titleSmall)
                    st?.summary?.let { sm ->
                        if (sm.bank != null || sm.cardLast4 != null) {
                            Text("The statement says: ${listOfNotNull(sm.bank, sm.cardLast4?.let { "card ending $it" }).joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        cards.filter { it.cardType != com.uaefinancial.tracker.data.CardTypes.ACCOUNT || it.cardKey == st?.cardKey }.forEach { c ->
                            Pill(CardArts.displayName(c), c.cardKey == st?.cardKey, onClick = { vm.chooseStatementCard(c.cardKey) }, tint = Ink.violet)
                        }
                        cards.filter { it.cardType == com.uaefinancial.tracker.data.CardTypes.ACCOUNT && it.cardKey != st?.cardKey }.forEach { c ->
                            Pill(CardArts.displayName(c), false, onClick = { vm.chooseStatementCard(c.cardKey) }, tint = Ink.violet)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    NewStatementCard(st?.summary, onCreate = { b, l, f, n -> vm.createStatementCard(b, l, f, n) })
                    if (card != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Someone else's card I pay for", style = MaterialTheme.typography.bodyMedium)
                                Text("Its spends go to the \"Family\" category, separate from your own.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                            }
                            androidx.compose.material3.Switch(card.owner == com.uaefinancial.tracker.data.CardOwner.FAMILY, { vm.setCardFamily(card.cardKey, it) })
                        }
                    }
                }
            }
        }
        // ---- key figures: statement vs app
        val sm = st?.summary
        if (sm != null && !sm.isEmpty && card != null) {
            item {
                val cs = summaries.firstOrNull { it.card.cardKey == card.cardKey }
                val appSt = cs?.latestStatement
                Panel(Modifier.fillMaxWidth()) {
                    Text(if (sm.isAccount) "Account statement" else "Statement figures", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (sm.isAccount) "Read from the statement." else "Compared with what the app has from SMS and the card profile.",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (sm.isAccount) {
                        FigureRow("Period", listOfNotNull(sm.periodFrom?.format(dateFmt), sm.periodTo?.format(dateFmt)).joinToString(" – ").ifEmpty { null }, null, showApp = false)
                        FigureRow("Opening balance", sm.previousBalanceMinor?.let { fmtMoney(it) }, null, showApp = false)
                        FigureRow("Closing balance", sm.closingBalanceMinor?.let { fmtMoney(it) }, cs?.latestBalanceMinor?.let { fmtMoney(it) })
                    } else {
                    FigureRow("Statement date", sm.statementDate?.format(dateFmt), appSt?.statementDateEpochDay?.let { fmtEpochDay(it) })
                    FigureRow("Payment due date", sm.dueDate?.format(dateFmt), appSt?.dueDateEpochDay?.let { fmtEpochDay(it) })
                    FigureRow("Amount due (avoid finance charges)", sm.totalDueMinor?.let { fmtMoney(it) }, appSt?.balanceMinor?.let { fmtMoney(it, appSt.currency) })
                    FigureRow("Minimum due", sm.minimumDueMinor?.let { fmtMoney(it) }, appSt?.minimumDueMinor?.let { fmtMoney(it, appSt.currency) })
                    FigureRow("Total credit limit", sm.creditLimitMinor?.let { fmtMoney(it) }, card.creditLimitMinor?.let { fmtMoney(it) })
                    FigureRow("Available limit", sm.availableLimitMinor?.let { fmtMoney(it) }, cs?.latestBalanceMinor?.let { fmtMoney(it) })
                    FigureRow("Previous balance", sm.previousBalanceMinor?.let { fmtMoney(it) }, null, showApp = false)
                    }
                    // Does the transaction list add up to the statement's own totals?
                    st?.totalsCheck?.let { msg ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            (if (st?.totalsAgree == true) "✓ " else "! ") + msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (st?.totalsAgree == true) Ink.green else Ink.amber,
                        )
                    }
                    if (st?.otherCards?.isNotEmpty() == true) {
                        Text(
                            "Also includes card " + st!!.otherCards.keys.joinToString(", ") { "·$it" } + " (supplementary): its lines are compared with that card.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    if (sm.isAccount) {
                        // nothing to save for an account
                    } else if (st?.summarySaved?.isNotEmpty() == true) {
                        Text("Saved: " + st!!.summarySaved.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Ink.green)
                    } else {
                        Button(onClick = { vm.applyStatementSummary() }, shape = RoundedCornerShape(50)) { Text("Save to card profile") }
                        Text(
                            "Sets the credit limit and statement / due day, and adds this statement to Payments due if no SMS statement has that due date.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.faint, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (result != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountTile("Matched", result.matched.size, Ink.green, Modifier.weight(1f))
                    CountTile("Missing in app", result.missing.size, Ink.amber, Modifier.weight(1f))
                    CountTile("Only in app", result.extra.size, Ink.violet, Modifier.weight(1f))
                }
                if (result.from != null && result.to != null) {
                    Text("Statement lines ${result.from.format(dateFmt)} – ${result.to.format(dateFmt)} · ${st?.lineCount ?: 0} read",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (result.missing.isNotEmpty()) {
                item { SectionHeader("On the statement, not in the app") }
                item {
                    Panel(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 6.dp)) {
                        result.missing.forEach { l ->
                            val on = l in selected
                            Row(
                                Modifier.fillMaxWidth().clickable { if (on) selected.remove(l) else selected.add(l) }.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(on, { c -> if (c) selected.add(l) else selected.remove(l) })
                                Column(Modifier.weight(1f)) {
                                    Text(l.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(l.date.format(dateFmt) + if (l.isCredit) " · credit" else "", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                                }
                                Text((if (l.isCredit) "+" else "−") + fmtAmount(l.amountMinor), fontWeight = FontWeight.SemiBold,
                                    color = if (l.isCredit) Ink.green else Ink.text, maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 8.dp))
                            }
                        }
                    }
                }
                item {
                    Button(onClick = { vm.addFromStatement(selected.toList()) }, enabled = selected.isNotEmpty(), shape = RoundedCornerShape(50)) {
                        Text("Add ${selected.size} to transactions")
                    }
                    Text("Added lines are saved as typed entries on this card, so they count in totals and survive Re-parse and backups.",
                        style = MaterialTheme.typography.bodySmall, color = Ink.faint, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (result.extra.isNotEmpty()) {
                item { SectionHeader("In the app, not on the statement") }
                item {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("Often pending card transactions, a duplicate SMS, or something the bank reversed. Check and delete from Transactions if wrong.",
                            style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                        result.extra.forEach { a ->
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(a.date.format(dateFmt), style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                                }
                                Text((if (a.isCredit) "+" else "−") + fmtAmount(a.amountMinor), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            if (result.matched.isNotEmpty()) {
                item {
                    TextButton(onClick = { showMatched = !showMatched }) { Text(if (showMatched) "Hide matched" else "Show ${result.matched.size} matched") }
                }
                if (showMatched) {
                    items(result.matched, key = { "${it.second.id}" }) { (l, a) ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(l.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${l.date.format(dateFmt)} · in app: ${a.label}", style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(fmtAmount(l.amountMinor), color = Ink.green, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountTile(label: String, n: Int, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Panel(modifier, padding = PaddingValues(12.dp)) {
        Text(n.toString(), style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.muted, maxLines = 2)
    }
}

private fun shareText(ctx: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.take(90_000))
    ctx.startActivity(Intent.createChooser(send, "Share statement text"))
}

/** One figure: statement value, app value, and whether they agree. */
@Composable
private fun FigureRow(label: String, statement: String?, app: String?, showApp: Boolean = true) {
    val same = statement != null && app != null && statement.filter { it.isDigit() } == app.filter { it.isDigit() }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    !showApp -> if (statement == null) "Not found on the statement" else "From the statement"
                    statement == null -> "Not found on the statement"
                    app == null -> "Not in the app yet"
                    same -> "Matches the app"
                    else -> "App has $app"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when { statement == null -> Ink.faint; !showApp -> Ink.muted; same -> Ink.green; app == null -> Ink.muted; else -> Ink.amber },
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(statement ?: "—", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Form for a card the app doesn't know (another bank, or a family member's card). */
@Composable
private fun NewStatementCard(summary: com.uaefinancial.tracker.core.StatementSummary?, onCreate: (String, String, Boolean, String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var bank by remember(summary) { mutableStateOf(summary?.bank ?: "") }
    var last4 by remember(summary) { mutableStateOf(summary?.cardLast4 ?: "") }
    var nick by remember { mutableStateOf("") }
    var family by remember { mutableStateOf(true) }
    if (!open) {
        TextButton(onClick = { open = true }) { Text("+ A card not listed (other bank / family)") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(bank, { bank = it }, label = { Text("Bank (e.g. Emirates Islamic)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4") }, singleLine = true,
                modifier = Modifier.width(110.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(nick, { nick = it }, label = { Text("Name (e.g. Sara's card)") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(family, { family = it })
            Text("Someone else's card I pay for (Family category)", style = MaterialTheme.typography.bodyMedium)
        }
        Button(onClick = { onCreate(bank, last4, family, nick); open = false }, shape = RoundedCornerShape(50)) { Text("Add card and compare") }
    }
}
