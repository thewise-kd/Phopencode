package io.github.thewisekd.phopencode.feature.files.logic.contract

/**
 * Editing one text file, `FilesRoute.Editor` (P8.8 logic, P8.9 screen).
 *
 * Where it comes from: [FileContent.stat] and [FileContent.read]; the file's `modifiedAt` at load
 * is kept. Saving checks [FileContent.stat] first: a different `modifiedAt` means someone else
 * changed the file, which shows as [EditorState.conflict] instead of saving. Saving writes through
 * [FileContent.write], atomically, with the file's own [LineEnding] put back on every line.
 *
 * @property path The file, from the route.
 * @property text The text as edited, with `\n` line endings; prints masked.
 * @property dirty [text] differs from what was loaded or last saved.
 * @property lineEnding The file's line ending, kept on save.
 * @property conflict The file changed on the server since it was loaded; the user overwrites,
 *   reloads or keeps editing.
 * @property confirmingLeave Leaving with unsaved changes asks first.
 * @property blocked The file cannot be edited safely; nothing can be saved.
 * @property loadError The file could not be read.
 * @property saveError The last save failed; the text is kept.
 */
data class EditorState(
    val path: String,
    val loading: Boolean = true,
    val text: String = "",
    val dirty: Boolean = false,
    val lineEnding: LineEnding = LineEnding.Lf,
    val saving: Boolean = false,
    val conflict: Boolean = false,
    val confirmingLeave: Boolean = false,
    val blocked: EditorBlock? = null,
    val loadError: FilesFailure? = null,
    val saveError: FilesFailure? = null,
) {
    /** Whether [EditorIntent.Save] would write. */
    val canSave: Boolean
        get() = !loading && blocked == null && loadError == null && dirty && !saving

    override fun toString(): String =
        "EditorState(path=***, loading=$loading, text=***, dirty=$dirty, lineEnding=$lineEnding, " +
            "saving=$saving, conflict=$conflict, confirmingLeave=$confirmingLeave, blocked=$blocked, " +
            "loadError=$loadError, saveError=$saveError)"
}

/** The first line ending the file uses; a file without one is [Lf]. */
enum class LineEnding {
    Lf,
    CrLf,
    Cr,
}

enum class EditorBlock {
    /** Not text. */
    Binary,

    /** Above the editor's limit. */
    TooLarge,

    /** Not valid UTF-8: saving would change bytes the user did not touch. */
    Malformed,
}

sealed interface EditorIntent {
    data class TextChanged(val text: String) : EditorIntent {
        override fun toString(): String = "TextChanged(***)"
    }

    /** Saves when [EditorState.canSave]; a conflict stops it. */
    data object Save : EditorIntent

    /** Saves over the server's newer version after [EditorState.conflict]. */
    data object Overwrite : EditorIntent

    /** Drops the edits and loads the server's version after [EditorState.conflict]. */
    data object Reload : EditorIntent

    /** Keeps editing after [EditorState.conflict]; the next save checks again. */
    data object DismissConflict : EditorIntent

    /** Back or the close button: closes, or asks first when [EditorState.dirty]. */
    data object Back : EditorIntent

    /** Leaves, dropping the unsaved changes. */
    data object ConfirmLeave : EditorIntent

    data object CancelLeave : EditorIntent

    /** Loads again after [EditorState.loadError]. */
    data object Retry : EditorIntent

    data object DismissError : EditorIntent
}

sealed interface EditorEvent {
    /** The graph goes back; a viewer below reloads the file then. */
    data object Close : EditorEvent
}

interface EditorPresenter : Presenter<EditorState, EditorIntent, EditorEvent>
