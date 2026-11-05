package io.github.thewisekd.phopencode.feature.chat.logic.permission

import io.github.thewisekd.phopencode.core.navigation.ChatRoute
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatSession
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionIntent
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionPresenter
import io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract.PermissionsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P7.1) for the permission requests of this session and its subagents:
 * shows the initial state and ignores intents. P7.4 fills it in without changing the constructor;
 * work starts in [scope] after a `yield()`, never in the constructor, and every server call goes
 * through [ChatSession.call].
 *
 * @param route The destination's route.
 * @param scope The destination's scope; work started here stops with it.
 * @param session The destination's session, shared with its other presenters.
 */
@Suppress("unused")
class DefaultPermissionPresenter(
    private val route: ChatRoute,
    private val scope: CoroutineScope,
    private val session: ChatSession,
) : PermissionPresenter {
    private val mutableState = MutableStateFlow(PermissionsState())

    override val state: StateFlow<PermissionsState> = mutableState.asStateFlow()

    override val events: Flow<Nothing> = emptyFlow()

    override fun onIntent(intent: PermissionIntent) = Unit
}
