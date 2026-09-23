package com.junaid.cardtracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppLockGate { AppRoot(vm) } } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun granted(ctx: Context, permission: String) =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

private const val RESTRICTED_HINT =
    "If Android says the permission is restricted: Settings → Apps → Card Tracker → ⋮ → Allow restricted settings, then try again."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val ctx = LocalContext.current
    val nav = vm.nav
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSamsungTip by remember { mutableStateOf(false) }

    val month by vm.month.collectAsStateWithLifecycle()
    val cardFilter by vm.cardFilter.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val excluded by vm.excludedCards.collectAsStateWithLifecycle()
    val txns by vm.transactions.collectAsStateWithLifecycle()
    val summaries by vm.cardSummaries.collectAsStateWithLifecycle()
    val failed by vm.failedSms.collectAsStateWithLifecycle()
    val counts by vm.smsCounts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val liveOn by vm.liveListening.collectAsStateWithLifecycle()

    // READ_SMS: asked the first time you tap Sync.
    val readSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            vm.sync()
        } else {
            vm.message.value = "Sync needs permission to read SMS. $RESTRICTED_HINT"
        }
    }
    val onSync: () -> Unit = {
        if (granted(ctx, Manifest.permission.READ_SMS)) vm.sync() else readSmsLauncher.launch(Manifest.permission.READ_SMS)
    }

    // RECEIVE_SMS: asked only the first time you switch live listening on.
    fun enableLive() {
        vm.setLiveListening(true)
        if (vm.shouldShowSamsungTip()) showSamsungTip = true
    }
    val receiveSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            enableLive()
        } else {
            vm.message.value = "Live listening needs permission to receive SMS. $RESTRICTED_HINT"
        }
    }
    val onLiveToggle: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.setLiveListening(false)
            granted(ctx, Manifest.permission.RECEIVE_SMS) -> enableLive()
            else -> receiveSmsLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }
    BackHandler(enabled = nav.canGoBack) { nav.back() }

    if (showSamsungTip) {
        SamsungTipDialog(
            onOpenSettings = {
                vm.markSamsungTipShown(); showSamsungTip = false
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onDismiss = { vm.markSamsungTipShown(); showSamsungTip = false },
        )
    }

    val route = nav.current
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            is Route.Home -> route.tab.label
                            is Route.CardDetail -> "Card"
                        },
                    )
                },
                navigationIcon = {
                    if (nav.canGoBack) IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.ArrowBack, "Back") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = route is Route.Home && nav.currentTab == tab,
                        onClick = { nav.selectTab(tab) },
                        icon = {
                            if (tab == Tab.REVIEW && failed.isNotEmpty()) {
                                BadgedBox(badge = { Badge { Text("${failed.size}") } }) { Icon(tab.icon, null) }
                            } else {
                                Icon(tab.icon, null)
                            }
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (route) {
                is Route.Home -> when (route.tab) {
                    Tab.TRANSACTIONS -> TransactionsScreen(
                        vm, month, cardFilter, cards, excluded, txns,
                        syncing = syncing, lastSyncAt = lastSyncAt, liveOn = liveOn, onSync = onSync,
                    )
                    Tab.CARDS -> CardsScreen(
                        summaries, month,
                        onOpenCard = { nav.push(Route.CardDetail(it)) },
                        onToggleCounted = { key, on -> vm.setCardCounted(key, on) },
                    )
                    Tab.REVIEW -> ReviewScreen(
                        failed, counts,
                        onDismiss = { vm.dismiss(it) },
                        onReparse = { vm.reparseAll() },
                        onShare = {
                            scope.launch {
                                val text = vm.reviewExportText()
                                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_SUBJECT, "Card Tracker unparsed SMS")
                                    .putExtra(Intent.EXTRA_TEXT, text)
                                ctx.startActivity(Intent.createChooser(send, "Share unparsed SMS"))
                            }
                        },
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        liveOn = liveOn,
                        lastSyncAt = lastSyncAt,
                        onLiveToggle = onLiveToggle,
                        onShowSamsungTip = { showSamsungTip = true },
                        onResetSync = { vm.resetSyncPointer() },
                        onReparse = { vm.reparseAll() },
                        versionName = versionName,
                    )
                }
                is Route.CardDetail -> CardDetailScreen(
                    summary = summaries.firstOrNull { it.card.cardKey == route.cardKey },
                    onSetType = { vm.setCardType(route.cardKey, it) },
                    onToggleCounted = { vm.setCardCounted(route.cardKey, it) },
                    onShowTransactions = { vm.selectCard(route.cardKey); nav.selectTab(Tab.TRANSACTIONS) },
                )
            }
        }
    }
}

@Composable
private fun SamsungTipDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep live listening reliable") },
        text = {
            Text(
                "One UI can put Card Tracker to sleep, and then new SMS are missed until you Sync.\n\n" +
                    "Add it to Never sleeping apps:\nSettings → Battery (or Device care → Battery) → Background usage limits → " +
                    "Never sleeping apps → + → Card Tracker.\n\n" +
                    "Also set Settings → Apps → Card Tracker → Battery to Unrestricted.",
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open app settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
