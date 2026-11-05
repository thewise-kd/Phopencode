package io.github.thewisekd.phopencode.feature.chat.ui

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
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
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
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
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherPresenter
import io.github.thewisekd.phopencode.feature.chat.ui.revert.TurnDiffScreen
import io.github.thewisekd.phopencode.feature.chat.ui.subagent.SubagentListScreen
import io.github.thewisekd.phopencode.feature.chat.ui.usage.UsageDetailScreen
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
 * - [ChatRoute.Chat]: every part of the chat. The composer's draft goes to the palette as its
 *   query; a palette pick comes back to the composer as its new draft and an attachment. Session
 *   actions open a turn's diff or switch to a fork; subagents open their list.
 * - [ChatRoute.Subagents]: the subagent list; a subagent opens its [ChatRoute.ChildSession].
 * - [ChatRoute.ChildSession]: a subagent's timeline, read only, with its way back to the parent.
 * - [ChatRoute.TurnDiff]: one turn's changes and their revert.
 * - [ChatRoute.TokenUsage]: the usage page.
 *
 * Leaving through a status, as for a session the active server does not have, goes back.
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
        val forms = rememberPresenter<FormPresenter>(route)
        val permissions = rememberPresenter<PermissionPresenter>(route)
        val subagents = rememberPresenter<SubagentPresenter>(route)
        val palette = rememberPresenter<PalettePresenter>(route)
        val switcher = rememberPresenter<SwitcherPresenter>(route)
        val actions = rememberPresenter<ActionsPresenter>(route)
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
        CollectFormEvents(forms)
        CollectEvents(subagents) { navController.onSubagentEvent(it, route) }
        CollectEvents(palette) { event ->
            when (event) {
                is PaletteEvent.Attached -> {
                    composer.onIntent(ComposerIntent.DraftChanged(event.draft))
                    composer.onIntent(ComposerIntent.Attach(event.attachment))
                }
                is PaletteEvent.CommandSent -> {
                    composer.onIntent(ComposerIntent.DraftChanged(event.draft))
                    timeline.onIntent(TimelineIntent.JumpToLatest)
                }
            }
        }
        CollectEvents(actions) { event ->
            when (event) {
                is ActionsEvent.OpenTurnDiff ->
                    navController.navigate(ChatRoute.TurnDiff(route.sessionId, event.messageId.value))
                is ActionsEvent.OpenSession -> navController.navigate(ChatRoute.Chat(event.sessionId.value)) {
                    popUpTo(route) { inclusive = true }
                }
                ActionsEvent.CompactionStarted -> timeline.onIntent(TimelineIntent.JumpToLatest)
            }
        }
        ForwardDraft(composer, palette)
        val statusState by status.state.collectAsStateWithLifecycle()
        val usageState by usage.state.collectAsStateWithLifecycle()
        val timelineState by timeline.state.collectAsStateWithLifecycle()
        val reasoningState by reasoning.state.collectAsStateWithLifecycle()
        val toolState by tool.state.collectAsStateWithLifecycle()
        val composerState by composer.state.collectAsStateWithLifecycle()
        val formsState by forms.state.collectAsStateWithLifecycle()
        val permissionsState by permissions.state.collectAsStateWithLifecycle()
        val subagentState by subagents.state.collectAsStateWithLifecycle()
        val paletteState by palette.state.collectAsStateWithLifecycle()
        val switcherState by switcher.state.collectAsStateWithLifecycle()
        val actionsState by actions.state.collectAsStateWithLifecycle()
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
            forms = formsState,
            onFormIntent = forms::onIntent,
            permissions = permissionsState,
            onPermissionIntent = permissions::onIntent,
            subagents = subagentState,
            onSubagentIntent = subagents::onIntent,
            palette = paletteState,
            onPaletteIntent = palette::onIntent,
            switcher = switcherState,
            onSwitcherIntent = switcher::onIntent,
            actions = actionsState,
            onActionsIntent = actions::onIntent,
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
    composable<ChatRoute.Subagents> { entry ->
        val route = entry.toRoute<ChatRoute.Subagents>()
        val subagents = rememberPresenter<SubagentPresenter>(route)
        CollectEvents(subagents) { navController.onSubagentEvent(it, route) }
        val state by subagents.state.collectAsStateWithLifecycle()
        SubagentListScreen(route, state, subagents::onIntent)
    }
    composable<ChatRoute.ChildSession> { entry ->
        val route = entry.toRoute<ChatRoute.ChildSession>()
        val status = rememberPresenter<StatusPresenter>(route)
        val subagents = rememberPresenter<SubagentPresenter>(route)
        val timeline = rememberPresenter<TimelinePresenter>(route)
        val reasoning = rememberPresenter<ReasoningPresenter>(route)
        val tool = rememberPresenter<ToolPresenter>(route)
        val forms = rememberPresenter<FormPresenter>(route)
        val permissions = rememberPresenter<PermissionPresenter>(route)
        CollectEvents(status) { event ->
            when (event) {
                StatusEvent.Close -> navController.popBackStack()
            }
        }
        CollectEvents(subagents) { navController.onSubagentEvent(it, route) }
        CollectFormEvents(forms)
        val statusState by status.state.collectAsStateWithLifecycle()
        val subagentState by subagents.state.collectAsStateWithLifecycle()
        val timelineState by timeline.state.collectAsStateWithLifecycle()
        val reasoningState by reasoning.state.collectAsStateWithLifecycle()
        val toolState by tool.state.collectAsStateWithLifecycle()
        val formsState by forms.state.collectAsStateWithLifecycle()
        val permissionsState by permissions.state.collectAsStateWithLifecycle()
        ChildSessionScreen(
            route = route,
            status = statusState,
            onStatusIntent = status::onIntent,
            subagents = subagentState,
            onSubagentIntent = subagents::onIntent,
            timeline = timelineState,
            onTimelineIntent = timeline::onIntent,
            reasoning = reasoningState,
            onReasoningIntent = reasoning::onIntent,
            tool = toolState,
            onToolIntent = tool::onIntent,
            forms = formsState,
            onFormIntent = forms::onIntent,
            permissions = permissionsState,
            onPermissionIntent = permissions::onIntent,
        )
    }
    composable<ChatRoute.TurnDiff> { entry ->
        val route = entry.toRoute<ChatRoute.TurnDiff>()
        val revert = rememberPresenter<RevertPresenter>(route)
        CollectEvents(revert) { event ->
            when (event) {
                RevertEvent.Reverted, RevertEvent.Close -> navController.popBackStack()
            }
        }
        val state by revert.state.collectAsStateWithLifecycle()
        TurnDiffScreen(route, state, revert::onIntent)
    }
}

