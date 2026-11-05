package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter

/**
 * The session's action menu, and the actions on one prompt of the timeline (P7.14 builds them,
 * P7.15 draws them).
 *
 * - **Compact**: `SessionApi.compact(sessionId)` through `ChatSession.call`. Accepted is not done:
 *   the timeline shows the compaction's progress, and [ActionsState.compacting] holds from
 *   `CompactionStarted` until it ends or fails, as `SessionState.messages` show it.
 * - **Fork**: `SessionApi.fork(sessionId, before = messageId)` through `ChatSession.call`; the copy
 *   holds the history before that message, or all of it for `null`. Once the server made it,
 *   [ActionsEvent.OpenSession] switches the chat to the new session.
 * - **This turn's changes**, from a prompt's actions: [ActionsEvent.OpenTurnDiff].
 *
 * Compact and fork ask first: [ActionsIntent.Request] sets [ActionsState.confirming], and only
 * [ActionsIntent.Confirm] sends. One action at a time; a failed one sets [ActionsState.error].
 *
 * @property enabled The session is loaded and its server connected.
 * @property menuOpen The session's menu is shown.
 * @property confirming The action waiting for its confirmation.
 * @property running The action on its way.
 * @property compacting The server is compacting the session.
 * @property error The last action failed.
 */
data class ActionsState(
    val enabled: Boolean = false,
    val menuOpen: Boolean = false,
    val confirming: SessionAction? = null,
    val running: SessionAction? = null,
    val compacting: Boolean = false,
    val error: ActionError? = null,
) {
    /** Whether an action could start: none runs. */
    val canAct: Boolean
        get() = enabled && running == null
}

sealed interface SessionAction {
    data object Compact : SessionAction

    /** @property messageId The copy ends before this message; null copies the whole history. */
    data class Fork(val messageId: MessageId?) : SessionAction
}

data class ActionError(val action: SessionAction, val reason: ChatFailure)

sealed interface ActionsIntent {
    data object OpenMenu : ActionsIntent

    data object CloseMenu : ActionsIntent

    /** Asks to run [action]: closes the menu and sets [ActionsState.confirming]. */
    data class Request(val action: SessionAction) : ActionsIntent

    /** Runs [ActionsState.confirming]. */
    data object Confirm : ActionsIntent

    /** Drops [ActionsState.confirming]. */
    data object Cancel : ActionsIntent

    /** Opens the changes of the turn the prompt [messageId] started. */
    data class ShowTurnChanges(val messageId: MessageId) : ActionsIntent

    data object DismissError : ActionsIntent
}

sealed interface ActionsEvent {
    /** The fork [sessionId] was made; the chat switches to it, in place of this one. */
    data class OpenSession(val sessionId: SessionId) : ActionsEvent

    /** Opens `ChatRoute.TurnDiff` for this session and [messageId]. */
    data class OpenTurnDiff(val messageId: MessageId) : ActionsEvent

    /** The server accepted a compaction; the chat brings the timeline back to the newest item. */
    data object CompactionStarted : ActionsEvent
}

interface ActionsPresenter : Presenter<ActionsState, ActionsIntent, ActionsEvent>
