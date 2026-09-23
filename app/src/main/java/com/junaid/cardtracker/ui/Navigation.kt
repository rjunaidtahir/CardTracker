package com.junaid.cardtracker.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Minimal navigation: bottom-bar tabs plus a back stack for detail screens.
 * Planned additions (ROADMAP.md): Tab.INSIGHTS (Phase 3 charts), Route.Reminders / Route.Backup
 * (Phase 2), Route.Goals (Phase 4). Add a Route + a branch in AppRoot's `when`.
 */
enum class Tab(val label: String, val icon: ImageVector) {
    TRANSACTIONS("Transactions", Icons.Filled.List),
    CARDS("Cards", Icons.Filled.AccountBox),
    REVIEW("Review", Icons.Filled.Warning),
    SETTINGS("Settings", Icons.Filled.Settings),
}

sealed interface Route {
    data class Home(val tab: Tab) : Route
    /** Phase 2 turns this into the full card profile (limit, statement day, due day, utilization). */
    data class CardDetail(val cardKey: String) : Route
}

class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home(Tab.TRANSACTIONS))
    val current: Route get() = stack.last()
    val currentTab: Tab get() = stack.filterIsInstance<Route.Home>().last().tab
    val canGoBack: Boolean get() = stack.size > 1

    fun selectTab(tab: Tab) { stack.clear(); stack.add(Route.Home(tab)) }
    fun push(route: Route) { stack.add(route) }
    fun back() { if (canGoBack) stack.removeAt(stack.lastIndex) }
}

/**
 * Phase 2 app lock (biometric + PIN fallback, re-lock after background timeout) wraps the UI here.
 * For now it shows the content directly.
 */
@Composable
fun AppLockGate(content: @Composable () -> Unit) = content()
