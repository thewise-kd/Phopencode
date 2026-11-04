package io.github.thewisekd.phopencode.feature.chat.logic.timeline

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelinePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.TimelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P6.1): shows the initial state and ignores intents. P6.3 fills it in
 * without changing the constructor.
 *
 * @param session The chat's session, shared with the other presenters of the screen.
 * @param pageSize How many messages the window shows at first, and adds on each
 *   [TimelineIntent.LoadOlder].
 */
@Suppress("unused")
class DefaultTimelinePresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : TimelinePresenter {
    private val mutableState = MutableStateFlow(TimelineState())

    override val state: StateFlow<TimelineState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    override fun onIntent(intent: TimelineIntent) = Unit

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
    }
}
