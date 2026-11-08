package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * One agent, found by [agentId] in [RegistryCatalog.agents], hidden ones included: the kept list
 * first, the server's on [AgentDetailIntent.Retry]. The state carries the whole agent, its mode,
 * model and permissions among it.
 *
 * @param agentId The agent's ID, the route's `name`.
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultAgentDetailPresenter(
    private val agentId: String,
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : AgentDetailPresenter {
    private val mutableState = MutableStateFlow(AgentDetailState(agentId = agentId))
    private val eventChannel = Channel<AgentDetailEvent>(Channel.UNLIMITED)
    private var reading: Job? = null

    override val state: StateFlow<AgentDetailState> = mutableState.asStateFlow()

    override val events: Flow<AgentDetailEvent> = eventChannel.receiveAsFlow()

    init {
        read(refresh = false)
    }

    override fun onIntent(intent: AgentDetailIntent) {
        when (intent) {
            AgentDetailIntent.Retry -> read(refresh = true)
            AgentDetailIntent.OpenModel -> mutableState.value.agent?.model?.let { model ->
                // The model screen shows the model; a variant is the agent's choice, shown here.
                eventChannel.trySend(AgentDetailEvent.OpenModel(model.providerId, model.modelId))
            }
            AgentDetailIntent.Close -> eventChannel.trySend(AgentDetailEvent.Close)
        }
    }

    private fun read(refresh: Boolean) {
        val previous = reading
        val job = scope.launch(start = CoroutineStart.LAZY) {
            yield()
            val self = coroutineContext[Job]
            write(self) { it.copy(loading = true, error = null) }
            val outcome = attempt { registry.agents(refresh) }
            write(self) { state ->
                when (outcome) {
                    is Outcome.Read -> {
                        val agent = outcome.value.firstOrNull { it.id == agentId }
                        state.copy(loading = false, agent = agent, error = if (agent == null) CatalogFailure.NotFound else null)
                    }
                    is Outcome.Failed -> state.copy(loading = false, error = outcome.failure)
                }
            }
        }
        synchronized(this) { reading = job }
        previous?.cancel()
        job.start()
    }

    /** Applies [change] only while [job] is the newest read: a replaced one never writes. */
    private fun write(job: Job?, change: (AgentDetailState) -> AgentDetailState) {
        synchronized(this) {
            if (reading === job) mutableState.update(change)
        }
    }
}
