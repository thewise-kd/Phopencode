package io.github.thewisekd.phopencode.feature.chat.logic.tool

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ToolState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P6.1): shows the initial state and ignores intents. P6.9 fills it in
 * without changing the constructor.
 *
 * @param session The chat's session, shared with the other presenters of the screen.
 */
@Suppress("unused")
class DefaultToolPresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
) : ToolPresenter {
    private val mutableState = MutableStateFlow(ToolState())

    override val state: StateFlow<ToolState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    override fun onIntent(intent: ToolIntent) = Unit
}
