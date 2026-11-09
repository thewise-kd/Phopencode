package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.SessionId

/**
 * Forms and permission requests waiting for an answer, from every session of the active
 * connection's server, in one list: `NotifyRoute.Pending` (P11.11 logic, P11.12 screen). The
 * notification's tap opens it through `DeepLinks.PENDING`, possibly before the app has a
 * connection.
 *
 * Where it comes from, through [NotifyConnection]: `InteractionApi.listForms` and
 * `listPermissions` with no session, for the server's default folder and for each folder its
 * sessions are in (`SessionApi.list`), so that sessions of every folder are covered. Each item's
 * session is looked up (`SessionApi.get`) for its title and parent. Live updates come from the
 * client's `EventApi.events`: `FormCreated` and `PermissionAsked` add an item, `FormReplied`,
 * `FormCancelled` and `PermissionReplied` remove it, and `SessionDeleted` removes its session's
 * items. Each `ServerEvent.Connected` and each new generation reads the list again.
 *
 * The list does not answer: tapping an item opens its session ([PendingEvent.OpenSession]), where
 * the chat's form and permission cards answer it; the answer's event then removes it here.
 *
 * Without a connection, [error] is [NotifyFailure.NotConnected] and [PendingIntent.Connect] goes to
 * the server list.
 *
 * @property loading The list is read for the first time and nothing is shown yet.
 * @property refreshing The list is read again while [items] stay shown.
 * @property items Forms and permissions together, in the order they were asked, oldest first; an
 *   item read again keeps its place.
 * @property error The last read failed; [items] keep what was read before it.
 */
data class PendingState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<PendingItem> = emptyList(),
    val error: NotifyFailure? = null,
) {
    /** Read, without an error and with nothing waiting. */
    val isEmpty: Boolean
        get() = !loading && error == null && items.isEmpty()

    /** Items hold what the server asked: counts only. */
    override fun toString(): String =
        "PendingState(loading=$loading, refreshing=$refreshing, items=${items.size}, error=$error)"
}

/**
 * One form or permission request waiting for an answer. Both may belong to a child session, a
 * subagent's: [isChild] tells the graph to open it as `ChatRoute.ChildSession`.
 *
 * Prints its IDs only: titles, fields, actions and resources are the server's text and can hold a
 * path or a command.
 *
 * @property sessionTitle The session's title, if it has one and it was read.
 * @property parentId The session's parent, for a child session; null for a root session or when
 *   it was not read.
 */
sealed interface PendingItem {
    val sessionId: SessionId
    val sessionTitle: String?
    val parentId: SessionId?

    /** Unique in the list, also between a form and a request with the same ID. */
    val key: String

    val isChild: Boolean
        get() = parentId != null

    data class FormItem(
        val form: Form,
        override val sessionTitle: String?,
        override val parentId: SessionId?,
    ) : PendingItem {
        override val sessionId: SessionId
            get() = form.sessionId

        override val key: String
            get() = "form:${form.id.value}"

        override fun toString(): String =
            "FormItem(id=${form.id.value}, session=${sessionId.value}, parent=${parentId?.value})"
    }

    data class PermissionItem(
        val request: PermissionRequest,
        override val sessionTitle: String?,
        override val parentId: SessionId?,
    ) : PendingItem {
        override val sessionId: SessionId
            get() = request.sessionId

        override val key: String
            get() = "permission:${request.id.value}"

        override fun toString(): String =
            "PermissionItem(id=${request.id.value}, session=${sessionId.value}, parent=${parentId?.value})"
    }
}

sealed interface PendingIntent {
    /** Reads the list again, keeping [PendingState.items] shown. */
    data object Refresh : PendingIntent

    /** Opens [item]'s session: [PendingEvent.OpenSession]. */
    data class Open(val item: PendingItem) : PendingIntent

    /** Goes to the server list to connect: [PendingEvent.OpenServerList]. */
    data object Connect : PendingIntent

    /** Clears [PendingState.error]. */
    data object DismissError : PendingIntent

    data object Close : PendingIntent
}

sealed interface PendingEvent {
    /**
     * Opens the session the item belongs to: `ChatRoute.ChildSession` when [isChild], otherwise
     * `ChatRoute.Chat`. The pending list stays below it, so going back returns here.
     */
    data class OpenSession(val sessionId: SessionId, val isChild: Boolean) : PendingEvent

    /** No connection: the server list, to connect. [ApiError.Closed] calls end up here too. */
    data object OpenServerList : PendingEvent

    data object Close : PendingEvent
}

interface PendingPresenter : Presenter<PendingState, PendingIntent, PendingEvent>
