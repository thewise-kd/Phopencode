package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.model.FileEntry

enum class FolderError {
    /** No connection stands, so the server cannot be browsed. */
    NotConnected,

    /** The folder cannot be read with the logged-in user's permissions. */
    PermissionDenied,

    NotFound,

    /** Any other SFTP failure. */
    Failed,

    /** The recent folders could not be read or written. */
    StorageFailed,
}

/**
 * Picking the project folder on the server of the active connection, over SFTP (P4.12, P4.13).
 * Paths are absolute paths on the server.
 *
 * @property path The folder shown, or null before the first listing.
 * @property entries The children of [path] that can be opened as folders.
 * @property recent The active server's recent folders, newest first.
 * @property errorDetail With [FolderError.Failed] from a failed listing, the failure's class chain,
 *   for example `SshException.Sftp(Failed) <- java.io.EOFException`: class names only, never a
 *   message, path or host. Null otherwise, and cleared with the error.
 */
data class FolderState(
    val loading: Boolean = true,
    val path: String? = null,
    val entries: List<FileEntry> = emptyList(),
    val recent: List<String> = emptyList(),
    val error: FolderError? = null,
    val errorDetail: String? = null,
) {
    /** [path] has a parent folder. */
    val canGoUp: Boolean get() = path != null && path.trimEnd('/').isNotEmpty()
}

sealed interface FolderIntent {
    /** Shows [path], a child of the current folder or a recent folder. */
    data class Open(val path: String) : FolderIntent

    /** Shows the parent of the current folder. */
    data object Up : FolderIntent

    /** Picks [path] as the project folder and puts it first among the recent folders. */
    data class Select(val path: String) : FolderIntent

    data class RemoveRecent(val path: String) : FolderIntent

    /** Lists the current folder again, for example after an error. */
    data object Retry : FolderIntent

    data object DismissError : FolderIntent

    data object Close : FolderIntent
}

sealed interface FolderEvent {
    /** @property directory The picked folder, absolute. */
    data class Selected(val directory: String) : FolderEvent

    data object Close : FolderEvent
}

interface FolderPresenter : Presenter<FolderState, FolderIntent, FolderEvent>
