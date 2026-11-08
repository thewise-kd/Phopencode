package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P10.1): shows the initial state and ignores intents. P10.4 fills it in
 * without changing the constructor; loading starts in [scope] after a
 * `yield()`.
 *
 * @param agentId The agent's ID, the route's `name`.
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultAgentDetailPresenter(
    private val agentId: String,
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : AgentDetailPresenter {
    private val mutableState = MutableStateFlow(AgentDetailState(agentId = agentId))

    override val state: StateFlow<AgentDetailState> = mutableState.asStateFlow()

    override val events: Flow<AgentDetailEvent> = emptyFlow()

    override fun onIntent(intent: AgentDetailIntent) = Unit
}
