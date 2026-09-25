package com.uaefinancial.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uaefinancial.tracker.data.SmsEntity
import com.uaefinancial.tracker.parser.BankRules
import com.uaefinancial.tracker.parser.CardType
import com.uaefinancial.tracker.parser.TxnType

// ================================================================== More tab

/** Everything that isn't a daily screen: data tools, settings and help, in plain sections. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MoreScreen(
    vm: MainViewModel,
    reviewCount: Int,
    liveOn: Boolean,
    lastSyncAt: Long?,
    biometricAvailable: Boolean,
    versionName: String,
    onOpen: (Route) -> Unit,
    onLiveToggle: (Boolean) -> Unit,
    onShowBatteryTip: () -> Unit,
    onRemindersToggle: (Boolean) -> Unit,
    onAlertsToggle: (Boolean) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onExportReport: () -> Unit,
) {
    val themeId by vm.themeId.collectAsStateWithLifecycle()
    val remindersOn by vm.remindersOn.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val bioOn by vm.biometricOn.collectAsStateWithLifecycle()
    val timeout by vm.lockTimeoutMs.collectAsStateWithLifecycle()
    val name by vm.displayName.collectAsStateWithLifecycle()
    var settingPin by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf(false) }
    var confirmReparse by remember { mutableStateOf(false) }

    if (settingPin) SetPinDialog(onSet = { pin -> if (vm.enableLock(pin)) settingPin = false }, onDismiss = { settingPin = false })
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            containerColor = Ink.surface,
            title = { Text("Restore a backup?") },
            text = { Text("Messages, typed entries and settings from the backup are added to what's already here (nothing is deleted). Then everything is re-read.") },
            confirmButton = { TextButton(onClick = { confirmImport = false; onImportBackup() }) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
    if (confirmReparse) {
        AlertDialog(
            onDismissRequest = { confirmReparse = false },
            containerColor = Ink.surface,
            title = { Text("Re-read stored messages?") },
            text = { Text("Every bank SMS already stored is read again with the current rules. Your categories, fixes and typed entries are kept. Use this after updating the app.") },
            confirmButton = { TextButton(onClick = { confirmReparse = false; vm.reparseAll() }) { Text("Re-read") } },
            dismissButton = { TextButton(onClick = { confirmReparse = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // ---- data
        item {
            MenuGroup("Your data") {
                MenuRow(Icons.Filled.RateReview, "Needs review", "Bank messages the app couldn't read", badge = reviewCount) { onOpen(Route.Review) }
                MenuRow(Icons.Filled.AccountBalance, "Bank senders", "Which SMS senders are your banks; add one the app doesn't know") { onOpen(Route.Senders) }
                MenuRow(Icons.Filled.EventRepeat, "Fixed payments", "Rent, fees, loans without SMS: reminders and totals") { onOpen(Route.FixedPayments) }
                MenuRow(Icons.Filled.PictureAsPdf, "Check a statement PDF", "Compare a bank statement with what the app recorded") { onOpen(Route.StatementCheck(null)) }
                MenuRow(Icons.Filled.IosShare, "Export report", "PDF or Excel (CSV) for the selected period", onClick = onExportReport)
                MenuRow(Icons.Filled.CurrencyExchange, "Exchange rates", "Rates used to show foreign spends in AED") { onOpen(Route.Rates) }
            }
        }
        // ---- messages
        item {
            MenuGroup("Bank messages") {
                SettingSwitchRow(
                    "Record new messages automatically",
                    "Reads each bank SMS as it arrives. When off, nothing runs in the background: tap Sync (↻) to import.",
                    liveOn, onLiveToggle,
                )
                if (liveOn) TextButton(onClick = onShowBatteryTip) { Text("Keep it working: stop the phone putting the app to sleep") }
                Text(
                    if (lastSyncAt == null) "Not synced yet. The first Sync imports all bank SMS on the phone."
                    else "Last sync ${fmtDateTime(lastSyncAt)}. The next Sync reads newer messages.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { vm.resetSyncPointer() }) { Text("Re-import whole inbox", maxLines = 1) }
                    OutlinedButton(onClick = { confirmReparse = true }) { Text("Re-read stored", maxLines = 1) }
                }
            }
        }
        // ---- notifications
        item {
            MenuGroup("Notifications") {
                SettingSwitchRow(
                    "Card due-date reminders",
                    "3 days before, 1 day before and on the due day while the minimum isn't paid.",
                    remindersOn, onRemindersToggle,
                )
                Spacer(Modifier.height(12.dp))
                AlertsSettings(vm, onAlertsToggle)
            }
        }
        // ---- security
        item {
            MenuGroup("Security") {
                SettingSwitchRow("App lock", "A PIN (4–8 digits), with fingerprint or face if your phone has it.", lockOn) { on ->
                    if (on) settingPin = true else vm.disableLock()
                }
                if (lockOn) {
                    Text("Lock again after", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0L to "Immediately", 60_000L to "1 min", 300_000L to "5 min", 900_000L to "15 min").forEach { (ms, label) ->
                            FilterChip(selected = timeout == ms, onClick = { vm.setLockTimeout(ms) }, label = { Text(label) })
                        }
                    }
                    if (biometricAvailable) SettingSwitchRow("Use fingerprint / face", "The PIN still works as a fallback.", bioOn) { vm.setBiometric(it) }
                    TextButton(onClick = { settingPin = true }) { Text("Change PIN") }
                }
                Text(
                    "Everything stays on this phone. The app has no internet permission, so it can't send your data anywhere.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // ---- backup
        item {
            MenuGroup("Backup") {
                Text(
                    "A .zip you save where you like (Drive, Files, email). Includes messages, typed entries, cards, categories and settings. all_transactions.csv inside opens in Excel.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = onExportBackup) { Icon(Icons.Filled.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Back up") }
                    OutlinedButton(onClick = { confirmImport = true }) { Text("Restore") }
                }
            }
        }
        // ---- appearance
        item {
            MenuGroup("Appearance") {
                ThemePicker(themeId) { vm.setTheme(it) }
                var nameText by remember(name) { mutableStateOf(name) }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedTextField(nameText, { nameText = it }, label = { Text("Your name on reports (optional)") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.setDisplayName(nameText) }, enabled = nameText.trim() != name) { Text("Save") }
                }
            }
        }
        // ---- help
        item {
            MenuGroup("Help") {
                MenuRow(Icons.AutoMirrored.Filled.HelpOutline, "Help & questions", "How the app works, and what to do when something looks wrong") { onOpen(Route.Help) }
                MenuRow(Icons.Filled.Search, "Run the setup again", "Permissions, finding your banks, first import") { vm.restartOnboarding() }
            }
        }
        item {
            Text(
                "UAE Financial Tracker $versionName · works offline · your data stays on this phone",
                style = MaterialTheme.typography.bodySmall, color = Ink.faint, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun MenuGroup(title: String, content: @Composable () -> Unit) {
    Column {
        Eyebrow(title, Modifier.padding(start = 6.dp, bottom = 6.dp))
        Panel(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, badge: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, Ink.violet, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 2)
        }
        if (badge > 0) Badge { Text("$badge") }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Ink.faint)
    }
}

@Composable
fun SettingSwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ================================================================ Needs review

@Composable
fun ReviewScreen(vm: MainViewModel, onShare: () -> Unit) {
    val failed by vm.failedSms.collectAsStateWithLifecycle()
    val counts by vm.smsCounts.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf<SmsEntity?>(null) }
    fixing?.let { s -> FixSmsDialog(vm, s, onDismiss = { fixing = null }) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text("Messages the app couldn't read", style = MaterialTheme.typography.titleMedium)
                Text(
                    "These bank SMS mention an amount, but the app wasn't sure what they mean. For each one: Fix (tell the app what it was: " +
                        "it remembers), Not a transaction, or Dismiss. After an app update, More → Re-read stored may read them automatically.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Stored bank SMS: ${counts.values.sum()} · read as transactions ${counts["TRANSACTION"] ?: 0} · statements ${counts["STATEMENT"] ?: 0} · " +
                        "not transactions ${counts["IGNORED"] ?: 0}",
                    style = MaterialTheme.typography.bodySmall, color = Ink.faint, modifier = Modifier.padding(top = 8.dp),
                )
                if (failed.isNotEmpty()) {
                    TextButton(onClick = onShare, modifier = Modifier.padding(top = 2.dp)) { Text("Share these messages (to report a bank format)") }
                }
            }
        }
        if (failed.isEmpty()) {
            item { Muted("Nothing to review. Every bank message was read.", Modifier.padding(8.dp)) }
        }
        items(failed, key = { it.id }) { s ->
            Panel(Modifier.fillMaxWidth()) {
                Text("${s.bank ?: s.sender} · ${fmtDateTime(s.receivedAt)}", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        s.body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ink.bg).padding(10.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { fixing = s }) { Text("Fix") }
                    TextButton(onClick = { vm.markNotTransaction(s) }) { Text("Not a transaction") }
                    TextButton(onClick = { vm.dismiss(s.id) }) { Text("Dismiss", color = Ink.muted) }
                }
            }
        }
    }
}

private val fixTypes = listOf(
    TxnType.PURCHASE to "Spend",
    TxnType.REFUND to "Refund / cashback",
    TxnType.PAYMENT to "Card payment received",
    TxnType.TRANSFER_IN to "Money in",
    TxnType.TRANSFER_OUT to "Money out / transfer",
)

/** "Tell the app what this SMS was": pre-filled with the smart reader's best guess. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FixSmsDialog(vm: MainViewModel, sms: SmsEntity, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(TxnType.PURCHASE) }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("AED") }
    var merchant by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var cardType by remember { mutableStateOf(CardType.CREDIT) }
    LaunchedEffect(sms.id) {
        vm.guessFor(sms)?.let { g ->
            type = g.type
            amount = g.amount.stripTrailingZeros().toPlainString()
            currency = g.currency
            merchant = g.merchant
            last4 = g.cardLast4 ?: ""
            cardType = g.cardType
        }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("What was this?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sms.body, style = MaterialTheme.typography.bodySmall, color = Ink.muted, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (!loaded) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fixTypes.forEach { (t, label) -> Pill(label, type == t, onClick = { type = t }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        amount, { amount = it }, label = { Text("Amount") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(currency, { currency = it.take(3).uppercase() }, label = { Text("Currency") }, singleLine = true, modifier = Modifier.width(96.dp))
                }
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant / description") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Card / account last 4 digits (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Credit card", cardType == CardType.CREDIT, onClick = { cardType = CardType.CREDIT })
                    Pill("Debit card", cardType == CardType.DEBIT, onClick = { cardType = CardType.DEBIT })
                    Pill("Bank account", cardType == CardType.ACCOUNT, onClick = { cardType = CardType.ACCOUNT })
                }
                Text("The date is the day the SMS arrived.", style = MaterialTheme.typography.bodySmall, color = Ink.faint)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveFix(sms, type, amount, currency, merchant, last4, cardType); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ Bank senders

@Composable
fun SendersScreen(vm: MainViewModel, onScan: () -> Unit) {
    val senders by vm.senders.collectAsStateWithLifecycle()
    val scan by vm.scan.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showBuiltIn by rememberSaveable { mutableStateOf(false) }
    adding?.let { (s, b) -> AddSenderDialog(s, b, onAdd = { sender, bank -> vm.addSenders(listOf(sender to bank), syncAfter = true); adding = null }, onDismiss = { adding = null }) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel(Modifier.fillMaxWidth()) {
                Text("How the app finds your bank messages", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Banks send SMS from a named sender (like \"EmiratesNBD\" or \"ADCBAlert\"). The app knows the main UAE banks. " +
                        "If yours isn't picked up, scan your messages or add its sender name exactly as it shows in your Messages app.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted, modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Button(onClick = onScan, enabled = !scan.running) {
                        if (scan.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink.onAccent)
                        else Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(if (scan.running) "Scanning…" else "Scan my messages")
                    }
                    OutlinedButton(onClick = { adding = "" to "" }) { Text("Add by hand") }
                }
            }
        }
        scan.error?.let { e -> item { Text(e, color = Ink.red, style = MaterialTheme.typography.bodySmall) } }
        scan.result?.let { r ->
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Found on this phone", style = MaterialTheme.typography.titleSmall)
                    if (r.known.isEmpty()) Muted("No messages from the banks the app knows.", Modifier.padding(top = 4.dp))
                    r.known.forEach { (bank, n) ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text("✓ $bank", modifier = Modifier.weight(1f))
                            Text("$n messages", color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (r.suggestions.isNotEmpty()) {
                        Text("Look like banks, not added yet", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp))
                        r.suggestions.forEach { sug ->
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(sug.sender, fontWeight = FontWeight.SemiBold)
                                        Text("${sug.alerts} bank-like messages", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                                    }
                                    TextButton(onClick = { adding = sug.sender to guessBankName(sug.sender) }) { Text("Add") }
                                }
                                Text(sug.sample, style = MaterialTheme.typography.bodySmall, color = Ink.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else if (!scan.running) {
                        Muted("No other senders look like banks.", Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
        item { SectionHeader("Senders you added") }
        if (senders.isEmpty()) item { Muted("None yet.") }
        items(senders, key = { it.sender }) { s ->
            Panel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.sender, fontWeight = FontWeight.SemiBold)
                        Text(s.bankName, style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    }
                    IconButton(onClick = { vm.removeSender(s.sender) }) { Icon(Icons.Filled.Delete, "Remove ${s.sender}", tint = Ink.muted) }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showBuiltIn = !showBuiltIn }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Banks the app knows (${BankRules.banks.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (showBuiltIn) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
        }
        if (showBuiltIn) {
            items(BankRules.banks, key = { "bank-" + it.name }) { b ->
                Column(Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
                    Text(b.name, fontWeight = FontWeight.Medium)
                    Text(
                        "Senders: " + b.senderIds.joinToString(", ") + if (b.rules.isEmpty()) " · read by the smart reader" else " · ${b.rules.size} bank-specific formats",
                        style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                    )
                }
            }
        }
    }
}

/** "ADCB-Alerts" -> "ADCB" when it's a bank the app knows; otherwise the sender itself. */
private fun guessBankName(sender: String): String {
    val s = sender.uppercase().filter { it.isLetterOrDigit() }
    return BankRules.banks.firstOrNull { b -> b.senderIds.any { id -> id.uppercase().filter(Char::isLetterOrDigit).let { it.length >= 3 && s.contains(it) } } }?.name
        ?: sender
}

