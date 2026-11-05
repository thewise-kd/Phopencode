package io.github.thewisekd.phopencode.feature.files.logic.ops

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileAction
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOp
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpError
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsDialog
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsPresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsState
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Delete, rename and new folder over the tree (P8.10); the flow is on [FileOpsState].
 *
 * - A menu offers every [FileAction] for a [FileEntryType.File] and only [FileAction.Rename] and
 *   [FileAction.Delete] for anything else: a folder, a symlink (whose target may be a folder) or an
 *   unknown type. A [FileOpsIntent.Choose] the menu does not offer is ignored.
 * - Names are checked with [FileNames] on every keystroke and again on [FileOpsIntent.Submit];
 *   a name with a problem is never sent. The name is sent trimmed. Renaming to the entry's own name
 *   sends nothing and closes the dialog.
 * - Every delete asks first ([FileOpsDialog.ConfirmDelete]); only [FileOpsIntent.ConfirmDelete] on
 *   that dialog sends it. Only a [FileEntryType.Directory] is deleted with its content; a symlink
 *   or a file is deleted alone.
 * - One operation runs at a time ([FileOpsState.busy]). Its dialog takes no input meanwhile; a
 *   newer dialog does, but cannot submit until the operation ends.
 *   [FileOpsIntent.Cancel] and [FileOpsIntent.Open] close or replace the dialog but do not stop the
 *   operation; when it ends it closes its own dialog only if that dialog is still open, and a newer
 *   one is left as it is.
 * - Success closes the dialog and sends [FileOpsEvent.Changed] for the folder the tree showed.
 *   [FilesFailure.AlreadyExists] on a rename or new folder shows as [NameProblem.Taken] on the
 *   dialog; any other failure keeps the dialog open with [FileOpsState.error]. A failed delete also
 *   sends [FileOpsEvent.Changed], since a recursive delete may have removed part of the content.
 * - Intents, results and events are handled under one lock; each state is published whole, and an
 *   event is sent in the same locked step that decides it.
 *
 * Nothing runs in the constructor: an operation starts in [scope] after a `yield()`. Cancellation
 * is never wrapped. When [scope] itself is cancelled, nothing more is published or sent,
 * even when a port ignores the cancellation and answers anyway. When only
 * the port's call throws a `CancellationException` (a connection call cancelled under it) while
 * [scope] lives on, the operation ends as [FilesFailure.Unknown], like any unexpected failure: the
 * cause is not known to be the connection, so [FilesFailure.NotConnected] would send the user to
 * the connection for nothing. The cancellation is then rethrown as it came. Nothing here logs; the state prints its paths and names masked.
 *
 * @param directory The project's folder, from the tree's route.
 * @param files Deletes, renames and creates folders.
 * @param scope The destination's scope; an operation started here stops with it.
 */
