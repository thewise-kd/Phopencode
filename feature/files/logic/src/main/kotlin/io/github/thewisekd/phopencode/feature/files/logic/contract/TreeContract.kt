package io.github.thewisekd.phopencode.feature.files.logic.contract

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType

/**
 * The folder tree of one project folder, `FilesRoute.Tree` (P8.4 logic, P8.5 screen). Moving between
 * folders is state of this screen, not a new route; system back goes up a folder until [isAtRoot].
 *
 * Where it comes from: the entries of [folder] from [FileListing.list]; the git badges from
 * [GitChanges.changes], a file's own status, and [GitBadge.ContainsChanges] on a folder holding
 * changed files. A failed git status leaves the badges out; it is not an error of the tree.
 *
 * The file operations of the same destination ([FileOpsPresenter]) run beside it: the graph
 * forwards [TreeEvent.OpenActions] to them, and their [FileOpsEvent.Changed] back as
 * [TreeIntent.Refresh].
 *
 * @property directory The project's folder, from the route; never changes.
 * @property folder The folder shown; starts as [directory].
 * @property refreshing A reload with the entries still shown.
 * @property entries Sorted by [sort], folders first; hidden names left out unless [showHidden].
 * @property error The folder could not be listed.
 */
data class TreeState(
    val directory: String,
    val folder: String = directory,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val entries: List<TreeEntry> = emptyList(),
    val showHidden: Boolean = false,
    val sort: TreeSort = TreeSort.Name,
    val error: FilesFailure? = null,
) {
    val isAtRoot: Boolean
        get() = folder == directory

    /** Loaded, without entries to show and without an error. */
    val isEmpty: Boolean
        get() = !loading && error == null && entries.isEmpty()

    override fun toString(): String =
        "TreeState(directory=***, folder=***, loading=$loading, refreshing=$refreshing, " +
            "entries=${entries.size}, showHidden=$showHidden, sort=$sort, error=$error)"
}

/**
 * One row of the tree.
 *
 * @property badge The git status; null when unchanged or not known.
 */
data class TreeEntry(val entry: FileEntry, val badge: GitBadge? = null) {
    /** A folder; a symlink is not, until the tree logic resolves it. */
    val isFolder: Boolean
        get() = entry.type == FileEntryType.Directory

    /** A dot name, which the tree hides unless asked. */
    val isHidden: Boolean
        get() = entry.name.startsWith(".")

    override fun toString(): String =
        "TreeEntry(type=${entry.type}, ignored=${entry.ignored}, badge=$badge)"
}

enum class GitBadge {
    Added,
    Modified,
    Deleted,
    Renamed,
    Untracked,

    /** A folder with changed files somewhere below it. */
    ContainsChanges,
}

/** Folders always come before files; within each, this order. */
enum class TreeSort {
    Name,

    /** Newest first. */
    Modified,

    /** Largest first. */
    Size,
}

sealed interface TreeIntent {
    /** A row tapped: a folder opens in place, a file sends [TreeEvent.OpenFile]. */
    data class Open(val entry: TreeEntry) : TreeIntent {
        override fun toString(): String = "Open($entry)"
    }

    /**
     * Shows [path] inside the project's folder: a path bar segment, or a folder found by search
     * (the graph forwards it).
     */
    data class OpenFolder(val path: String) : TreeIntent {
        override fun toString(): String = "OpenFolder(***)"
    }

    /** The parent folder; nothing at [TreeState.isAtRoot]. */
    data object Up : TreeIntent

    /**
     * Lists [TreeState.folder] again, keeping the rows until the new ones arrive. The graph sends it
     * whenever the screen is shown again (after another screen, after the app came back, after a
     * rotation) and after a file operation.
     */
    data object Refresh : TreeIntent

    data class SetShowHidden(val show: Boolean) : TreeIntent

    data class SortBy(val sort: TreeSort) : TreeIntent

    /** A long press on a row: sends [TreeEvent.OpenActions] with [FileOpsRequest.Actions]. */
    data class ShowActions(val entry: TreeEntry) : TreeIntent {
        override fun toString(): String = "ShowActions($entry)"
    }

    /** Sends [TreeEvent.OpenActions] with [FileOpsRequest.NewFolder] in [TreeState.folder]. */
    data object NewFolder : TreeIntent

    data object OpenSearch : TreeIntent

    /** Sends [TreeEvent.OpenTransfer] for [TreeState.folder]. */
    data object OpenTransfer : TreeIntent

    /** Lists again after [TreeState.error]. */
    data object Retry : TreeIntent

    data object Close : TreeIntent
}

sealed interface TreeEvent {
    data class OpenFile(val path: String) : TreeEvent {
        override fun toString(): String = "OpenFile(***)"
    }

    data object OpenSearch : TreeEvent

    /** @property folder Where uploads go: [TreeState.folder]. */
    data class OpenTransfer(val folder: String) : TreeEvent {
        override fun toString(): String = "OpenTransfer(***)"
    }

    /** For the file operations of the same destination; the graph forwards it as [FileOpsIntent.Open]. */
    data class OpenActions(val request: FileOpsRequest) : TreeEvent

    data object Close : TreeEvent
}

interface TreePresenter : Presenter<TreeState, TreeIntent, TreeEvent>