/**
 * A subagent event of the destination showing [current]:
 * - the list and a subagent's timeline open on top;
 * - the way back to the parent goes back to the parent's screen when it is on the back stack, and
 *   otherwise opens it in place of [current], as a pending item can open a subagent with no parent
 *   below it;
 * - closing leaves the list; the chat and a subagent's timeline have nothing to close this way.
 */
private fun NavController.onSubagentEvent(event: SubagentEvent, current: ChatRoute) {
    when (event) {
        is SubagentEvent.OpenList -> navigate(ChatRoute.Subagents(event.sessionId.value))
        is SubagentEvent.OpenChild -> navigate(ChatRoute.ChildSession(event.sessionId.value))
        is SubagentEvent.OpenParent -> {
            val id = event.sessionId.value
            val parent: ChatRoute = if (event.root) ChatRoute.Chat(id) else ChatRoute.ChildSession(id)
            if (!popBackStack(parent, inclusive = false)) {
                navigate(parent) { popUpTo(current) { inclusive = true } }
            }
        }
        SubagentEvent.Close -> if (current is ChatRoute.Subagents) popBackStack()
    }
}

/**
 * Opens a form's web step outside the app. Only `http` and `https` are opened, whatever the
 * presenter sends: the URL comes from the server. A device with nothing to open it opens nothing.
 */
@Composable
private fun CollectFormEvents(forms: FormPresenter) {
    val uriHandler = LocalUriHandler.current
    CollectEvents(forms) { event ->
        when (event) {
            is FormEvent.OpenUrl -> if (event.url.isWebUrl()) {
                try {
                    uriHandler.openUri(event.url)
                } catch (_: ActivityNotFoundException) {
                    // Nothing on the device opens web pages.
                } catch (_: IllegalArgumentException) {
                    // Not a URL the platform can open.
                }
            }
        }
    }
}

private fun String.isWebUrl(): Boolean {
    val scheme = substringBefore(':', missingDelimiterValue = "").lowercase()
    return (scheme == "https" || scheme == "http") && startsWith("$scheme://", ignoreCase = true)
}

/** Hands every draft of [composer] to [palette], which opens on a `/` or `@` word. */
@Composable
private fun ForwardDraft(composer: ComposerPresenter, palette: PalettePresenter) {
    LaunchedEffect(composer, palette) {
        composer.state
            .map { it.draft }
            .distinctUntilChanged()
            .collect { palette.onIntent(PaletteIntent.QueryChanged(it)) }
    }
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
