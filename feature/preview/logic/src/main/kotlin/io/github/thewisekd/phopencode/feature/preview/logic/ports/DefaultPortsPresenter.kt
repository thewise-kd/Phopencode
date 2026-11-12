package io.github.thewisekd.phopencode.feature.preview.logic.ports

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsEvent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsPresenter
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PortsState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewPortsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P13.5): shows the initial state and ignores intents. P13.8 fills it in
 * without changing the constructor; the first listing starts in [scope] after a `yield()`.
 *
 * @param ports The listening ports of the server of the active connection.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultPortsPresenter(
    private val ports: PreviewPortsSource,
    private val scope: CoroutineScope,
) : PortsPresenter {
    private val mutableState = MutableStateFlow(PortsState())

    override val state: StateFlow<PortsState> = mutableState.asStateFlow()

    override val events: Flow<PortsEvent> = emptyFlow()

    override fun onIntent(intent: PortsIntent) = Unit
}
