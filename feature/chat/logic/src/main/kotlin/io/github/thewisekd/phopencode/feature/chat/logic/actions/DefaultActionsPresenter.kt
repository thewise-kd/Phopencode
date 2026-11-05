package io.github.thewisekd.phopencode.feature.chat.logic.actions

import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.ActionsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P7.1) for the session's actions: compact, fork, a turn's changes: shows
 * the initial state and ignores intents. P7.14 fills it in without changing the constructor; work
 * starts in [scope] after a `yield()`, never in the constructor, and every server call goes through
 * [ChatSession.call].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultActionsPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : ActionsPresenter {
    private val mutableState = MutableStateFlow(ActionsState())

    override val state: StateFlow<ActionsState> = mutableState.asStateFlow()

    override val events: Flow<ActionsEvent> = emptyFlow()

    override fun onIntent(intent: ActionsIntent) = Unit
}
