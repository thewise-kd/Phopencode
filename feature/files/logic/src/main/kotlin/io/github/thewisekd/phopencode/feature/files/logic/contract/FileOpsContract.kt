package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.model.FileEntry

/**
 * Delete, rename and new folder (P8.10 logic, P8.11 menu and dialogs). They have no route: they run
 * on the tree's destination, `FilesRoute.Tree`, over the tree.
 *
 * The flow: the tree's [TreeEvent.OpenActions] arrives as [FileOpsIntent.Open]. An entry opens the
 * menu with [FileOpsState.menuActions]; choosing an action opens its dialog or sends its event.
 * Every change goes through [FileChanges] and ends with [FileOpsEvent.Changed], which refreshes the
 * tree. Names are checked before anything is sent ([NameProblem]); deleting a folder always asks
 * first ([FileOpsDialog.ConfirmDelete]) and then deletes its content too.
 *
 * @property menu The entry whose menu is open.
 * @property menuActions What the menu offers for [menu]: a file gets every [FileAction], a folder
 *   [FileAction.Rename] and [FileAction.Delete].
 * @property dialog The open dialog; the menu closes when one opens.
 * @property busy The operation under way; the dialog stays open, without taking input, until it ends.
 * @property error The last operation failed.
 */
data class FileOpsState(
    val menu: FileOpsRequest.Actions? = null,
    val menuActions: List<FileAction> = emptyList(),
    val dialog: FileOpsDialog? = null,
    val busy: FileOp? = null,
    val error: FileOpError? = null,
) {
    /** Whether [FileOpsIntent.Submit] or [FileOpsIntent.ConfirmDelete] would run. */
    val canSubmit: Boolean
        get() = busy == null && when (val open = dialog) {
            is FileOpsDialog.Rename -> open.name.isNotBlank() && open.problem == null
            is FileOpsDialog.NewFolder -> open.name.isNotBlank() && open.problem == null
            is FileOpsDialog.ConfirmDelete -> true
            null -> false
        }
}

/** What the tree asks the file operations to open. */
sealed interface FileOpsRequest {
    /** The folder the tree shows, where new names go. */
    val folder: String

    /** The menu for [entry]. */
    data class Actions(val entry: FileEntry, override val folder: String) : FileOpsRequest {
        override fun toString(): String = "Actions(type=${entry.type})"
    }

    /** The new folder dialog. */
    data class NewFolder(override val folder: String) : FileOpsRequest {
        override fun toString(): String = "NewFolder(***)"
    }
}

enum class FileAction {
    /** Sends [FileOpsEvent.OpenEditor]. */
    Edit,

    /** Sends [FileOpsEvent.Download]. */
    Download,
    Rename,
    Delete,
}

sealed interface FileOpsDialog {
    /** @property name As typed; starts as the entry's name. */
    data class Rename(val entry: FileEntry, val name: String, val problem: NameProblem? = null) : FileOpsDialog {
        override fun toString(): String = "Rename(type=${entry.type}, name=***, problem=$problem)"
    }

    /** @property folder Where the folder is created. */
    data class NewFolder(val folder: String, val name: String = "", val problem: NameProblem? = null) : FileOpsDialog {
        override fun toString(): String = "NewFolder(folder=***, name=***, problem=$problem)"
    }

    /** Deleting cannot be undone; for a folder, its whole content goes with it. */
    data class ConfirmDelete(val entry: FileEntry) : FileOpsDialog {
        override fun toString(): String = "ConfirmDelete(type=${entry.type})"
    }
}

/** Why a typed name is refused, before anything is sent. */
enum class NameProblem {
    /** Empty or only spaces. */
    Blank,

    /** Holds `/`. */
    Separator,

    /** `.` or `..`. */
    Reserved,

    /** Longer than 255 bytes in UTF-8. */
    TooLong,

    /** Holds NUL or another control character. */
    ControlCharacter,

    /** Something in the folder already has it; also what [FilesFailure.AlreadyExists] shows as. */
    Taken,
}

enum class FileOp {
    Rename,
    Delete,
    CreateFolder,
}

data class FileOpError(val op: FileOp, val reason: FilesFailure)

sealed interface FileOpsIntent {
    /** Forwarded by the graph from [TreeEvent.OpenActions]; replaces whatever was open. */
    data class Open(val request: FileOpsRequest) : FileOpsIntent

    /** A menu item of [FileOpsState.menuActions]. */
    data class Choose(val action: FileAction) : FileOpsIntent

    /** The name field of [FileOpsDialog.Rename] or [FileOpsDialog.NewFolder]. */
    data class NameChanged(val name: String) : FileOpsIntent {
        override fun toString(): String = "NameChanged(***)"
    }

    /** Renames or creates the folder, trimmed, when [FileOpsState.canSubmit]. */
    data object Submit : FileOpsIntent

    /** Deletes the entry of [FileOpsDialog.ConfirmDelete], irreversibly. */
    data object ConfirmDelete : FileOpsIntent

    /** Closes the menu or the dialog; an operation under way is not stopped. */
    data object Cancel : FileOpsIntent

    data object DismissError : FileOpsIntent
}

sealed interface FileOpsEvent {
    /** [folder]'s content changed; the graph forwards [TreeIntent.Refresh]. */
    data class Changed(val folder: String) : FileOpsEvent {
        override fun toString(): String = "Changed(***)"
    }

    /**
     * Download [path]: the graph opens `FilesRoute.Transfer` for [folder] and hands it [path] as
     * [TransferIntent.RequestDownload].
     */
    data class Download(val path: String, val folder: String) : FileOpsEvent {
        override fun toString(): String = "Download(***)"
    }

    data class OpenEditor(val path: String) : FileOpsEvent {
        override fun toString(): String = "OpenEditor(***)"
    }
}

interface FileOpsPresenter : Presenter<FileOpsState, FileOpsIntent, FileOpsEvent>
