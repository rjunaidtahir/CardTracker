package com.junaid.cardtracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector

/** Bottom-bar tabs plus a back stack for detail screens. Add a Route + a branch in AppRoot's `when` for new screens. */
enum class Tab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Filled.Home),
    TRANSACTIONS("Transactions", Icons.Filled.List),
    CARDS("Cards", Icons.Filled.AccountBox),
    REVIEW("Review", Icons.Filled.Warning),
    SETTINGS("Settings", Icons.Filled.Settings),
}

sealed interface Route {
    data class Home(val tab: Tab) : Route
    /** Card profile: limit, statement/due day, reminders, utilisation, statement status, payments. */
    data class CardDetail(val cardKey: String) : Route
    /** Editable AED exchange rates. */
    data object Rates : Route
    /** Fixed monthly payments you add by hand. */
    data object FixedPayments : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home(Tab.OVERVIEW))
    val current: Route get() = stack.last()
    val currentTab: Tab get() = stack.filterIsInstance<Route.Home>().last().tab
    val canGoBack: Boolean get() = stack.size > 1

    fun selectTab(tab: Tab) { stack.clear(); stack.add(Route.Home(tab)) }
    fun push(route: Route) { stack.add(route) }
    fun back() { if (canGoBack) stack.removeAt(stack.lastIndex) }
}
