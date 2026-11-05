package io.github.thewisekd.phopencode.feature.chat.logic.palette

import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteEvent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PalettePresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PaletteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P7.1) for the command palette: shows the initial state and ignores
 * intents. P7.8 fills it in without changing the constructor; work starts in [scope] after a
 * `yield()`, never in the constructor, and every server call goes through [ChatSession.call].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultPalettePresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : PalettePresenter {
    private val mutableState = MutableStateFlow(PaletteState())

    override val state: StateFlow<PaletteState> = mutableState.asStateFlow()

    override val events: Flow<PaletteEvent> = emptyFlow()

    override fun onIntent(intent: PaletteIntent) = Unit
}
