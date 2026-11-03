package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState

/**
 * The chat screens, one destination per [ChatRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only. Until P6.1 wires the screens, every route opens a placeholder.
 *
 * @param navController Moves between screens, once there are screens to move between.
 */
@Suppress("UNUSED_PARAMETER")
fun NavGraphBuilder.chatGraph(navController: NavController) {
    composable<ChatRoute.Chat> { RoutePlaceholder(it.toRoute<ChatRoute.Chat>()) }
    composable<ChatRoute.Subagents> { RoutePlaceholder(it.toRoute<ChatRoute.Subagents>()) }
    composable<ChatRoute.ChildSession> { RoutePlaceholder(it.toRoute<ChatRoute.ChildSession>()) }
    composable<ChatRoute.TurnDiff> { RoutePlaceholder(it.toRoute<ChatRoute.TurnDiff>()) }
    composable<ChatRoute.TokenUsage> { RoutePlaceholder(it.toRoute<ChatRoute.TokenUsage>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: ChatRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}
