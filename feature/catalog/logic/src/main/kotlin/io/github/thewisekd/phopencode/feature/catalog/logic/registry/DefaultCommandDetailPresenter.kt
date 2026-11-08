package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
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
 * @param name The command's name, from the route.
 * @param registry The app's command, agent and model cache.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultCommandDetailPresenter(
    private val name: String,
    private val registry: RegistryCatalog,
    private val scope: CoroutineScope,
) : CommandDetailPresenter {
    private val mutableState = MutableStateFlow(CommandDetailState(name = name))

    override val state: StateFlow<CommandDetailState> = mutableState.asStateFlow()

    override val events: Flow<CommandDetailEvent> = emptyFlow()

    override fun onIntent(intent: CommandDetailIntent) = Unit
}
