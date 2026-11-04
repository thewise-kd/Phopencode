package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.model.ServerProfile
import io.github.thewisekd.phopencode.core.model.ServerProfileId

/**
 * The saved servers, the start of the app (P4.4, P4.5).
 *
 * @property servers Most recently used first, as `ProfileRepository` sorts them. [ServerProfile]
 *   holds references only, never a secret.
 * @property pendingDelete The server whose deletion waits for the user's confirmation.
 */
data class ServerListState(
    val loading: Boolean = true,
    val servers: List<ServerProfile> = emptyList(),
    val pendingDelete: ServerProfile? = null,
    val error: ServerListError? = null,
)

enum class ServerListError {
    /** The saved servers could not be read. */
    LoadFailed,

    /** A server could not be marked as used, so the connection did not start. */
    SaveFailed,

    DeleteFailed,
}

sealed interface ServerListIntent {
    /** Connects to the server: marks it most recently used, then opens the connection flow. */
    data class Select(val id: ServerProfileId) : ServerListIntent

    data object Add : ServerListIntent

    data class Edit(val id: ServerProfileId) : ServerListIntent

    data object OpenKeys : ServerListIntent

    data object OpenSettings : ServerListIntent

    /** Asks for confirmation; nothing is deleted yet. */
    data class RequestDelete(val id: ServerProfileId) : ServerListIntent

    /**
     * Deletes [ServerListState.pendingDelete] with its recent folders and the secrets only it refers
     * to; a secret another profile or the key index still refers to stays.
     */
    data object ConfirmDelete : ServerListIntent

    data object CancelDelete : ServerListIntent

    data object DismissError : ServerListIntent
}

sealed interface ServerListEvent {
    data class OpenProgress(val id: ServerProfileId) : ServerListEvent

    /** @property id The server to edit, or null for a new one. */
    data class OpenForm(val id: ServerProfileId?) : ServerListEvent

    data object OpenKeys : ServerListEvent

    data object OpenSettings : ServerListEvent
}

interface ServerListPresenter : Presenter<ServerListState, ServerListIntent, ServerListEvent>
