package io.github.thewisekd.phopencode.feature.catalog.logic.status

import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusPresenter
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P10.1): shows the initial state and ignores intents. P10.6 fills it in
 * without changing the constructor; loading starts in [scope] after a
 * `yield()`.
 *
 * @param connection The catalog of the active connection, read fresh each time.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultStatusPresenter(
    private val connection: CatalogConnection,
    private val scope: CoroutineScope,
) : StatusPresenter {
    private val mutableState = MutableStateFlow(StatusState())

    override val state: StateFlow<StatusState> = mutableState.asStateFlow()

    override val events: Flow<StatusEvent> = emptyFlow()

    override fun onIntent(intent: StatusIntent) = Unit
}
