package com.junaid.cardtracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
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

/** FragmentActivity (a ComponentActivity) because BiometricPrompt needs one. */
class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private lateinit var biometricPrompt: BiometricPrompt

    // Android 9+ only: older versions would need an AppCompat theme for the library's own dialog.
    private fun biometricAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Must be created in onCreate.
        biometricPrompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    vm.unlockWithBiometric()
                }
            },
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Card Tracker")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        val showBiometric = { biometricPrompt.authenticate(promptInfo) }
        setContent {
            AppTheme {
                val bioOn by vm.biometricOn.collectAsStateWithLifecycle()
                val canBio = remember { biometricAvailable() }
                AppLockGate(vm, onBiometric = if (bioOn && canBio) showBiometric else null) {
                    AppRoot(vm, biometricAvailable = canBio)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) vm.onBackground()
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
fun AppRoot(vm: MainViewModel, biometricAvailable: Boolean) {
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
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoryFilter by vm.categoryFilter.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()

    // Notifications (Android 13+) are asked for only when you switch reminders on.
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            vm.setReminders(true)
        } else {
            vm.message.value = "Reminders need permission to show notifications."
        }
    }
    val onRemindersToggle: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.setReminders(false)
            Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) -> vm.setReminders(true)
            else -> notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Backup: system file pickers (nothing leaves the phone unless you choose where to save it).
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

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
                            Route.Rates -> "Exchange rates"
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
                    Tab.OVERVIEW -> OverviewScreen(
                        vm,
                        onOpenCategory = { id -> vm.selectCard(null); vm.selectCategory(id); nav.selectTab(Tab.TRANSACTIONS) },
                        onOpenCard = { nav.push(Route.CardDetail(it)) },
                    )
                    Tab.TRANSACTIONS -> TransactionsScreen(
                        vm, month, cardFilter, cards, excluded, txns,
                        syncing = syncing, lastSyncAt = lastSyncAt, liveOn = liveOn, onSync = onSync,
                        categories = categories, categoryFilter = categoryFilter,
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
                        vm = vm,
                        liveOn = liveOn,
                        lastSyncAt = lastSyncAt,
                        onLiveToggle = onLiveToggle,
                        onShowSamsungTip = { showSamsungTip = true },
                        onRemindersToggle = onRemindersToggle,
                        onExport = { exportLauncher.launch("cardtracker-backup-${java.time.LocalDate.now()}.zip") },
                        onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        onOpenRates = { nav.push(Route.Rates) },
                        biometricAvailable = biometricAvailable,
                        versionName = versionName,
                    )
                }
                is Route.CardDetail -> CardDetailScreen(
                    summary = summaries.firstOrNull { it.card.cardKey == route.cardKey },
                    onSetType = { vm.setCardType(route.cardKey, it) },
                    onToggleCounted = { vm.setCardCounted(route.cardKey, it) },
                    onShowTransactions = { vm.selectCategory(null); vm.selectCard(route.cardKey); nav.selectTab(Tab.TRANSACTIONS) },
                    onSaveProfile = { n, l, sd, dd, r -> vm.saveCardProfile(route.cardKey, n, l, sd, dd, r) },
                )
                Route.Rates -> RatesScreen(rates, onSave = { c, r -> vm.setRate(c, r) })
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
