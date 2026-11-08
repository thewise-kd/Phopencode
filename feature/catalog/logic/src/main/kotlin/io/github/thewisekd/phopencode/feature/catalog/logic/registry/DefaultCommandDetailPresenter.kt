package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
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
 * One command, found by [name] in [RegistryCatalog.commands]: the kept list first, the server's on
 * [CommandDetailIntent.Retry], so a command added since the list was kept can still be found.
 *
 * @param name The command's name, from the route.
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultCommandDetailPresenter(
    private val name: String,
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : CommandDetailPresenter {
    private val mutableState = MutableStateFlow(CommandDetailState(name = name))
    private val eventChannel = Channel<CommandDetailEvent>(Channel.UNLIMITED)
    private var reading: Job? = null

    override val state: StateFlow<CommandDetailState> = mutableState.asStateFlow()

    override val events: Flow<CommandDetailEvent> = eventChannel.receiveAsFlow()

    init {
        read(refresh = false)
    }

    override fun onIntent(intent: CommandDetailIntent) {
        when (intent) {
            CommandDetailIntent.Retry -> read(refresh = true)
            CommandDetailIntent.Close -> eventChannel.trySend(CommandDetailEvent.Close)
        }
    }

    private fun read(refresh: Boolean) {
        val previous = reading
        val job = scope.launch(start = CoroutineStart.LAZY) {
            yield()
            val self = coroutineContext[Job]
            write(self) { it.copy(loading = true, error = null) }
            val outcome = attempt { registry.commands(refresh) }
            write(self) { state ->
                when (outcome) {
                    is Outcome.Read -> {
                        val command = outcome.value.firstOrNull { it.name == name }
                        state.copy(loading = false, command = command, error = if (command == null) CatalogFailure.NotFound else null)
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
    private fun write(job: Job?, change: (CommandDetailState) -> CommandDetailState) {
        synchronized(this) {
            if (reading === job) mutableState.update(change)
        }
    }
}
