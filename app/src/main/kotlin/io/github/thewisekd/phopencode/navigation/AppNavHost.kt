package io.github.thewisekd.phopencode.navigation

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.SettingsRoute
import io.github.thewisekd.phopencode.core.storage_api.AppSettings
import io.github.thewisekd.phopencode.core.storage_api.SettingsRepository
import io.github.thewisekd.phopencode.feature.catalog.ui.catalogGraph
import io.github.thewisekd.phopencode.feature.chat.ui.chatGraph
import io.github.thewisekd.phopencode.feature.connect.ui.connectGraph
import io.github.thewisekd.phopencode.feature.files.ui.filesGraph
import io.github.thewisekd.phopencode.feature.git.ui.gitGraph
import io.github.thewisekd.phopencode.feature.notify.ui.notifyGraph
import io.github.thewisekd.phopencode.feature.preview.ui.previewGraph
import io.github.thewisekd.phopencode.feature.sessions.ui.sessionsGraph
import io.github.thewisekd.phopencode.feature.settings.ui.settingsGraph
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

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
        previewGraph(navController)
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

/**
 * Opens the usage guide, `SettingsRoute.Guide`, above the server list on a launch while
 * [AppSettings.guideSeen] is false (P14.1). Compose it beside [AppNavHost], with the same
 * [navController].
 *
 * - It waits for the stored settings before it decides, so a guide already seen never flashes.
 * - It decides once per activity: the decision is saved state, so a rotation or a restored process
 *   does not open the guide again, even if it was left without being recorded as seen.
 * - It opens the guide only when the server list alone is shown: a deep link that started the
 *   app, or a screen opened while the settings were read, is not covered. The guide then waits for
 *   a later launch.
 * - Settings that cannot be read open no guide: the app must stay usable.
 */
@Composable
fun OpenGuideOnFirstLaunch(navController: NavHostController, settings: SettingsRepository) {
    var decided by rememberSaveable { mutableStateOf(false) }
    if (decided) return
    LaunchedEffect(navController, settings) {
        val seen = guideSeen(settings)
        if (!seen && navController.showsServerListAlone()) {
            navController.navigate(SettingsRoute.Guide) { launchSingleTop = true }
        }
        decided = true
    }
}

/** Whether the guide was seen; true when the settings cannot be read, so nothing is forced on the user. */
private suspend fun guideSeen(settings: SettingsRepository): Boolean =
    try {
        settings.observe().first().guideSeen
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        true
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        true
    }

private fun NavController.showsServerListAlone(): Boolean =
    currentDestination?.hasRoute<ConnectRoute.ServerList>() == true && previousBackStackEntry == null
