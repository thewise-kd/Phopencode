package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter

/**
 * What one turn changed, and undoing it: the `ChatRoute.TurnDiff` page (P7.12 builds it, P7.13
 * draws it). The turn is the route's user message.
 *
 * Where it comes from, all through `ChatSession.call`:
 * - [files]: `MessageApi.turnDiff(sessionId, from = messageId)`, once the chat is live, and again
 *   on [RevertIntent.Retry].
 * - [staged]: `Session.revert` of `ChatSessionState.session`, which the sync keeps current through
 *   `revert.*` events: a revert staged for this turn or another.
 *
 * Reverting takes two steps, so nothing is lost on one tap:
 * 1. [RevertIntent.Revert] stages it: `MessageApi.stageRevert(sessionId, messageId)`. Nothing is
 *    final yet; [staged] shows what would change.
 * 2. [RevertIntent.Confirm] makes it final: `MessageApi.commitRevert(sessionId)`, which removes
 *    the turn and all after it, then [RevertEvent.Reverted]. [RevertIntent.Cancel] drops it:
 *    `MessageApi.cancelRevert(sessionId)`, also for a revert staged for another turn.
 * One step at a time; a failed one sets [error] and changes nothing.
 *
 * @property loading The diff is not loaded yet.
 * @property enabled The session is loaded and its server connected.
 * @property files The turn's changed files, in the server's order. Print as a count.
 * @property loadError Loading the diff failed.
 * @property staged The revert staged on the session, if any.
 * @property pending The step on its way.
 * @property error The last step failed.
 */
data class RevertState(
    val loading: Boolean = true,
    val enabled: Boolean = false,
    val files: List<FileDiff> = emptyList(),
    val loadError: ChatFailure? = null,
    val staged: StagedRevert? = null,
    val pending: RevertAction? = null,
    val error: RevertError? = null,
) {
    /** Whether [RevertIntent.Revert] would stage: nothing staged, nothing on its way. */
    val canRevert: Boolean
        get() = enabled && staged == null && pending == null

    /** Whether [RevertIntent.Confirm] would commit: this turn's revert is staged. */
    val canConfirm: Boolean
        get() = enabled && staged?.thisTurn == true && pending == null

    /** Whether [RevertIntent.Cancel] would drop a staged revert. */
    val canCancel: Boolean
        get() = enabled && staged != null && pending == null

    override fun toString(): String =
        "RevertState(loading=$loading, enabled=$enabled, files=${files.size}, loadError=$loadError, staged=$staged, " +
            "pending=$pending, error=$error)"
}

/**
 * A staged revert.
 *
 * @property messageId The user message it undoes, with all after it.
 * @property files What it would change. Print as a count.
 * @property thisTurn It is for this page's turn.
 */
data class StagedRevert(
    val messageId: MessageId,
    val files: List<FileDiff> = emptyList(),
    val thisTurn: Boolean,
) {
    override fun toString(): String =
        "StagedRevert(messageId=${messageId.value}, files=${files.size}, thisTurn=$thisTurn)"
}

enum class RevertAction {
    Stage,
    Commit,
    Cancel,
}

data class RevertError(val action: RevertAction, val reason: ChatFailure)

sealed interface RevertIntent {
    /** Stages the revert of this turn. */
    data object Revert : RevertIntent

    /** Commits this turn's staged revert. */
    data object Confirm : RevertIntent

    /** Drops the staged revert. */
    data object Cancel : RevertIntent

    /** Loads the diff again after [RevertState.loadError]. */
    data object Retry : RevertIntent

    data object DismissError : RevertIntent

    /** Leaves the page. */
    data object Close : RevertIntent
}

sealed interface RevertEvent {
    /** The revert is final; the chat goes back to the session. */
    data object Reverted : RevertEvent

    data object Close : RevertEvent
}

interface RevertPresenter : Presenter<RevertState, RevertIntent, RevertEvent>
