package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryCatalog
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
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
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultRegistryPresenter(
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : RegistryPresenter {
    private val mutableState = MutableStateFlow(RegistryState())

    override val state: StateFlow<RegistryState> = mutableState.asStateFlow()

    override val events: Flow<RegistryEvent> = emptyFlow()

    override fun onIntent(intent: RegistryIntent) = Unit
}
