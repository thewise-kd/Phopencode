package io.github.thewisekd.phopencode.feature.files.logic.tree

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileListing
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreePresenter
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * The folder tree of one project folder (P8.4); the rules are on [TreeState].
 *
 * - A listing is [FileListing.list] of [TreeState.folder], with [GitChanges.changes] of the
 *   project's folder asked beside it. Only the latest listing lands: moving to another folder or
 *   refreshing drops the one in flight. A listing that fails shows [TreeState.error] without rows;
 *   a git status that fails only leaves the badges out.
 * - The rows are kept as listed, so [TreeIntent.SortBy] and [TreeIntent.SetShowHidden] rearrange
 *   them without asking the server again.
 * - A folder opens in place. A symlink is tried as a folder (a probe): if it lists, it opens in
 *   place; otherwise it opens as a file. Until then the tree stays as it is. Every intent but
 *   [TreeIntent.SortBy] and [TreeIntent.SetShowHidden] is the user moving on and drops a probe in
 *   flight, as does any listing started.
 * - Each published state is whole: a folder always comes with its own rows (or none while it
 *   loads), never with another folder's.
 * - Intents, results and events are handled under one lock, and an event is sent in the same
 *   locked step that decides it, so nothing can come between a check and its event.
 * - [TreeIntent.OpenFolder] takes a path inside the project's folder, absolute or relative; any
 *   other path is ignored. At the root, [TreeState.folder] is exactly [directory], so
 *   [TreeState.isAtRoot] holds however the path was written.
 * - [TreeIntent.Refresh] keeps the rows shown ([TreeState.refreshing]); after an error it loads
 *   again, as [TreeIntent.Retry] does.
 *
 * Known limit: the paths of [GitChanges.changes] are taken as relative to the project's folder
 * (or absolute inside it). For a project inside a larger repository, whose status paths are
 * relative to the repository's root, they match no row and the tree may show no badges.
 *
 * Nothing runs in the constructor: work starts in [scope] after a `yield()`. Nothing here logs; the
 * state prints its paths masked.
 *
 * @param directory The project's folder, from the route; the tree starts there.
 * @param files Lists folders.
 * @param git The git badges.
 * @param scope The destination's scope; work started here stops with it.
 */
