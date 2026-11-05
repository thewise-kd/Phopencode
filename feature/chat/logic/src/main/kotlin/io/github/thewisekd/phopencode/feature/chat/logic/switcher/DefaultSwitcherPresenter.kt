package io.github.thewisekd.phopencode.feature.chat.logic.switcher

import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.AgentMode
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatAvailability
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSessionState
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.AgentOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ModelOption
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherAction
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherError
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SwitcherState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The session's agent, model and variant, and switching them (P7.10); the rules are on
 * [SwitcherState].
 *
 * - [SwitcherState.agent], [SwitcherState.model] and [SwitcherState.enabled] follow
 *   `ChatSessionState`: enabled while the session is loaded and the chat is
 *   [ChatAvailability.Live]. A switch changes them only once the sync brings the server's word.
 * - The lists are `CatalogApi.agents` and `CatalogApi.models` for `Location(session.directory)`,
 *   asked through [ChatSession.call] the first time the sheet is open while the switcher is
 *   enabled, and again on [SwitcherIntent.Retry]; a list that worked is not asked for again. Hidden
 *   agents and subagents are left out.
 * - A pick is sent only when [SwitcherState.canSwitch], when it names a listed agent, or a listed
 *   model with one of its variants or none, and when it is not the current one. One switch at a
 *   time: a pick while one is in flight is ignored. A switch the server accepts closes the sheet; a
 *   failed one keeps the sheet and the old selection, with [SwitcherState.error]. Picks wait for
 *   the lists, since only they tell a hidden agent or an unknown variant apart.
 * - Closing the sheet drops [SwitcherState.error]. Opening it asks again for lists whose load
 *   failed, even when the failure landed after the sheet closed. A switch still on its way when the
 *   sheet closes finishes, and its failure shows.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs; the
 * session's folder and choices print masked.
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
class DefaultSwitcherPresenter(
    @Suppress("unused") private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : SwitcherPresenter {
    /**
     * @property directory The loaded session's folder; null when none is loaded or it has none.
     * @property loaded The lists came back at least once since the last retry.
     */
    private data class Sheet(
        val ui: SwitcherState = SwitcherState(),
        val sessionLoaded: Boolean = false,
        val directory: String? = null,
        val loaded: Boolean = false,
        /** Raised by a retry: ask once the switcher is enabled, even while the sheet is closed. */
        val wanted: Boolean = false,
    ) {
        override fun toString(): String =
            "Sheet(enabled=${ui.enabled}, open=${ui.open}, loading=${ui.loading}, switching=${ui.switching}, " +
                "agents=${ui.agents.size}, models=${ui.models.size}, error=${ui.error}, loaded=$loaded, directory=***)"
    }

    private val lock = Any()

    private var sheet = Sheet()

    private val mutableState = MutableStateFlow(SwitcherState())

    override val state: StateFlow<SwitcherState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    init {
        scope.launch {
            yield()
            session.state.collect(::follow)
        }
    }

    override fun onIntent(intent: SwitcherIntent) {
        when (intent) {
            SwitcherIntent.Open -> {
                change {
                    // A load that failed, even after the sheet closed, is asked again on opening.
                    val stale = !it.loaded && !it.ui.loading && it.ui.error?.action == SwitcherAction.Load
                    it.copy(ui = it.ui.copy(open = true, error = if (stale) null else it.ui.error))
                }
                maybeLoad()
            }
            SwitcherIntent.Close -> change { it.copy(ui = it.ui.copy(open = false, error = null)) }
            is SwitcherIntent.PickAgent -> pickAgent(intent.id)
            is SwitcherIntent.PickModel -> pickModel(intent.model)
            SwitcherIntent.Retry -> {
                change {
                    if (it.ui.loading) {
                        it
                    } else {
                        val error = it.ui.error?.takeUnless { e -> e.action == SwitcherAction.Load }
                        it.copy(ui = it.ui.copy(error = error), loaded = false, wanted = true)
                    }
                }
                maybeLoad()
            }
            SwitcherIntent.DismissError -> change { it.copy(ui = it.ui.copy(error = null)) }
        }
    }

    // Following the session

    private fun follow(chat: ChatSessionState) {
        val loaded = chat.session?.session
        change {
            it.copy(
                ui = it.ui.copy(
                    enabled = loaded != null && chat.availability == ChatAvailability.Live,
                    agent = loaded?.agent,
                    model = loaded?.model,
                ),
                sessionLoaded = loaded != null,
                directory = loaded?.directory,
            )
        }
        maybeLoad()
    }

    // The lists

    /**
     * Asks for the lists when the sheet is open or a retry wants them, they are not known, none is
     * on its way, the last load did not fail, and the switcher is enabled.
     */
    private fun maybeLoad() {
        val directory = synchronized(lock) {
            val current = sheet
            val due = (current.ui.open || current.wanted) && !current.loaded && !current.ui.loading &&
                current.ui.error?.action != SwitcherAction.Load && current.ui.enabled && current.sessionLoaded
            if (!due) return
            commit(current.copy(ui = current.ui.copy(loading = true), wanted = false))
            current.directory
        }
        scope.launch { load(directory?.let(::Location)) }
    }

    private suspend fun load(location: Location?) {
        val result = try {
            session.call { client -> client.catalog.agents(location) to client.catalog.models(location) }
        } catch (e: ApiException) {
            change { it.copy(ui = it.ui.copy(loading = false, error = SwitcherError(SwitcherAction.Load, ChatFailure.of(e.error)))) }
            return
        }
        val (agents, models) = result
        change {
            val error = it.ui.error?.takeUnless { e -> e.action == SwitcherAction.Load }
            it.copy(
                ui = it.ui.copy(
                    loading = false,
                    agents = agents.filter(::leads).map(::option),
                    models = models.map(::option),
                    error = error,
                ),
                loaded = true,
            )
        }
    }

    private fun leads(agent: Agent): Boolean = !agent.hidden && agent.mode != AgentMode.Subagent

    private fun option(agent: Agent) = AgentOption(id = agent.id, name = agent.name, description = agent.description)

    private fun option(model: Model) =
        ModelOption(providerId = model.providerId, modelId = model.id, name = model.name, variants = model.variants)

    // Switching

    private fun pickAgent(id: String) {
        if (!claim { ui -> ui.agent != id && ui.agents.any { it.id == id } }) return
        scope.launch { switch(SwitcherAction.SwitchAgent) { client -> client.messages.switchAgent(session.sessionId, id) } }
    }

    private fun pickModel(ref: ModelRef) {
        val allowed = claim { ui ->
            ui.model != ref && ui.models.any { option ->
                option.providerId == ref.providerId && option.modelId == ref.modelId &&
                    (ref.variant == null || ref.variant in option.variants)
            }
        }
        if (!allowed) return
        scope.launch { switch(SwitcherAction.SwitchModel) { client -> client.messages.switchModel(session.sessionId, ref) } }
    }

    /** Marks a switch as on its way if one may start and [valid] holds; false when it may not. */
    private inline fun claim(valid: (SwitcherState) -> Boolean): Boolean = synchronized(lock) {
        val ui = sheet.ui
        if (!ui.canSwitch || !sheet.loaded || !valid(ui)) return false
        commit(sheet.copy(ui = ui.copy(switching = true, error = null)))
        true
    }

    private suspend fun switch(action: SwitcherAction, block: suspend (OpencodeClient) -> Unit) {
        val failure = try {
            session.call(block)
            null
        } catch (e: ApiException) {
            ChatFailure.of(e.error)
        }
        change {
            if (failure == null) {
                it.copy(ui = it.ui.copy(switching = false, open = false))
            } else {
                it.copy(ui = it.ui.copy(switching = false, error = SwitcherError(action, failure)))
            }
        }
    }

    // State

    private inline fun change(next: (Sheet) -> Sheet) {
        synchronized(lock) { commit(next(sheet)) }
    }

    /** Publishes [next]; holds [lock]. */
    private fun commit(next: Sheet) {
        sheet = next
        mutableState.value = next.ui
    }
}
