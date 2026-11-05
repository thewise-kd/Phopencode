package io.github.thewisekd.phopencode.feature.chat.logic.form

import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.FormsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P7.1) for the forms of this session and its subagents: shows the initial
 * state and ignores intents. P7.2 fills it in without changing the constructor; work starts in
 * [scope] after a `yield()`, never in the constructor, and every server call goes through
 * [ChatSession.call].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultFormPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : FormPresenter {
    private val mutableState = MutableStateFlow(FormsState())

    override val state: StateFlow<FormsState> = mutableState.asStateFlow()

    override val events: Flow<FormEvent> = emptyFlow()

    override fun onIntent(intent: FormIntent) = Unit
}
