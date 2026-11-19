package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogRead
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
 * shows its error on its tab and leaves the others; a failed read keeps the items read before it,
 * unless it failed on another connection than the one those items were read on: then they go (and
 * for the models, the providers and the default too), so one server's lists never stand beside
 * another's error. A failure with no connection standing keeps them.
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

    /** The generation each list on screen was read on, under [lock]; absent when none is known. */
    private val shownGenerations = mutableMapOf<RegistryTab, Long>()

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
                RegistryTab.Commands -> loadCommands(tab, self, refresh)
                RegistryTab.Agents -> loadAgents(tab, self, refresh)
                RegistryTab.Models -> loadModels(tab, self, refresh)
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

    /**
     * Ends [tab]'s read [job] with [outcome], only while [job] is its newest read. [change] learns
     * whether the read failed on another connection than the one [tab]'s list on screen came from,
     * when that list must go.
     */
    private fun <T> settle(
        tab: RegistryTab,
        job: Job?,
        read: CatalogRead<T>,
        outcome: Outcome<T>,
        change: (RegistryState, otherConnection: Boolean) -> RegistryState,
    ) {
        synchronized(lock) {
            if (newest[tab] !== job) return
            val generation = read.generation
            val shown = shownGenerations[tab]
            val otherConnection = outcome is Outcome.Failed && generation != null && shown != null && generation != shown
            when {
                outcome is Outcome.Read -> if (generation != null) shownGenerations[tab] = generation else shownGenerations.remove(tab)
                otherConnection -> shownGenerations.remove(tab)
            }
            mutableState.update { change(it, otherConnection) }
        }
    }

    private suspend fun loadCommands(tab: RegistryTab, job: Job?, refresh: Boolean) {
        write(tab, job) { it.copy(commands = it.commands.reading()) }
        val read = registry.readCommands(refresh)
        val outcome = attempt { read.outcome.getOrThrow() }
        settle(tab, job, read, outcome) { state, otherConnection ->
            state.copy(commands = state.commands.droppedIf(otherConnection).settled(outcome) { commands -> commands })
        }
    }

    private suspend fun loadAgents(tab: RegistryTab, job: Job?, refresh: Boolean) {
        write(tab, job) { it.copy(agents = it.agents.reading()) }
        val read = registry.readAgents(refresh)
        val outcome = attempt { read.outcome.getOrThrow() }
        settle(tab, job, read, outcome) { state, otherConnection ->
            state.copy(agents = state.agents.droppedIf(otherConnection).settled(outcome) { agents -> agents.filterNot { it.hidden } })
        }
    }

    private suspend fun loadModels(tab: RegistryTab, job: Job?, refresh: Boolean) {
        write(tab, job) { it.copy(models = it.models.reading()) }
        val read = registry.readModels(refresh)
        val outcome = attempt { read.outcome.getOrThrow() }
        settle(tab, job, read, outcome) { state, otherConnection ->
            val models = state.models.droppedIf(otherConnection).settled(outcome) { catalog -> catalog.models }
            when {
                outcome is Outcome.Read ->
                    state.copy(models = models, providers = outcome.value.providers, defaultModel = outcome.value.defaultModel)
                otherConnection -> state.copy(models = models, providers = emptyList(), defaultModel = null)
                else -> state.copy(models = models)
            }
        }
    }

    private fun <T> CatalogList<T>.droppedIf(otherConnection: Boolean): CatalogList<T> =
        if (otherConnection) copy(items = emptyList()) else this
}
