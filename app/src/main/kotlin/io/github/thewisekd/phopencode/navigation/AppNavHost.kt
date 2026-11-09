package io.github.thewisekd.phopencode.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.feature.catalog.ui.catalogGraph
import io.github.thewisekd.phopencode.feature.chat.ui.chatGraph
import io.github.thewisekd.phopencode.feature.connect.ui.connectGraph
import io.github.thewisekd.phopencode.feature.files.ui.filesGraph
import io.github.thewisekd.phopencode.feature.git.ui.gitGraph
import io.github.thewisekd.phopencode.feature.notify.ui.notifyGraph
import io.github.thewisekd.phopencode.feature.sessions.ui.sessionsGraph
import io.github.thewisekd.phopencode.feature.settings.ui.settingsGraph

/**
 * Every screen of the app. Each feature registers its own screens in its graph, so this file
 * does not change when a screen is added.
 */
@Composable
fun AppNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = ConnectRoute.ServerList,
        modifier = modifier,
    ) {
        connectGraph(navController)
        sessionsGraph(navController)
        chatGraph(navController)
        catalogGraph(navController)
        filesGraph(navController)
        gitGraph(navController)
        notifyGraph(navController)
        settingsGraph(navController)
    }
}

/**
 * Opens the screen whose deep link [intent] carries, above the current back stack, and returns
 * whether it did; an intent without a matching link changes nothing. Meant for an intent that
 * reaches the running activity; the NavHost handles the one that starts it.
 *
 * `NavController.handleDeepLink` is not used here: it always clears the back stack, and with
 * `FLAG_ACTIVITY_NEW_TASK`, which a notification's tap carries, it restarts the whole task.
 * Opening a screen that is already on top does not add a second copy of it.
 */
fun NavController.openDeepLink(intent: Intent): Boolean {
    val uri = intent.data ?: return false
    val request = NavDeepLinkRequest.Builder.fromUri(uri).build()
    if (!graph.hasDeepLink(request)) return false
    navigate(request, navOptions { launchSingleTop = true })
    return true
}
