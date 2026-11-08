package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
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
 * @param providerId From the route.
 * @param modelId From the route.
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultModelDetailPresenter(
    private val providerId: String,
    private val modelId: String,
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : ModelDetailPresenter {
    private val mutableState = MutableStateFlow(ModelDetailState(providerId = providerId, modelId = modelId))

    override val state: StateFlow<ModelDetailState> = mutableState.asStateFlow()

    override val events: Flow<ModelDetailEvent> = emptyFlow()

    override fun onIntent(intent: ModelDetailIntent) = Unit
}
