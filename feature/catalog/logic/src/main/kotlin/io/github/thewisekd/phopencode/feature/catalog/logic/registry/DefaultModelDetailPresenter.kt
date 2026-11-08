package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
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
 * One model, found by [providerId] and [modelId] together in [RegistryCatalog.models]: the kept
 * catalog first, the server's on [ModelDetailIntent.Retry]. The state carries the whole model, its
 * limits, price tiers and variants among it, with its provider's name and the server's default.
 *
 * @param providerId From the route.
 * @param modelId From the route.
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultModelDetailPresenter(
    private val providerId: String,
    private val modelId: String,
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : ModelDetailPresenter {
    private val mutableState = MutableStateFlow(ModelDetailState(providerId = providerId, modelId = modelId))
    private val eventChannel = Channel<ModelDetailEvent>(Channel.UNLIMITED)
    private var reading: Job? = null

    override val state: StateFlow<ModelDetailState> = mutableState.asStateFlow()

    override val events: Flow<ModelDetailEvent> = eventChannel.receiveAsFlow()

    init {
        read(refresh = false)
    }

    override fun onIntent(intent: ModelDetailIntent) {
        when (intent) {
            ModelDetailIntent.Retry -> read(refresh = true)
            ModelDetailIntent.Close -> eventChannel.trySend(ModelDetailEvent.Close)
        }
    }

    private fun read(refresh: Boolean) {
        val previous = reading
        val job = scope.launch(start = CoroutineStart.LAZY) {
            yield()
            val self = coroutineContext[Job]
            write(self) { it.copy(loading = true, error = null) }
            val outcome = attempt { registry.models(refresh) }
            write(self) { state ->
                when (outcome) {
                    is Outcome.Read -> {
                        val catalog = outcome.value
                        val model = catalog.models.firstOrNull { it.providerId == providerId && it.id == modelId }
                        state.copy(
                            loading = false,
                            model = model,
                            providerName = catalog.providerName(providerId),
                            defaultModel = catalog.defaultModel,
                            error = if (model == null) CatalogFailure.NotFound else null,
                        )
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
    private fun write(job: Job?, change: (ModelDetailState) -> ModelDetailState) {
        synchronized(this) {
            if (reading === job) mutableState.update(change)
        }
    }
}
