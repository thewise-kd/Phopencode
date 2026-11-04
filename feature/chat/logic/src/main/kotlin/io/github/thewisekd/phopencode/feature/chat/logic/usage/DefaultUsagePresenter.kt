package io.github.thewisekd.phopencode.feature.chat.logic.usage

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsagePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.UsageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P6.1): shows the initial state and ignores intents. P6.11 fills it in
 * without changing the constructor. The chat's bar and the detail page each have one.
 *
 * @param session The chat's session; the model list is asked through `ChatSession.call`.
 */
@Suppress("unused")
class DefaultUsagePresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
) : UsagePresenter {
    private val mutableState = MutableStateFlow(UsageState())

    override val state: StateFlow<UsageState> = mutableState.asStateFlow()

    override val events: Flow<UsageEvent> = emptyFlow()

    override fun onIntent(intent: UsageIntent) = Unit
}