class DefaultFileOpsPresenter(
    private val directory: String,
    private val files: FileChanges,
    private val scope: CoroutineScope,
) : FileOpsPresenter {
    private val lock = Any()

    private var ui = FileOpsState()

    /** The folder the tree showed when the open menu or dialog was asked for. */
    private var folder: String? = null

    /** The dialog the running operation started from; null when none runs. */
    private var running: FileOpsDialog? = null

    /** Raised by every operation started; only the latest one's flags and result land. */
    private var operation = 0L

    private val mutableState = MutableStateFlow(ui)

    override val state: StateFlow<FileOpsState> = mutableState.asStateFlow()

    /** Unlimited, so [send] never drops an event; it fails only once closed, which nothing does. */
    private val channel = Channel<FileOpsEvent>(Channel.UNLIMITED)

    override val events: Flow<FileOpsEvent> = channel.receiveAsFlow()

    override fun onIntent(intent: FileOpsIntent) {
        synchronized(lock) {
            when (intent) {
                is FileOpsIntent.Open -> open(intent.request)
                is FileOpsIntent.Choose -> choose(intent.action)
                is FileOpsIntent.NameChanged -> rename(intent.name)
                FileOpsIntent.Submit -> submit()
                FileOpsIntent.ConfirmDelete -> confirmDelete()
                FileOpsIntent.Cancel -> publish(ui.copy(menu = null, menuActions = emptyList(), dialog = null))
                FileOpsIntent.DismissError -> publish(ui.copy(error = null))
            }
        }
    }

    // Menu and dialogs; every function from here to the state section holds [lock].

    private fun open(request: FileOpsRequest) {
        folder = request.folder
        when (request) {
            is FileOpsRequest.Actions ->
                publish(ui.copy(menu = request, menuActions = actionsFor(request.entry), dialog = null))
            is FileOpsRequest.NewFolder ->
                publish(ui.copy(menu = null, menuActions = emptyList(), dialog = FileOpsDialog.NewFolder(request.folder)))
        }
    }

    private fun actionsFor(entry: FileEntry): List<FileAction> =
        if (entry.type == FileEntryType.File) FILE_ACTIONS else OTHER_ACTIONS

    private fun choose(action: FileAction) {
        val menu = ui.menu ?: return
        if (action !in ui.menuActions) return
        val entry = menu.entry
        when (action) {
            FileAction.Edit -> {
                closeMenu(dialog = null)
                send(FileOpsEvent.OpenEditor(entry.path))
            }
            FileAction.Download -> {
                closeMenu(dialog = null)
                send(FileOpsEvent.Download(entry.path, menu.folder))
            }
            FileAction.Rename -> closeMenu(dialog = FileOpsDialog.Rename(entry, entry.name))
            FileAction.Delete -> closeMenu(dialog = FileOpsDialog.ConfirmDelete(entry))
        }
    }

    private fun closeMenu(dialog: FileOpsDialog?) {
        publish(ui.copy(menu = null, menuActions = emptyList(), dialog = dialog))
    }

    /**
     * The name field; ignored while this dialog's own operation runs. A newer dialog opened while
     * another operation runs takes input, but cannot submit until it ends.
     */
    private fun rename(name: String) {
        if (running != null && ui.dialog === running) return
        val next = when (val open = ui.dialog) {
            is FileOpsDialog.Rename -> open.copy(name = name, problem = FileNames.problem(name))
            is FileOpsDialog.NewFolder -> open.copy(name = name, problem = FileNames.problem(name))
            is FileOpsDialog.ConfirmDelete, null -> return
        }
        publish(ui.copy(dialog = next))
    }

    private fun submit() {
        if (!ui.canSubmit) return
        val target = folder ?: return
        when (val open = ui.dialog) {
            is FileOpsDialog.Rename -> {
                // Checked again here: nothing with a problem is ever sent.
                if (FileNames.problem(open.name) != null) return
                val name = open.name.trim()
                if (name == open.entry.name) {
                    closeMenu(dialog = null)
                    return
                }
                val to = sibling(open.entry.path, name)
                run(FileOp.Rename, open, target) { files.move(directory, open.entry.path, to) }
            }
            is FileOpsDialog.NewFolder -> {
                if (FileNames.problem(open.name) != null) return
                val path = child(open.folder, open.name.trim())
                run(FileOp.CreateFolder, open, target) { files.createFolder(directory, path) }
            }
            is FileOpsDialog.ConfirmDelete, null -> Unit
        }
    }

    private fun confirmDelete() {
        val open = ui.dialog as? FileOpsDialog.ConfirmDelete ?: return
        if (!ui.canSubmit) return
        val target = folder ?: return
        val entry = open.entry
        // Only a real folder takes its content with it, and only after this confirmation.
        val recursive = entry.type == FileEntryType.Directory
        run(FileOp.Delete, open, target) { files.delete(directory, entry.path, recursive) }
    }

    // Operations

    /** Starts [op] from [dialog]; [changed] is the folder the tree refreshes afterwards. */
    private fun run(op: FileOp, dialog: FileOpsDialog, changed: String, call: suspend () -> Unit) {
        val mine = ++operation
        running = dialog
        publish(ui.copy(busy = op, error = null))
        scope.launch {
            try {
                yield()
                val failure = try {
                    call()
                    null
                } catch (e: CancellationException) {
                    // If the presenter's own scope is going away, the guard in the lock lands
                    // nothing. If only the port's call was cancelled, the presenter lives on: the
                    // operation ends as a failure the user can retry. Either way the cancellation
                    // goes on unwrapped.
                    synchronized(lock) {
                        if (isActive) finish(mine, op, dialog, changed, FilesFailure.Unknown)
                    }
                    throw e
                } catch (e: FilesException) {
                    e.failure
                } catch (_: Exception) {
                    FilesFailure.Unknown
                }
                // A port that ignores cancellation may answer after the scope was cancelled; then
                // nothing lands. Checked in the lock, beside the operation number in [finish].
                synchronized(lock) { if (isActive) finish(mine, op, dialog, changed, failure) }
            } finally {
                // However it ended, a later operation is never blocked by this one's input lock. The
                // state is not published here: after the scope's cancellation nobody draws it.
                synchronized(lock) { if (operation == mine) running = null }
            }
        }
    }

    /**
     * Holds [lock], called only while the operation's coroutine is still active. Lands operation
     * [mine]'s result; does nothing when it already landed or a newer one started.
     */
    private fun finish(mine: Long, op: FileOp, dialog: FileOpsDialog, changed: String, failure: FilesFailure?) {
        if (operation != mine || running == null) return
        running = null
        val stillOpen = ui.dialog === dialog
        when {
            failure == null -> {
                publish(ui.copy(busy = null, dialog = if (stillOpen) null else ui.dialog))
                send(FileOpsEvent.Changed(changed))
            }
            failure == FilesFailure.AlreadyExists && op != FileOp.Delete -> {
                val taken = when {
                    !stillOpen -> ui.dialog
                    dialog is FileOpsDialog.Rename -> dialog.copy(problem = NameProblem.Taken)
                    dialog is FileOpsDialog.NewFolder -> dialog.copy(problem = NameProblem.Taken)
                    else -> dialog
                }
                // Shown on the dialog when it is still open, as an error otherwise.
                val error = if (stillOpen) ui.error else FileOpError(op, failure)
                publish(ui.copy(busy = null, dialog = taken, error = error))
            }
            else -> {
                publish(ui.copy(busy = null, error = FileOpError(op, failure)))
                if (op == FileOp.Delete) send(FileOpsEvent.Changed(changed))
            }
        }
    }

    // State

    /** Holds [lock]. Never suspends: the channel is unlimited. */
    private fun send(event: FileOpsEvent) {
        channel.trySend(event)
    }

    /** Holds [lock]. */
    private fun publish(next: FileOpsState) {
        ui = next
        mutableState.value = next
    }

    private companion object {
        val FILE_ACTIONS = listOf(FileAction.Edit, FileAction.Download, FileAction.Rename, FileAction.Delete)
        val OTHER_ACTIONS = listOf(FileAction.Rename, FileAction.Delete)

        /** [name] next to [path], in the same folder, written as [path] is: absolute or relative. */
        fun sibling(path: String, name: String): String {
            val trimmed = path.trimEnd('/')
            val cut = trimmed.lastIndexOf('/')
            return when {
                cut < 0 -> name
                cut == 0 -> "/$name"
                else -> trimmed.substring(0, cut) + "/" + name
            }
        }

        /** [name] inside [folder]; [folder] empty is the project's folder itself. */
        fun child(folder: String, name: String): String = when {
            folder.isEmpty() -> name
            folder.endsWith("/") -> folder + name
            else -> "$folder/$name"
        }
    }
}
