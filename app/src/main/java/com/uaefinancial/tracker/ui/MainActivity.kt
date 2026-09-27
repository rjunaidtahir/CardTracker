package com.uaefinancial.tracker.ui

import androidx.core.net.toUri
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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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
            .setTitle("Unlock Fils")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        val showBiometric = { biometricPrompt.authenticate(promptInfo) }
        // Only on a fresh start: after a rotation the same intent must not open the PDF again.
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            // Status / navigation bar icons follow the theme (dark icons on the light themes).
            val palette = AppThemes.current
            LaunchedEffect(palette.isLight) {
                val style = if (palette.isLight) SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme {
                val bioOn by vm.biometricOn.collectAsStateWithLifecycle()
                val canBio = remember { biometricAvailable() }
                AppLockGate(vm, onBiometric = if (bioOn && canBio) showBiometric else null) {
                    AppRoot(vm, biometricAvailable = canBio)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    /** A statement PDF sent here by another app ("Open with" / Share). */
    @Suppress("DEPRECATION")
    private fun handleIncoming(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) vm.incomingPdf.value = uri
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

private fun granted(ctx: Context, permission: String) =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

private const val RESTRICTED_HINT =
    "If Android says it's restricted: Settings → Apps → Fils → ⋮ → Allow restricted settings, then try again."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel, biometricAvailable: Boolean) {
    val ctx = LocalContext.current
    val nav = vm.nav
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showBatteryTip by remember { mutableStateOf(false) }
    var addingCard by remember { mutableStateOf(false) }

    val onboarded by vm.onboarded.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()
    val cardFilter by vm.cardFilter.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val excluded by vm.excludedCards.collectAsStateWithLifecycle()
    val txns by vm.transactions.collectAsStateWithLifecycle()
    val summaries by vm.cardSummaries.collectAsStateWithLifecycle()
    val failed by vm.failedSms.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val lastSyncAt by vm.lastSyncAt.collectAsStateWithLifecycle()
    val liveOn by vm.liveListening.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val categoryFilter by vm.categoryFilter.collectAsStateWithLifecycle()
    val rates by vm.rates.collectAsStateWithLifecycle()

    // Notifications (Android 13+) are asked for only when you switch reminders or alerts on.
    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.setReminders(true) else vm.message.value = "Reminders need permission to show notifications."
    }
    val onRemindersToggle: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.setReminders(false)
            Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) -> vm.setReminders(true)
            else -> notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val alertsNotifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.setAlerts(true) else vm.message.value = "Alerts need permission to show notifications."
    }
    val onAlertsToggle: (Boolean) -> Unit = { on ->
        when {
            !on -> vm.setAlerts(false)
            Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS) -> vm.setAlerts(true)
            else -> alertsNotifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Card pictures: the system photo picker (no storage permission needed).
    var pickingFor by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val key = pickingFor
        if (uri != null && key != null) vm.setCardImage(key, uri)
        pickingFor = null
    }

    // Reports: PDF or CSV for the selected period.
    var showReport by remember { mutableStateOf(false) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportReport(uri, pdf = true)
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportReport(uri, pdf = false)
    }
    val reportName = "spending-report-" + period.label().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase()
    if (showReport) {
        ReportDialog(
            periodLabel = period.label(),
            onPdf = { pdfLauncher.launch("$reportName.pdf") },
            onCsv = { csvLauncher.launch("$reportName.csv") },
            onDismiss = { showReport = false },
        )
    }

    // Backup: system file pickers (nothing leaves the phone unless you choose where to save it).
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBackup(uri)
    }

    // READ_SMS: asked by the setup, or the first time you tap Sync / Scan.
    var afterSmsGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val readSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) afterSmsGrant?.invoke() else vm.message.value = "The app needs permission to read SMS. $RESTRICTED_HINT"
        afterSmsGrant = null
    }
    fun withSms(action: () -> Unit) {
        if (granted(ctx, Manifest.permission.READ_SMS)) action() else { afterSmsGrant = action; readSmsLauncher.launch(Manifest.permission.READ_SMS) }
    }
    val onSync: () -> Unit = { withSms { vm.sync() } }

    // RECEIVE_SMS: asked only the first time you switch automatic recording on.
    fun enableLive() {
        vm.setLiveListening(true)
        if (vm.shouldShowBatteryTip()) showBatteryTip = true
    }
    val receiveSmsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) enableLive() else vm.message.value = "Recording new messages needs permission to receive SMS. $RESTRICTED_HINT"
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

    if (showBatteryTip) {
        BatteryTipDialog(
            onOpenSettings = {
                vm.markBatteryTipShown(); showBatteryTip = false
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onDismiss = { vm.markBatteryTipShown(); showBatteryTip = false },
        )
    }

    // A statement PDF opened from Gmail / Files / Share: straight to the statement check (after the setup, if it's running).
    val incoming by vm.incomingPdf.collectAsStateWithLifecycle()
    LaunchedEffect(incoming, onboarded) {
        val uri = incoming ?: return@LaunchedEffect
        if (!onboarded) return@LaunchedEffect
        vm.incomingPdf.value = null
        vm.openIncomingPdf(uri)
        if (nav.current !is Route.StatementCheck) nav.push(Route.StatementCheck(null))
    }

    if (!onboarded) {
        OnboardingFlow(vm, onLiveToggle = onLiveToggle, onRemindersToggle = onRemindersToggle)
        return
    }
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    if (addingCard) AddCardDialog(onAdd = { b, l, t, n, f -> vm.addCard(b, l, t, n, f) }, onDismiss = { addingCard = false })

    val route = nav.current
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Box(Modifier.fillMaxSize().background(screenBrush)) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Ink.bg),
                title = {
                    Text(
                        when (route) {
                            is Route.Home -> if (route.tab == Tab.HOME) "Financial Tracker" else route.tab.label
                            is Route.CardDetail -> "Card"
                            Route.Rates -> "Exchange rates"
                            Route.FixedPayments -> "Fixed payments"
                            is Route.StatementCheck -> "Check a statement"
                            Route.Review -> "Needs review"
                            Route.Senders -> "Bank senders"
                            Route.Help -> "Help & questions"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    if (nav.canGoBack) IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (route is Route.Home && (route.tab == Tab.HOME || route.tab == Tab.ACTIVITY)) {
                        if (route.tab == Tab.HOME) IconButton(onClick = { showReport = true }) { Icon(Icons.Filled.IosShare, "Export report") }
                        IconButton(onClick = onSync, enabled = !syncing) {
                            if (syncing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Refresh, "Sync bank messages")
                        }
                    }
                    if (route is Route.Home && route.tab == Tab.CARDS) {
                        IconButton(onClick = { addingCard = true }) { Icon(Icons.Filled.Add, "Add a card or account") }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Ink.surface, tonalElevation = 0.dp) {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Ink.green, selectedTextColor = Ink.text, indicatorColor = Ink.green.copy(alpha = 0.16f),
                            unselectedIconColor = Ink.muted, unselectedTextColor = Ink.muted,
                        ),
                        selected = route is Route.Home && nav.currentTab == tab,
                        onClick = { nav.selectTab(tab) },
                        icon = {
                            if (tab == Tab.MORE && failed.isNotEmpty()) {
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
                    Tab.HOME -> OverviewScreen(
                        vm,
                        onOpenCategory = { id -> vm.selectCard(null); vm.selectCategory(id); nav.selectTab(Tab.ACTIVITY) },
                        onOpenCard = { nav.push(Route.CardDetail(it)) },
                        onOpenFixed = { nav.push(Route.FixedPayments) },
                        reviewCount = failed.size,
                        onOpenReview = { nav.push(Route.Review) },
                        onSync = onSync,
                        onAddManual = { nav.selectTab(Tab.ACTIVITY) },
                    )
                    Tab.ACTIVITY -> TransactionsScreen(
                        vm, period, cardFilter, cards, excluded, txns,
                        syncing = syncing, lastSyncAt = lastSyncAt, liveOn = liveOn, onSync = onSync,
                        categories = categories, categoryFilter = categoryFilter,
                    )
                    Tab.CARDS -> CardsScreen(
                        summaries, period,
                        onOpenCard = { nav.push(Route.CardDetail(it)) },
                        onToggleCounted = { key, on -> vm.setCardCounted(key, on) },
                        onReorder = { vm.setCardOrder(it) },
                        onImportStatement = { vm.startStatementCheck(null); nav.push(Route.StatementCheck(null)) },
                        onAddCard = { addingCard = true },
                    )
                    Tab.MORE -> MoreScreen(
                        vm = vm,
                        reviewCount = failed.size,
                        liveOn = liveOn,
                        lastSyncAt = lastSyncAt,
                        biometricAvailable = biometricAvailable,
                        versionName = versionName,
                        onOpen = { r ->
                            if (r is Route.StatementCheck) vm.startStatementCheck(r.cardKey)
                            nav.push(r)
                        },
                        onLiveToggle = onLiveToggle,
                        onShowBatteryTip = { showBatteryTip = true },
                        onRemindersToggle = onRemindersToggle,
                        onAlertsToggle = onAlertsToggle,
                        onExportBackup = { exportLauncher.launch("financial-tracker-backup-${java.time.LocalDate.now()}.zip") },
                        onImportBackup = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        onExportReport = { showReport = true },
                    )
                }
                is Route.CardDetail -> CardDetailScreen(
                    summary = summaries.firstOrNull { it.card.cardKey == route.cardKey },
                    periodLabel = period.label(),
                    onSetType = { vm.setCardType(route.cardKey, it) },
                    onToggleCounted = { vm.setCardCounted(route.cardKey, it) },
                    onShowTransactions = { vm.selectCategory(null); vm.selectCard(route.cardKey); nav.selectTab(Tab.ACTIVITY) },
                    onSaveProfile = { n, l, sd, dd, r -> vm.saveCardProfile(route.cardKey, n, l, sd, dd, r) },
                    onSetTheme = { vm.setCardTheme(route.cardKey, it) },
                    onCheckStatement = { vm.startStatementCheck(route.cardKey); nav.push(Route.StatementCheck(route.cardKey)) },
                    onShowSinceStatement = { start -> vm.showSinceStatement(route.cardKey, start); nav.selectTab(Tab.ACTIVITY) },
                    onSetFamily = { vm.setCardFamily(route.cardKey, it) },
                    onPickImage = {
                        pickingFor = route.cardKey
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onDelete = { vm.deleteCard(route.cardKey) { nav.back() } },
                )
                Route.Rates -> RatesScreen(rates, onSave = { c, r -> vm.setRate(c, r) })
                Route.FixedPayments -> FixedPaymentsScreen(vm)
                is Route.StatementCheck -> StatementCheckScreen(vm)
                Route.Review -> ReviewScreen(vm, onShare = {
                    scope.launch {
                        val text = vm.reviewExportText()
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Bank SMS Fils couldn't read")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        ctx.startActivity(Intent.createChooser(send, "Share messages (check them first: they include amounts)"))
                    }
                })
                Route.Senders -> SendersScreen(vm, onScan = { withSms { vm.scanSenders() } })
                Route.Help -> HelpScreen()
            }
        }
    }
    }
}

@Composable
private fun BatteryTipDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.surface,
        title = { Text("Keep recording reliable") },
        text = {
            Text(
                "Some phones put apps to sleep, and then new SMS are missed until you Sync.\n\n" +
                    "Open the app's settings → Battery → Unrestricted.\n\n" +
                    "On Samsung, also: Settings → Battery → Background usage limits → Never sleeping apps → add Fils.",
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("Open app settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
