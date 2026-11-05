package io.github.thewisekd.phopencode.feature.chat.logic.interaction.contract

import io.github.thewisekd.phopencode.core.model.PermissionDecision
import io.github.thewisekd.phopencode.core.model.PermissionRequestId
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.feature.chat.logic.contract.ChatFailure
import io.github.thewisekd.phopencode.feature.chat.logic.contract.Presenter

/**
 * The permission requests the server waits on, as cards in the chat (P7.4 builds them, P7.5 draws
 * them). One card per pending request, oldest first.
 *
 * Where they come from, as for forms (see [FormsState]): this session's from
 * `SessionState.permissions` of the chat's `ChatSession`; its subagents', at any depth, from
 * `InteractionApi.listPermissions(sessionId = null, location = Location(session.directory))`, kept
 * to sessions that descend from this one, with `permission.asked` and `permission.replied` from
 * `client.events.events(types)` collected inside `ChatSession.call`, and listed again on every
 * `ServerEvent.Connected`.
 *
 * Replying: `InteractionApi.replyPermission(card.sessionId, card.requestId, decision, message)`
 * through `ChatSession.call`, to the session the request names. [PermissionDecision.Once] goes at
 * once. [PermissionDecision.Always] saves a rule as wide as [PermissionCardState.save], so it goes
 * only after a separate confirmation. [PermissionDecision.Reject] goes with the rejection message,
 * trimmed, or none when blank. One reply at a time per card; a failed reply keeps the card. A card
 * leaves once the server settles the request; a second reply fails with `NotFound`, which also
 * removes the card.
 *
 * @property cards Oldest first.
 * @property error Listing the subagents' requests failed; this session's still show.
 */
data class PermissionsState(
    val cards: List<PermissionCardState> = emptyList(),
    val error: ChatFailure? = null,
)

/**
 * One pending request.
 *
 * @property sessionId The session the request belongs to; the reply goes there.
 * @property fromSubagent The request comes from a subagent's session, not this one.
 * @property action What is asked, for example `bash`, as the server names it.
 * @property resources What [action] applies to: paths, commands. Prints masked.
 * @property message The server's own wording of the request, if any. Prints masked.
 * @property save What an `always` decision would remember. Prints masked.
 * @property confirmingAlways `always` was picked and waits for its confirmation.
 * @property rejecting The rejection message is being written.
 * @property rejectMessage The rejection message so far. Prints masked.
 * @property replying The decision on its way.
 * @property error The last reply failed.
 */
data class PermissionCardState(
    val requestId: PermissionRequestId,
    val sessionId: SessionId,
    val fromSubagent: Boolean = false,
    val action: String,
    val resources: List<String> = emptyList(),
    val message: String? = null,
    val save: List<String> = emptyList(),
    val confirmingAlways: Boolean = false,
    val rejecting: Boolean = false,
    val rejectMessage: String = "",
    val replying: PermissionDecision? = null,
    val error: PermissionError? = null,
) {
    /** Whether a decision would be sent. */
    val canReply: Boolean
        get() = replying == null

    override fun toString(): String =
        "PermissionCardState(requestId=${requestId.value}, sessionId=${sessionId.value}, fromSubagent=$fromSubagent, " +
            "action=$action, resources=${resources.size}, message=${if (message == null) "null" else "***"}, " +
            "save=${save.size}, confirmingAlways=$confirmingAlways, rejecting=$rejecting, rejectMessage=***, " +
            "replying=$replying, error=$error)"
}

data class PermissionError(val decision: PermissionDecision, val reason: ChatFailure)

sealed interface PermissionIntent {
    /** Allows this request once. */
    data class AllowOnce(val requestId: PermissionRequestId) : PermissionIntent

    /** Asks to allow always: sets [PermissionCardState.confirmingAlways], sends nothing yet. */
    data class AllowAlways(val requestId: PermissionRequestId) : PermissionIntent

    /** Sends [PermissionDecision.Always], only while [PermissionCardState.confirmingAlways]. */
    data class ConfirmAlways(val requestId: PermissionRequestId) : PermissionIntent

    data class CancelAlways(val requestId: PermissionRequestId) : PermissionIntent

    /** Opens the rejection message: sets [PermissionCardState.rejecting], sends nothing yet. */
    data class StartReject(val requestId: PermissionRequestId) : PermissionIntent

    data class RejectMessageChanged(val requestId: PermissionRequestId, val message: String) : PermissionIntent {
        override fun toString(): String = "RejectMessageChanged(requestId=${requestId.value}, message=***)"
    }

    /** Sends [PermissionDecision.Reject] with [PermissionCardState.rejectMessage]; works without [StartReject] too. */
    data class Reject(val requestId: PermissionRequestId) : PermissionIntent

    data class CancelReject(val requestId: PermissionRequestId) : PermissionIntent

    data class DismissError(val requestId: PermissionRequestId) : PermissionIntent

    /** Lists the subagents' requests again after [PermissionsState.error]. */
    data object Retry : PermissionIntent
}

/** Permissions have no one-time events. */
interface PermissionPresenter : Presenter<PermissionsState, PermissionIntent, Nothing>
