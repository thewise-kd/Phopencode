package io.github.thewisekd.phopencode.feature.chat.logic.subagent

import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.SubagentState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P7.1) for the subagent tree of the route's session: shows the initial
 * state and ignores intents. P7.6 fills it in without changing the constructor; work starts in
 * [scope] after a `yield()`, never in the constructor, and every server call goes through
 * [ChatSession.call].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultSubagentPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : SubagentPresenter {
    private val mutableState = MutableStateFlow(SubagentState())

    override val state: StateFlow<SubagentState> = mutableState.asStateFlow()

    override val events: Flow<SubagentEvent> = emptyFlow()

    override fun onIntent(intent: SubagentIntent) = Unit
}
