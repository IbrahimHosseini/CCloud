package com.pira.ccloud.navigation

import androidx.navigation.NavController

/**
 * Opens a top-level tab (one of [AppScreens.screens]) from the TV sidebar or the phone bottom bar.
 *
 * Movies, the first tab, stays at the bottom of the back stack with at most one other tab above
 * it, so Back from a tab goes to Movies and Back from Movies leaves the app. Switching tabs closes
 * the pages opened from the current tab (e.g. a movie's page) and saves the tab's own screen state
 * (scroll position, and the focused item that ScreenFocus restores), which comes back when the
 * user returns to that tab. Choosing the tab the current page belongs to goes back to the tab.
 */
fun NavController.navigateToTab(route: String) {
    // Movies isn't in the back stack until the splash is done
    if (currentDestination?.route == AppScreens.Splash.route) return
    val currentTab = currentTabRoute()
    popBackStack(currentTab, inclusive = false)
    when (route) {
        currentTab -> Unit
        AppScreens.Movies.route -> popBackStack(route, inclusive = false, saveState = true)
        else -> navigate(route) {
            popUpTo(AppScreens.Movies.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

/** The tab the current page belongs to: the tab right above Movies in the back stack, or Movies. */
private fun NavController.currentTabRoute(): String {
    val routes = currentBackStack.value.mapNotNull { it.destination.route }
    val tabAboveMovies = routes.dropWhile { it != AppScreens.Movies.route }.getOrNull(1)
    return tabAboveMovies?.takeIf { route -> AppScreens.screens.any { it.route == route } }
        ?: AppScreens.Movies.route
}