@Composable
private fun AddSenderDialog(initialSender: String, initialBank: String, onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var sender by remember { mutableStateOf(initialSender) }
    var bank by remember { mutableStateOf(initialBank) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("Add a bank sender") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sender, { sender = it }, label = { Text("Sender, as shown in Messages") }, placeholder = { Text("e.g. MyBank-Alerts") }, singleLine = true)
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank name") }, singleLine = true)
                Text(
                    "If it's a bank the app already knows (e.g. ADCB), use the same name so its formats are used. " +
                        "The app then re-imports your inbox so older messages from this sender come in too.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(sender, bank) }, enabled = sender.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ Add a card

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCardDialog(onAdd: (bank: String, last4: String, type: CardType, nickname: String, family: Boolean) -> Unit, onDismiss: () -> Unit) {
    var bank by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(CardType.CREDIT) }
    var family by remember { mutableStateOf(false) }
    val suggestions = remember(bank) {
        if (bank.length < 2) emptyList() else BankRules.banks.map { it.name }.filter { it.contains(bank, true) && !it.equals(bank, true) }.take(4)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("Add a card or account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Cards also appear by themselves after their first bank SMS. Add one here to set it up early, or for a card you pay for someone else.",
                    style = MaterialTheme.typography.bodySmall, color = Ink.muted,
                )
                OutlinedTextField(bank, { bank = it }, label = { Text("Bank") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { suggestions.forEach { n -> Pill(n, false, onClick = { bank = n }) } }
                }
                OutlinedTextField(
                    last4, { last4 = it.filter(Char::isDigit).take(4) }, label = { Text("Last 4 digits") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Credit card", type == CardType.CREDIT, onClick = { type = CardType.CREDIT })
                    Pill("Debit card", type == CardType.DEBIT, onClick = { type = CardType.DEBIT })
                    Pill("Bank account", type == CardType.ACCOUNT, onClick = { type = CardType.ACCOUNT })
                }
                OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == CardType.CREDIT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Someone else's card I pay for", style = MaterialTheme.typography.bodyMedium)
                            Text("Its spends go to the Family category.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                        }
                        Switch(family, { family = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(bank, last4, type, nickname, family); onDismiss() }, enabled = bank.isNotBlank() && (last4.isEmpty() || last4.length == 4)) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ================================================================ Help

private val helpItems = listOf(
    "How does the app know what I spend?" to
        "Your bank sends an SMS for every card spend, payment and transfer. The app reads those messages on your phone and turns them into transactions. " +
        "Tap Sync (↻) to import new ones, or turn on \"Record new messages automatically\" in More.",
    "Is my data safe?" to
        "Yes. The app has no internet access at all, so nothing can leave your phone. It only reads SMS from bank senders, and never stores one-time passwords (OTPs). " +
        "Backups are files you save yourself.",
    "Android says the SMS permission is restricted" to
        "Android blocks SMS access for apps installed from a file. Open Settings → Apps → UAE Financial Tracker → ⋮ (top right) → Allow restricted settings, " +
        "then come back and allow SMS.",
    "My bank's messages don't show up" to
        "More → Bank senders → Scan my messages. The app lists senders whose messages look like bank alerts; tap Add. " +
        "You can also add a sender by hand, typed exactly as it shows in your Messages app.",
    "A transaction is wrong" to
        "Tap it on the Activity tab to see the original SMS. Change its category there, or delete it. Messages the app couldn't read are in More → Needs review, " +
        "where Fix lets you tell the app what they were (it remembers).",
    "What counts as spending?" to
        "Purchases, minus refunds and cashback. Paying off a card, transfers between your accounts and money coming in never count. " +
        "Each card has a \"Show & count\" switch: debit cards and bank accounts are off by default, so money isn't counted twice when you pay a card from your account.",
    "Foreign currency" to
        "Spends in other currencies are shown in AED with approximate rates (marked ≈). Set your own rates in More → Exchange rates.",
    "Card due dates and statements" to
        "When your bank sends a statement SMS, the card shows the amount due and due date, and whether it's paid. You can also check a statement PDF " +
        "(More → Check a statement PDF) to compare it with what the app recorded and add anything missing.",
    "Adding things by hand" to
        "On Activity, tap + and type it like you'd say it: \"lunch 45\", \"taxi 30 aed\", \"usd 20 netflix #1234\" (#1234 = the card's last 4 digits), \"refund amazon 50\".",
    "New messages are missed when the app is closed" to
        "Some phones put apps to sleep. In More, tap \"Keep it working\" and set the app's battery use to Unrestricted. A Sync always catches up anyway.",
)

@Composable
fun HelpScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(helpItems, key = { it.first }) { (q, a) ->
            var open by rememberSaveable(q) { mutableStateOf(false) }
            Panel(Modifier.fillMaxWidth(), onClick = { open = !open }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(q, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Ink.muted)
                }
                if (open) Text(a, style = MaterialTheme.typography.bodyMedium, color = Ink.muted, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
