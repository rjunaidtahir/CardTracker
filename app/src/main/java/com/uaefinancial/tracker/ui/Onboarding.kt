package com.uaefinancial.tracker.ui

import androidx.core.net.toUri
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uaefinancial.tracker.sms.InboxReader

/**
 * First-run setup: what the app does → SMS permission → find your bank senders → first import → optional extras.
 * Every step can be skipped; it can be run again from More → Run the setup again.
 */
@Composable
fun OnboardingFlow(
    vm: MainViewModel,
    onLiveToggle: (Boolean) -> Unit,
    onRemindersToggle: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { denied = false; step = 2 } else denied = true
    }

    Box(Modifier.fillMaxSize().background(screenBrush)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepDots(step, 4)
            when (step) {
                0 -> {
                    Text("Fils", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("See where your money goes, from the SMS your banks already send you.", style = MaterialTheme.typography.bodyLarge, color = Ink.muted)
                    Feature(Icons.Filled.Sms, "Reads your bank SMS", "Card spends, refunds, payments and transfers become a clean list, by category and by card.")
                    Feature(Icons.Filled.CreditCard, "Every UAE bank", "Built-in formats for the main banks, plus a smart reader for any other.")
                    Feature(Icons.Filled.WifiOff, "Private by design", "No internet, no account, no ads. Your data never leaves this phone.")
                    Feature(Icons.Filled.Lock, "Ignores OTPs", "One-time passwords are never stored.")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { step = if (InboxReader.hasPermission(ctx)) 2 else 1 }, modifier = Modifier.fillMaxWidth()) { Text("Get started") }
                    TextButton(onClick = { vm.finishOnboarding() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip setup") }
                }
                1 -> {
                    Text("Allow access to SMS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "The app needs to read your SMS to find your bank messages. It only keeps messages from bank senders, and never OTPs.",
                        style = MaterialTheme.typography.bodyLarge, color = Ink.muted,
                    )
                    if (denied) {
                        Panel(Modifier.fillMaxWidth()) {
                            Text("Android didn't allow it", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "For apps installed from a file, Android 13 and later block SMS access until you allow it:\n\n" +
                                    "1. Tap Open app settings below.\n2. Tap ⋮ (top right) → Allow restricted settings.\n" +
                                    "3. Come back here and tap Allow SMS again.",
                                style = MaterialTheme.typography.bodyMedium, color = Ink.muted, modifier = Modifier.padding(top = 6.dp),
                            )
                            OutlinedButton(
                                onClick = {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                },
                                modifier = Modifier.padding(top = 10.dp),
                            ) { Text("Open app settings") }
                        }
                    }
                    Button(
                        onClick = { if (InboxReader.hasPermission(ctx)) step = 2 else permission.launch(Manifest.permission.READ_SMS) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (denied) "Allow SMS again" else "Allow SMS") }
                    TextButton(onClick = { step = 3 }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Not now (add transactions by hand)")
                    }
                }
                2 -> FindBanksStep(vm, onDone = { step = 3 })
                else -> DoneStep(vm, onLiveToggle, onRemindersToggle)
            }
        }
    }
}

@Composable
private fun FindBanksStep(vm: MainViewModel, onDone: () -> Unit) {
    val scan by vm.scan.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    val names = remember { mutableStateMapOf<String, String>() }
    val finished by vm.syncsFinished.collectAsStateWithLifecycle()
    // -1 = not importing; otherwise the finished-sync count when Import was tapped.
    var importFrom by rememberSaveable { mutableIntStateOf(-1) }
    val importing = importFrom >= 0
    LaunchedEffect(Unit) { if (scan.result == null) vm.scanSenders() }
    // Move on once the import started by the button has finished.
    LaunchedEffect(finished, importFrom) { if (importFrom >= 0 && finished > importFrom) { importFrom = -1; onDone() } }

    Text("Your banks", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    val r = scan.result
    when {
        scan.running || r == null && scan.error == null -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Looking through your messages…", color = Ink.muted)
        }
        scan.error != null -> Text(scan.error ?: "", color = Ink.red)
        r != null -> {
            if (r.known.isEmpty() && r.suggestions.isEmpty()) {
                Text(
                    "No bank messages found yet. That's fine: new ones are picked up when they arrive, and you can add a bank sender later in More → Bank senders.",
                    color = Ink.muted,
                )
            }
            if (r.known.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Found", style = MaterialTheme.typography.titleSmall)
                    r.known.forEach { (bank, n) ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text("✓  $bank", modifier = Modifier.weight(1f))
                            Text("$n messages", color = Ink.muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (r.suggestions.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("These look like banks too", style = MaterialTheme.typography.titleSmall)
                    Text("Tick the ones that are your banks, and check the name.", style = MaterialTheme.typography.bodySmall, color = Ink.muted)
                    r.suggestions.forEach { s ->
                        val on = picked[s.sender] ?: true
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Checkbox(on, { picked[s.sender] = it })
                            Column(Modifier.weight(1f)) {
                                OutlinedTextField(
                                    names[s.sender] ?: s.sender, { names[s.sender] = it }, singleLine = true,
                                    label = { Text("${s.sender} · ${s.alerts} messages") }, modifier = Modifier.fillMaxWidth(),
                                )
                                Text(s.sample, style = MaterialTheme.typography.bodySmall, color = Ink.faint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    Button(
        onClick = {
            val add = r?.suggestions.orEmpty().filter { picked[it.sender] ?: true }.map { it.sender to (names[it.sender] ?: it.sender) }
            importFrom = finished
            // Senders first, then one import that includes their older messages.
            if (add.isNotEmpty()) vm.addSenders(add, syncAfter = true) else vm.sync()
        },
        enabled = !scan.running && !syncing && !importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (syncing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ink.onAccent); Spacer(Modifier.width(8.dp)) }
        Text(if (syncing) "Importing…" else "Import my bank messages")
    }
    TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Skip") }
}

@Composable
private fun DoneStep(vm: MainViewModel, onLiveToggle: (Boolean) -> Unit, onRemindersToggle: (Boolean) -> Unit) {
    val live by vm.liveListening.collectAsStateWithLifecycle()
    val reminders by vm.remindersOn.collectAsStateWithLifecycle()
    val summary by vm.lastSyncSummary.collectAsStateWithLifecycle()
    Text("You're set", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    summary?.let { Text("Imported: $it.", style = MaterialTheme.typography.bodyLarge) }
    Panel(Modifier.fillMaxWidth()) {
        SettingSwitchRow("Record new messages automatically", "Each bank SMS is added as it arrives. Otherwise, tap Sync (↻) now and then.", live, onLiveToggle)
        SettingSwitchRow("Card due-date reminders", "A notification before a card payment is due.", reminders, onRemindersToggle)
    }
    Text(
        "Tip: tap a transaction to change its category; the app learns and does the same for that merchant next time. Help is in More → Help & questions.",
        style = MaterialTheme.typography.bodySmall, color = Ink.muted,
    )
    Button(onClick = { vm.finishOnboarding() }, modifier = Modifier.fillMaxWidth()) { Text("Start") }
}

@Composable
private fun Feature(icon: ImageVector, title: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        IconBadge(icon, Ink.green, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Ink.muted)
        }
    }
}

@Composable
private fun StepDots(step: Int, count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(RoundedCornerShape(50))
                    .background(if (i <= step) Ink.green else Ink.border),
            )
        }
    }
}
