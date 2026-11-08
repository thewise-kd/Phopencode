package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
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
 * The registry's three tabs. Each list is read through [registry] on its own, so one that fails
 * shows its error on its tab and leaves the others; a failed read keeps the items read before it.
 * [RegistryIntent.Refresh] reads every list again through the kept ones; [RegistryIntent.Reload]
 * reads one tab from the server. Either one starts a new read for each list it covers, and only a
 * list's newest read writes it: an older one, perhaps still running on a connection since replaced,
 * can no longer touch the state. A refresh does not cancel the read it replaces, so that one of the
 * same connection stays shared through [registry] and no second server call starts; a reload does.
 *
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultRegistryPresenter(
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : RegistryPresenter {
    private val mutableState = MutableStateFlow(RegistryState())
    private val eventChannel = Channel<RegistryEvent>(Channel.UNLIMITED)
    private val lock = Any()

    /** Each list's newest read, under [lock]; only it writes the list. */
    private val newest = mutableMapOf<RegistryTab, Job>()

    override val state: StateFlow<RegistryState> = mutableState.asStateFlow()

    override val events: Flow<RegistryEvent> = eventChannel.receiveAsFlow()

    init {
        RegistryTab.entries.forEach { read(it, refresh = false, cancelPrevious = false) }
    }

    override fun onIntent(intent: RegistryIntent) {
        when (intent) {
            is RegistryIntent.SelectTab -> mutableState.update { it.copy(tab = intent.tab) }
            RegistryIntent.Refresh -> RegistryTab.entries.forEach { read(it, refresh = false, cancelPrevious = false) }
            is RegistryIntent.Reload -> read(intent.tab, refresh = true, cancelPrevious = true)
            is RegistryIntent.OpenCommand -> eventChannel.trySend(RegistryEvent.OpenCommand(intent.name))
            is RegistryIntent.OpenAgent -> eventChannel.trySend(RegistryEvent.OpenAgent(intent.agentId))
            is RegistryIntent.OpenModel -> eventChannel.trySend(RegistryEvent.OpenModel(intent.providerId, intent.modelId))
            RegistryIntent.Close -> eventChannel.trySend(RegistryEvent.Close)
        }
    }

    private fun read(tab: RegistryTab, refresh: Boolean, cancelPrevious: Boolean) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            yield()
            val self = coroutineContext[Job]
            when (tab) {
                RegistryTab.Commands -> readCommands(refresh) { change -> write(tab, self, change) }
                RegistryTab.Agents -> readAgents(refresh) { change -> write(tab, self, change) }
                RegistryTab.Models -> readModels(refresh) { change -> write(tab, self, change) }
            }
        }
        val previous = synchronized(lock) { newest.put(tab, job) }
        if (cancelPrevious) previous?.cancel()
        job.start()
    }

    /** Applies [change] only while [job] is [tab]'s newest read. */
    private fun write(tab: RegistryTab, job: Job?, change: (RegistryState) -> RegistryState) {
        synchronized(lock) {
            if (newest[tab] === job) mutableState.update(change)
        }
    }

    private suspend fun readCommands(refresh: Boolean, write: ((RegistryState) -> RegistryState) -> Unit) {
        write { it.copy(commands = it.commands.reading()) }
        val outcome = attempt { registry.commands(refresh) }
        write { it.copy(commands = it.commands.settled(outcome) { commands -> commands }) }
    }

    private suspend fun readAgents(refresh: Boolean, write: ((RegistryState) -> RegistryState) -> Unit) {
        write { it.copy(agents = it.agents.reading()) }
        val outcome = attempt { registry.agents(refresh) }
        write { state ->
            state.copy(agents = state.agents.settled(outcome) { agents -> agents.filterNot { it.hidden } })
        }
    }

    private suspend fun readModels(refresh: Boolean, write: ((RegistryState) -> RegistryState) -> Unit) {
        write { it.copy(models = it.models.reading()) }
        val outcome = attempt { registry.models(refresh) }
        write { state ->
            val models = state.models.settled(outcome) { catalog -> catalog.models }
            when (outcome) {
                is Outcome.Read ->
                    state.copy(models = models, providers = outcome.value.providers, defaultModel = outcome.value.defaultModel)
                is Outcome.Failed -> state.copy(models = models)
            }
        }
    }
}
