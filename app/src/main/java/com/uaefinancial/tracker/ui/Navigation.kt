package com.uaefinancial.tracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector

/** Bottom-bar tabs plus a back stack for detail screens. Add a Route + a branch in AppRoot's `when` for new screens. */
enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    ACTIVITY("Activity", Icons.AutoMirrored.Filled.ReceiptLong),
    CARDS("Cards", Icons.Filled.CreditCard),
    MORE("More", Icons.Filled.Menu),
}

sealed interface Route {
    data class Home(val tab: Tab) : Route
    /** Card profile: limit, statement/due day, reminders, utilisation, statement status, payments. */
    data class CardDetail(val cardKey: String) : Route
    /** Editable exchange rates (in your home currency). */
    data object Rates : Route
    /** Fixed monthly payments you add by hand. */
    data object FixedPayments : Route
    /** Compare a card's statement PDF with the app. */
    data class StatementCheck(val cardKey: String?) : Route
    /** Bank messages the app couldn't read: fix, ignore or dismiss them. */
    data object Review : Route
    /** Bank sender IDs: built-in banks, senders you added, and a scan of your inbox. */
    data object Senders : Route
    /** Help & questions. */
    data object Help : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home(Tab.HOME))
    val current: Route get() = stack.last()
    val currentTab: Tab get() = stack.filterIsInstance<Route.Home>().last().tab
    val canGoBack: Boolean get() = stack.size > 1

    fun selectTab(tab: Tab) { stack.clear(); stack.add(Route.Home(tab)) }
    fun push(route: Route) { stack.add(route) }
    fun back() { if (canGoBack) stack.removeAt(stack.lastIndex) }
}
