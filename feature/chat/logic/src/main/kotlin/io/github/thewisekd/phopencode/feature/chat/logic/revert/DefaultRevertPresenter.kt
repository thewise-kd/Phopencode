package io.github.thewisekd.phopencode.feature.chat.logic.revert

import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.RevertState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P7.1) for the diff and revert of the route's turn: shows the initial
 * state and ignores intents. P7.12 fills it in without changing the constructor; work starts in
 * [scope] after a `yield()`, never in the constructor, and every server call goes through
 * [ChatSession.call].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultRevertPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : RevertPresenter {
    private val mutableState = MutableStateFlow(RevertState())

    override val state: StateFlow<RevertState> = mutableState.asStateFlow()

    override val events: Flow<RevertEvent> = emptyFlow()

    override fun onIntent(intent: RevertIntent) = Unit
}
