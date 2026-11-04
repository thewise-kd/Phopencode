package io.github.thewisekd.phopencode.feature.chat.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.core.ui.component.EmptyState
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsagePresenter
import io.github.thewisekd.phopencode.feature.chat.ui.usage.UsageDetailScreen
import kotlin.reflect.KClass
import org.koin.core.parameter.parametersOf
import org.koin.mp.KoinPlatform

/**
 * The chat screens, one destination per [ChatRoute]. The app's NavHost calls this; a new screen of
 * the feature is added here only.
 *
 * Each destination gets one [ChatSession] and its presenters from `chatModule`, hands each part of
 * the screen its presenter's state and intents, and turns the presenters' one-time events into
 * navigation, or into an intent of another part. The screens never see [navController].
 *
 * The chat opens its usage page; leaving the chat's status, as for a session the active server
 * does not have, goes back. [ChatRoute.Subagents], [ChatRoute.ChildSession] and [ChatRoute.TurnDiff]
 * keep their placeholders until their phases wire them.
 */
fun NavGraphBuilder.chatGraph(navController: NavController) {
    composable<ChatRoute.Chat> { entry ->
        val route = entry.toRoute<ChatRoute.Chat>()
        val status = rememberPresenter<StatusPresenter>(route)
        val usage = rememberPresenter<UsagePresenter>(route)
        val timeline = rememberPresenter<TimelinePresenter>(route)
        val reasoning = rememberPresenter<ReasoningPresenter>(route)
        val tool = rememberPresenter<ToolPresenter>(route)
        val composer = rememberPresenter<ComposerPresenter>(route)
        CollectEvents(status) { event ->
            when (event) {
                StatusEvent.Close -> navController.popBackStack()
            }
        }
        CollectEvents(usage) { event ->
            when (event) {
                UsageEvent.OpenDetail -> navController.navigate(ChatRoute.TokenUsage(route.sessionId))
                // The bar has no page to leave.
                UsageEvent.Close -> Unit
            }
        }
        CollectEvents(composer) { event ->
            when (event) {
                ComposerEvent.Sent -> timeline.onIntent(TimelineIntent.JumpToLatest)
            }
        }
        val statusState by status.state.collectAsStateWithLifecycle()
        val usageState by usage.state.collectAsStateWithLifecycle()
        val timelineState by timeline.state.collectAsStateWithLifecycle()
        val reasoningState by reasoning.state.collectAsStateWithLifecycle()
        val toolState by tool.state.collectAsStateWithLifecycle()
        val composerState by composer.state.collectAsStateWithLifecycle()
        ChatScreen(
            route = route,
            status = statusState,
            onStatusIntent = status::onIntent,
            usage = usageState,
            onUsageIntent = usage::onIntent,
            timeline = timelineState,
            onTimelineIntent = timeline::onIntent,
            reasoning = reasoningState,
            onReasoningIntent = reasoning::onIntent,
            tool = toolState,
            onToolIntent = tool::onIntent,
            composer = composerState,
            onComposerIntent = composer::onIntent,
        )
    }
    composable<ChatRoute.TokenUsage> { entry ->
        val route = entry.toRoute<ChatRoute.TokenUsage>()
        val usage = rememberPresenter<UsagePresenter>(route)
        CollectEvents(usage) { event ->
            when (event) {
                // Already here.
                UsageEvent.OpenDetail -> Unit
                UsageEvent.Close -> navController.popBackStack()
            }
        }
        val state by usage.state.collectAsStateWithLifecycle()
        UsageDetailScreen(route, state, usage::onIntent)
    }
    composable<ChatRoute.Subagents> { RoutePlaceholder(it.toRoute<ChatRoute.Subagents>()) }
    composable<ChatRoute.ChildSession> { RoutePlaceholder(it.toRoute<ChatRoute.ChildSession>()) }
    composable<ChatRoute.TurnDiff> { RoutePlaceholder(it.toRoute<ChatRoute.TurnDiff>()) }
}

/** Tagged `placeholder:<route>`, the tag the app's navigation test looks for. */
@Composable
private fun RoutePlaceholder(route: ChatRoute) {
    EmptyState(Modifier.fillMaxSize().safeDrawingPadding().testTag("placeholder:$route"))
}

/**
 * Keeps, for as long as its back stack entry lives, a destination's [ChatSession] and the
 * presenters built on it, all in the scope of the entry's view model.
 *
 * Koin is the one the app started, looked up when the holder is made. Compose's `getKoin()` is not
 * used: it can keep a Koin that was stopped since, as Robolectric tests stop and start it.
 */
private class ChatDestination(private val route: ChatRoute) : ViewModel() {
    private val koin = KoinPlatform.getKoin()

    private val session: ChatSession = koin.get { parametersOf(route, viewModelScope) }

    private val presenters = HashMap<KClass<*>, Any>()

    fun <P : Any> presenter(type: KClass<P>): P = synchronized(presenters) {
        @Suppress("UNCHECKED_CAST")
        presenters.getOrPut(type) {
            koin.get<P>(type, null) { parametersOf(route, viewModelScope, session) }
        } as P
    }
}

/**
 * The presenter of type [P] of the destination showing [route], from Koin with the parameters
 * `chatModule` expects: the route, the scope of the destination's view model, and the
 * destination's [ChatSession], which every presenter of the destination shares. It survives
 * configuration changes and stops when the destination leaves the back stack.
 */
@Composable
private inline fun <reified P : Any> rememberPresenter(route: ChatRoute): P {
    val destination = viewModel<ChatDestination>(key = ChatDestination::class.java.name) { ChatDestination(route) }
    return remember(destination) { destination.presenter(P::class) }
}

/** Delivers [presenter]'s one-time events while the destination is at least started. */
@Composable
private fun <E> CollectEvents(presenter: Presenter<*, *, E>, onEvent: suspend (E) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val handle by rememberUpdatedState(onEvent)
    LaunchedEffect(presenter, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            presenter.events.collect { handle(it) }
        }
    }
}