class DefaultTreePresenter(
    private val directory: String,
    private val files: FileListing,
    private val git: GitChanges,
    private val scope: CoroutineScope,
) : TreePresenter {
    private val paths = TreePaths(directory)

    private val lock = Any()

    private var ui = TreeState(directory = directory)

    /** Every row of the folder shown, badged, hidden ones included, as listed. */
    private var rows: List<TreeEntry> = emptyList()

    /** Raised by every listing started; a result lands only while it is still the latest. */
    private var generation = 0L

    private var listing: Job? = null

    private var probe: Job? = null

    /** Raised by every probe and every listing; a probe lands only while it is still the latest. */
    private var probeToken = 0L

    private val mutableState = MutableStateFlow(ui)

    override val state: StateFlow<TreeState> = mutableState.asStateFlow()

    /** Unlimited, so [send] never drops an event; it fails only once closed, which nothing does. */
    private val channel = Channel<TreeEvent>(Channel.UNLIMITED)

    override val events: Flow<TreeEvent> = channel.receiveAsFlow()

    init {
        synchronized(lock) { list(directory) }
    }

    override fun onIntent(intent: TreeIntent) {
        synchronized(lock) {
            // Anything but the view options means the user moved on: a probe in flight is dropped.
            if (intent !is TreeIntent.SetShowHidden && intent !is TreeIntent.SortBy) dropProbe()
            when (intent) {
                is TreeIntent.Open -> open(intent.entry)
                is TreeIntent.OpenFolder -> paths.resolve(intent.path)?.let(::navigate)
                TreeIntent.Up -> paths.parent(ui.folder)?.let(::navigate)
                TreeIntent.Refresh -> reload(onlyAfterError = false)
                TreeIntent.Retry -> reload(onlyAfterError = true)
                is TreeIntent.SetShowHidden -> change(ui.copy(showHidden = intent.show))
                is TreeIntent.SortBy -> change(ui.copy(sort = intent.sort))
                is TreeIntent.ShowActions -> send(TreeEvent.OpenActions(FileOpsRequest.Actions(intent.entry.entry, ui.folder)))
                TreeIntent.NewFolder -> send(TreeEvent.OpenActions(FileOpsRequest.NewFolder(ui.folder)))
                TreeIntent.OpenSearch -> send(TreeEvent.OpenSearch)
                TreeIntent.OpenTransfer -> send(TreeEvent.OpenTransfer(ui.folder))
                TreeIntent.Close -> send(TreeEvent.Close)
            }
        }
    }

    // Moving; every function from here to the state section holds [lock].

    private fun open(row: TreeEntry) {
        when {
            row.isFolder -> paths.resolve(row.entry.path)?.let(::navigate)
            row.entry.type == FileEntryType.Symlink -> probe(row.entry)
            else -> send(TreeEvent.OpenFile(row.entry.path))
        }
    }

    /** Shows [target], a normalized path inside the project's folder, loading from empty. */
    private fun navigate(target: String) {
        val folder = shownAs(target)
        rows = emptyList()
        publish(ui.copy(folder = folder, loading = true, refreshing = false, entries = emptyList(), error = null))
        list(folder)
    }

    /** [TreeState.folder] for [target]: exactly [directory] at the root. */
    private fun shownAs(target: String): String = if (target == paths.root) directory else target

    private fun reload(onlyAfterError: Boolean) {
        val current = ui
        when {
            current.error != null -> publish(current.copy(loading = true, refreshing = false, error = null))
            onlyAfterError -> return
            current.loading -> Unit
            else -> publish(current.copy(refreshing = true))
        }
        list(current.folder)
    }

    // Listing

    /** Starts listing [folder], dropping any listing or probe in flight. */
    private fun list(folder: String) {
        val mine = ++generation
        dropProbe()
        listing?.cancel()
        listing = scope.launch {
            yield()
            val result = fetch(folder)
            synchronized(lock) {
                if (generation != mine || !isActive) return@launch
                land(folder, result)
            }
        }
    }

    /** Invalidates the probe in flight, if any: it neither opens a folder nor sends an event. */
    private fun dropProbe() {
        ++probeToken
        probe?.cancel()
        probe = null
    }

    /**
     * Tries the symlink [link] as a folder. The tree stays as it is meanwhile; any later intent
     * but the view options, or any listing, drops it.
     */
    private fun probe(link: FileEntry) {
        dropProbe()
        val mine = probeToken
        val listed = generation
        probe = scope.launch {
            yield()
            val result = fetch(link.path)
            synchronized(lock) {
                if (probeToken != mine || generation != listed || !isActive) return@launch
                probe = null
                when (result) {
                    is Fetched.Listed -> {
                        ++generation
                        listing?.cancel()
                        land(paths.resolve(link.path)?.let(::shownAs) ?: link.path, result)
                    }
                    // Decided and sent in this one locked step.
                    is Fetched.Failed -> send(TreeEvent.OpenFile(link.path))
                }
            }
        }
    }

    /** Publishes [folder] with the result of listing it, in one state. */
    private fun land(folder: String, result: Fetched) {
        when (result) {
            is Fetched.Listed -> {
                rows = badged(result.entries, GitBadges(result.changes, paths))
                publish(
                    ui.copy(
                        folder = folder,
                        loading = false,
                        refreshing = false,
                        entries = shown(rows, ui.sort, ui.showHidden),
                        error = null,
                    ),
                )
            }
            is Fetched.Failed -> {
                rows = emptyList()
                publish(
                    ui.copy(folder = folder, loading = false, refreshing = false, entries = emptyList(), error = result.failure),
                )
            }
        }
    }

    private sealed interface Fetched {
        class Listed(val entries: List<FileEntry>, val changes: List<VcsFile>) : Fetched {
            override fun toString(): String = "Listed(entries=${entries.size}, changes=${changes.size})"
        }

        data class Failed(val failure: FilesFailure) : Fetched
    }

    /** Lists [folder] with the git changes asked beside it. */
    private suspend fun fetch(folder: String): Fetched = try {
        coroutineScope {
            val changes = async { changes() }
            val entries = files.list(directory, folder)
            Fetched.Listed(entries, changes.await())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: FilesException) {
        Fetched.Failed(e.failure)
    } catch (_: Exception) {
        Fetched.Failed(FilesFailure.Unknown)
    }

    /** The repository's changes; none when they cannot be had. */
    private suspend fun changes(): List<VcsFile> = try {
        git.changes(directory)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    // State

    /** Holds [lock]. Never suspends: the channel is unlimited. */
    private fun send(event: TreeEvent) {
        channel.trySend(event)
    }

    /** Holds [lock]. Publishes [next]'s view options with the rows rearranged for them. */
    private fun change(next: TreeState) {
        publish(next.copy(entries = shown(rows, next.sort, next.showHidden)))
    }

    /** Holds [lock]. */
    private fun publish(next: TreeState) {
        ui = next
        mutableState.value = next
    }
}
