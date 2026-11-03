package io.github.thewisekd.phopencode.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
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
