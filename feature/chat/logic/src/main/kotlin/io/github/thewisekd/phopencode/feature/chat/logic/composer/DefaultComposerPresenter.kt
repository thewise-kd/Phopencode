package io.github.thewisekd.phopencode.feature.chat.logic.composer

import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerEvent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerIntent
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ComposerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P6.1): shows the initial state and ignores intents. P6.5 fills it in
 * without changing the constructor.
 *
 * @param session The chat's session; prompts and stops go through `ChatSession.call`, so only to
 *   the server the chat is bound to.
 */
@Suppress("unused")
class DefaultComposerPresenter(
    private val session: ChatSession,
    private val scope: CoroutineScope,
) : ComposerPresenter {
    private val mutableState = MutableStateFlow(ComposerState())

    override val state: StateFlow<ComposerState> = mutableState.asStateFlow()

    override val events: Flow<ComposerEvent> = emptyFlow()

    override fun onIntent(intent: ComposerIntent) = Unit
}
