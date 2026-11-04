package io.github.thewisekd.phopencode.feature.chat.logic.reasoning

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ReasoningState
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P6.1): shows the initial state and ignores intents. P6.7 fills it in
 * without changing the constructor.
 *
 * @param session The chat's session, shared with the other presenters of the screen.
 * @param clock The duration of a reasoning that still streams runs to its `now()`.
 */
@Suppress("unused")
class DefaultReasoningPresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ReasoningPresenter {
    private val mutableState = MutableStateFlow(ReasoningState())

    override val state: StateFlow<ReasoningState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    override fun onIntent(intent: ReasoningIntent) = Unit
}
