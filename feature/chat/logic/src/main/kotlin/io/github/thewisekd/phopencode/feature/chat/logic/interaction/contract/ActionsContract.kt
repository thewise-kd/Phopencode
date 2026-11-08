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
 * - **Files and git** of the session's folder (`Session.directory`): [ActionsEvent.OpenFiles] and
 *   [ActionsEvent.OpenGit], only while [ActionsState.directory] is known. They ask the server
 *   nothing here, so they do not wait for [ActionsState.enabled].
 *
 * - **New session** (the `/new` built-in): `SessionApi.create` with the session's folder as its
 *   location, nothing else, as the session list's creation leaves the rest to the server; through
 *   `ChatSession.call`, only while [ActionsState.canAct] and [ActionsState.directory] is known. It
 *   does not ask first. Once the server made it, [ActionsEvent.OpenSession] switches the chat to it.
 * - **Undo and redo** (the `/undo` and `/redo` built-ins): [ActionsState.undoTurn] and
 *   [ActionsState.redoTurn] name the turn whose page ([ActionsIntent.ShowTurnChanges]) stages or
 *   drops a revert, with the page's own confirmation.
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
 * @property directory The loaded session's folder, which files and git open; null until the
 *   session is loaded, when it has none, and on any route but `ChatRoute.Chat`. Prints masked.
 * @property undoTurn The latest user message of the loaded session before the staged revert's
 *   (of all of them when none is staged): what `/undo` opens. None when the staged revert's message
 *   is not among those held, as it may already cover them all. Prints as a flag.
 * @property redoTurn The user message of the staged revert, if any: what `/redo` opens. Prints as
 *   a flag.
 */
data class ActionsState(
    val enabled: Boolean = false,
    val menuOpen: Boolean = false,
    val confirming: SessionAction? = null,
    val running: SessionAction? = null,
    val compacting: Boolean = false,
    val error: ActionError? = null,
    val directory: String? = null,
    val undoTurn: MessageId? = null,
    val redoTurn: MessageId? = null,
) {
    /** Whether an action could start: none runs. */
    val canAct: Boolean
        get() = enabled && running == null

    override fun toString(): String =
        "ActionsState(enabled=$enabled, menuOpen=$menuOpen, confirming=$confirming, running=$running, " +
            "compacting=$compacting, error=$error, directory=${if (directory == null) "null" else "***"}, " +
            "undoTurn=${undoTurn != null}, redoTurn=${redoTurn != null})"
}

sealed interface SessionAction {
    data object Compact : SessionAction

    /** @property messageId The copy ends before this message; null copies the whole history. */
    data class Fork(val messageId: MessageId?) : SessionAction

    /** A new session in the session's folder; sent by [ActionsIntent.NewSession], never asked first. */
    data object NewSession : SessionAction
}

data class ActionError(val action: SessionAction, val reason: ChatFailure)

sealed interface ActionsIntent {
    data object OpenMenu : ActionsIntent

    data object CloseMenu : ActionsIntent

    /**
     * Asks to run [action]: closes the menu and sets [ActionsState.confirming].
     * [SessionAction.NewSession] is not asked for; it only closes the menu.
     */
    data class Request(val action: SessionAction) : ActionsIntent

    /** Runs [ActionsState.confirming]. */
    data object Confirm : ActionsIntent

    /** Drops [ActionsState.confirming]. */
    data object Cancel : ActionsIntent

    /** Opens the changes of the turn the prompt [messageId] started. */
    data class ShowTurnChanges(val messageId: MessageId) : ActionsIntent

    data object DismissError : ActionsIntent

    /** Opens the files of [ActionsState.directory]; nothing while it is not known. */
    data object OpenFiles : ActionsIntent

    /** Opens git for [ActionsState.directory]; nothing while it is not known. */
    data object OpenGit : ActionsIntent

    /** Creates a session in [ActionsState.directory] and switches to it; nothing while it is not known. */
    data object NewSession : ActionsIntent
}

sealed interface ActionsEvent {
    /** The fork or new session [sessionId] was made; the chat switches to it, in place of this one. */
    data class OpenSession(val sessionId: SessionId) : ActionsEvent

    /** Opens `ChatRoute.TurnDiff` for this session and [messageId]. */
    data class OpenTurnDiff(val messageId: MessageId) : ActionsEvent

    /** The server accepted a compaction; the chat brings the timeline back to the newest item. */
    data object CompactionStarted : ActionsEvent

    /** Opens `FilesRoute.Tree` for [directory], the session's folder. Prints masked. */
    data class OpenFiles(val directory: String) : ActionsEvent {
        override fun toString(): String = "OpenFiles(***)"
    }

    /** Opens `GitRoute.Status` for [directory], the session's folder. Prints masked. */
    data class OpenGit(val directory: String) : ActionsEvent {
        override fun toString(): String = "OpenGit(***)"
    }
}

interface ActionsPresenter : Presenter<ActionsState, ActionsIntent, ActionsEvent>
