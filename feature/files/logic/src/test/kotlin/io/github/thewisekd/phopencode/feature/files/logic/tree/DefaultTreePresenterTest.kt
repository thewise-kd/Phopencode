package io.github.thewisekd.phopencode.feature.files.logic.tree

import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.VcsFile
import io.github.thewisekd.phopencode.core.model.VcsFileStatus
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileListing
import io.github.thewisekd.phopencode.feature.files.logic.contract.FileOpsRequest
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesException
import io.github.thewisekd.phopencode.feature.files.logic.contract.FilesFailure
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitBadge
import io.github.thewisekd.phopencode.feature.files.logic.contract.GitChanges
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEntry
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeEvent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeIntent
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeSort
import io.github.thewisekd.phopencode.feature.files.logic.contract.TreeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val ROOT = "/srv/çalışma alanı"

private fun file(path: String, size: Long? = null, modified: Long? = null, ignored: Boolean = false) = FileEntry(
    path = path,
    type = FileEntryType.File,
    sizeBytes = size,
    modifiedAt = modified?.let { Instant.fromEpochSeconds(it) },
    ignored = ignored,
)

private fun folder(path: String, size: Long? = null, modified: Long? = null, ignored: Boolean = false) = FileEntry(
    path = path,
    type = FileEntryType.Directory,
    sizeBytes = size,
    modifiedAt = modified?.let { Instant.fromEpochSeconds(it) },
    ignored = ignored,
)

private fun link(path: String) = FileEntry(path = path, type = FileEntryType.Symlink)

/**
 * Folders by absolute path. A folder may be held at a gate to keep its listing in flight, or fail.
 * Every call is recorded.
 */
private class FakeListing : FileListing {
    val folders = mutableMapOf<String, List<FileEntry>>()
    val failures = mutableMapOf<String, Throwable>()
    val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
    val calls = mutableListOf<Pair<String, String>>()

    override suspend fun list(directory: String, folder: String): List<FileEntry> {
        calls += directory to folder
        gates[folder]?.await()
        failures[folder]?.let { throw it }
        return folders[folder] ?: throw FilesException(FilesFailure.NotFound)
    }
}

private class FakeGit : GitChanges {
    var changes: List<VcsFile> = emptyList()
    var failure: Throwable? = null
    val calls = mutableListOf<String>()

    override suspend fun changes(directory: String): List<VcsFile> {
        calls += directory
        failure?.let { throw it }
        return changes
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultTreePresenterTest {
    private val files = FakeListing()
    private val git = FakeGit()

    private fun TestScope.presenter(directory: String = ROOT): DefaultTreePresenter =
        DefaultTreePresenter(directory = directory, files = files, git = git, scope = backgroundScope)

    private fun TestScope.events(presenter: DefaultTreePresenter): List<TreeEvent> {
        val seen = mutableListOf<TreeEvent>()
        backgroundScope.launch { presenter.events.toList(seen) }
        return seen
    }

    private fun TreeState.names(): List<String> = entries.map { it.entry.name }

    private fun TreeState.badge(name: String): GitBadge? = entries.single { it.entry.name == name }.badge

    // Loading and sorting

    @Test
    fun `nothing is listed before the scope runs`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val presenter = presenter()

        assertEquals(TreeState(directory = ROOT), presenter.state.value)
        assertTrue(files.calls.isEmpty())
        assertTrue(git.calls.isEmpty())
    }

    @Test
    fun `the root is listed with folders first, by name, and dot names hidden`() = runTest {
        files.folders[ROOT] = listOf(
            file("$ROOT/b.txt"),
            folder("$ROOT/src"),
            file("$ROOT/.env"),
            file("$ROOT/A.md"),
            folder("$ROOT/.git"),
            folder("$ROOT/Docs"),
            file("$ROOT/çizim.svg"),
        )
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("Docs", "src", "A.md", "b.txt", "çizim.svg"), state.names())
        assertEquals(listOf(ROOT to ROOT), files.calls)
        assertEquals(listOf(ROOT), git.calls)
        assertTrue(state.isAtRoot)
    }

    @Test
    fun `hidden names show on demand without listing again`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"), file("$ROOT/.env"), folder("$ROOT/.git"))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.SetShowHidden(true))
        assertTrue(presenter.state.value.showHidden)
        assertEquals(listOf(".git", ".env", "a.txt"), presenter.state.value.names())

        presenter.onIntent(TreeIntent.SetShowHidden(false))
        assertEquals(listOf("a.txt"), presenter.state.value.names())
        runCurrent()
        assertEquals(1, files.calls.size)
    }

    @Test
    fun `sorting by modified puts the newest first, folders still first, unknown times last`() = runTest {
        files.folders[ROOT] = listOf(
            file("$ROOT/old.txt", modified = 100),
            file("$ROOT/new.txt", modified = 300),
            file("$ROOT/when.txt"),
            folder("$ROOT/b", modified = 50),
            folder("$ROOT/a", modified = 400),
        )
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.SortBy(TreeSort.Modified))

        assertEquals(TreeSort.Modified, presenter.state.value.sort)
        assertEquals(listOf("a", "b", "new.txt", "old.txt", "when.txt"), presenter.state.value.names())
        assertEquals(1, files.calls.size)
    }

    @Test
    fun `sorting by size puts the largest first, ties by name`() = runTest {
        files.folders[ROOT] = listOf(
            file("$ROOT/small.txt", size = 10),
            file("$ROOT/big.bin", size = 5000),
            file("$ROOT/b.txt", size = 10),
            file("$ROOT/unknown"),
            folder("$ROOT/src", size = 4096),
        )
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.SortBy(TreeSort.Size))

        assertEquals(listOf("src", "big.bin", "b.txt", "small.txt", "unknown"), presenter.state.value.names())

        presenter.onIntent(TreeIntent.SortBy(TreeSort.Name))
        assertEquals(listOf("src", "b.txt", "big.bin", "small.txt", "unknown"), presenter.state.value.names())
    }

    @Test
    fun `an empty folder is empty, not an error`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/.hidden"))
        val presenter = presenter()
        runCurrent()

        assertTrue(presenter.state.value.isEmpty)
    }

    // The .gitignore marker and git badges

    @Test
    fun `ignored entries stay listed with their marker`() = runTest {
        files.folders[ROOT] = listOf(folder("$ROOT/build", ignored = true), file("$ROOT/a.txt"))
        val presenter = presenter()
        runCurrent()

        val entries = presenter.state.value.entries
        assertEquals(listOf("build", "a.txt"), presenter.state.value.names())
        assertTrue(entries[0].entry.ignored)
        assertFalse(entries[1].entry.ignored)
    }

    @Test
    fun `files carry their git status and folders holding changes are marked`() = runTest {
        files.folders[ROOT] = listOf(
            file("$ROOT/added.kt"),
            file("$ROOT/changed.kt"),
            file("$ROOT/moved.kt"),
            file("$ROOT/new notes.md"),
            file("$ROOT/clean.kt"),
            file("$ROOT/odd.kt"),
            folder("$ROOT/src"),
            folder("$ROOT/docs"),
            folder("$ROOT/fresh"),
            folder("$ROOT/s"),
        )
        git.changes = listOf(
            VcsFile("added.kt", VcsFileStatus.Added),
            VcsFile("changed.kt", VcsFileStatus.Modified),
            VcsFile("moved.kt", VcsFileStatus.Renamed),
            VcsFile("new notes.md", VcsFileStatus.Untracked),
            VcsFile("odd.kt", VcsFileStatus.Unknown("X")),
            VcsFile("src/main/deep/Gone.kt", VcsFileStatus.Deleted),
            VcsFile("fresh/", VcsFileStatus.Untracked),
            VcsFile("srcx.kt", VcsFileStatus.Modified),
        )
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(GitBadge.Added, state.badge("added.kt"))
        assertEquals(GitBadge.Modified, state.badge("changed.kt"))
        assertEquals(GitBadge.Renamed, state.badge("moved.kt"))
        assertEquals(GitBadge.Untracked, state.badge("new notes.md"))
        assertNull(state.badge("clean.kt"))
        assertNull(state.badge("odd.kt"))
        assertEquals(GitBadge.ContainsChanges, state.badge("src"))
        assertNull(state.badge("docs"))
        assertEquals(GitBadge.Untracked, state.badge("fresh"))
        assertNull(state.badge("s"))
    }

    @Test
    fun `badges in a subfolder match paths relative to the project, and absolute ones`() = runTest {
        files.folders[ROOT] = listOf(folder("$ROOT/src"))
        files.folders["$ROOT/src"] = listOf(file("$ROOT/src/a.kt"), file("$ROOT/src/b.kt"), folder("$ROOT/src/main"))
        git.changes = listOf(
            VcsFile("src/a.kt", VcsFileStatus.Modified),
            VcsFile("$ROOT/src/b.kt", VcsFileStatus.Deleted),
            VcsFile("./src/main/x.kt", VcsFileStatus.Added),
            VcsFile("/elsewhere/src/a.kt", VcsFileStatus.Added),
        )
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single()))
        runCurrent()

        val state = presenter.state.value
        assertEquals("$ROOT/src", state.folder)
        assertEquals(GitBadge.Modified, state.badge("a.kt"))
        assertEquals(GitBadge.Deleted, state.badge("b.kt"))
        assertEquals(GitBadge.ContainsChanges, state.badge("main"))
    }

    @Test
    fun `a failed git status leaves the badges out and is not an error`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.kt"))
        git.changes = listOf(VcsFile("a.kt", VcsFileStatus.Modified))
        git.failure = FilesException(FilesFailure.Unreachable)
        val presenter = presenter()
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf(TreeEntry(file("$ROOT/a.kt"))), presenter.state.value.entries)

        git.failure = IllegalStateException("broken")
        presenter.onIntent(TreeIntent.Refresh)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertNull(presenter.state.value.badge("a.kt"))

        git.failure = null
        presenter.onIntent(TreeIntent.Refresh)
        runCurrent()

        assertEquals(GitBadge.Modified, presenter.state.value.badge("a.kt"))
    }

    // Moving between folders

    @Test
    fun `a folder opens in place and up goes back to the root`() = runTest {
        files.folders[ROOT] = listOf(folder("$ROOT/src"))
        files.folders["$ROOT/src"] = listOf(file("$ROOT/src/main.kt"))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single()))

        val opening = presenter.state.value
        assertEquals("$ROOT/src", opening.folder)
        assertTrue(opening.loading)
        assertTrue(opening.entries.isEmpty())
        assertFalse(opening.isAtRoot)

        runCurrent()
        assertEquals(listOf("main.kt"), presenter.state.value.names())
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(TreeIntent.Up)
        assertTrue(presenter.state.value.isAtRoot)
        assertEquals(ROOT, presenter.state.value.folder)
        runCurrent()
        assertEquals(listOf("src"), presenter.state.value.names())
        assertEquals(listOf(ROOT, "$ROOT/src", ROOT), files.calls.map { it.second })
    }

    @Test
    fun `up at the root does nothing`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val presenter = presenter()
        runCurrent()
        val before = presenter.state.value

        presenter.onIntent(TreeIntent.Up)
        runCurrent()

        assertEquals(before, presenter.state.value)
        assertEquals(1, files.calls.size)
    }

    @Test
    fun `a project folder given with a trailing slash is still its own root`() = runTest {
        files.folders["$ROOT/"] = listOf(folder("$ROOT/src"))
        files.folders["$ROOT/src"] = emptyList()
        val presenter = presenter(directory = "$ROOT/")
        runCurrent()

        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single()))
        runCurrent()
        presenter.onIntent(TreeIntent.Up)

        assertTrue(presenter.state.value.isAtRoot)
        assertEquals("$ROOT/", presenter.state.value.folder)
    }

    @Test
    fun `open folder takes absolute and relative paths inside the project and refuses others`() = runTest {
        files.folders[ROOT] = emptyList()
        files.folders["$ROOT/src/main"] = listOf(file("$ROOT/src/main/a.kt"))
        files.folders["$ROOT/docs"] = emptyList()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.OpenFolder("src/main/"))
        runCurrent()
        assertEquals("$ROOT/src/main", presenter.state.value.folder)
        assertEquals(listOf("a.kt"), presenter.state.value.names())

        presenter.onIntent(TreeIntent.OpenFolder("$ROOT/src/../docs"))
        runCurrent()
        assertEquals("$ROOT/docs", presenter.state.value.folder)

        presenter.onIntent(TreeIntent.OpenFolder(ROOT))
        runCurrent()
        assertTrue(presenter.state.value.isAtRoot)

        val callsBefore = files.calls.size
        val before = presenter.state.value
        presenter.onIntent(TreeIntent.OpenFolder("../other"))
        presenter.onIntent(TreeIntent.OpenFolder("/etc"))
        presenter.onIntent(TreeIntent.OpenFolder("${ROOT}x"))
        presenter.onIntent(TreeIntent.OpenFolder(""))
        runCurrent()
        assertEquals(before, presenter.state.value)
        assertEquals(callsBefore, files.calls.size)
    }

    @Test
    fun `only the latest folder's listing lands`() = runTest {
        files.folders[ROOT] = listOf(folder("$ROOT/slow"), folder("$ROOT/fast"))
        files.folders["$ROOT/slow"] = listOf(file("$ROOT/slow/s.txt"))
        files.folders["$ROOT/fast"] = listOf(file("$ROOT/fast/f.txt"))
        val gate = CompletableDeferred<Unit>()
        files.gates["$ROOT/slow"] = gate
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.OpenFolder("slow"))
        runCurrent()
        presenter.onIntent(TreeIntent.OpenFolder("fast"))
        gate.complete(Unit)
        runCurrent()

        assertEquals("$ROOT/fast", presenter.state.value.folder)
        assertEquals(listOf("f.txt"), presenter.state.value.names())
    }

    @Test
    fun `a file sends open file with its path`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/main kodu.kt"))
        val presenter = presenter()
        val events = events(presenter)
        runCurrent()

        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single()))
        runCurrent()

        assertEquals(listOf<TreeEvent>(TreeEvent.OpenFile("$ROOT/main kodu.kt")), events)
        assertEquals(ROOT, presenter.state.value.folder)
    }

    @Test
    fun `a symlink to a folder opens in place, any other symlink opens as a file`() = runTest {
        files.folders[ROOT] = listOf(link("$ROOT/linked"), link("$ROOT/note"))
        files.folders["$ROOT/linked"] = listOf(file("$ROOT/linked/inside.txt"))
        files.failures["$ROOT/note"] = FilesException(FilesFailure.ServerFailed)
        val presenter = presenter()
        val events = events(presenter)
        runCurrent()

        val note = presenter.state.value.entries.single { it.entry.name == "note" }
        presenter.onIntent(TreeIntent.Open(note))
        runCurrent()
        assertEquals(listOf<TreeEvent>(TreeEvent.OpenFile("$ROOT/note")), events)
        assertEquals(ROOT, presenter.state.value.folder)
        assertNull(presenter.state.value.error)

        val linked = presenter.state.value.entries.single { it.entry.name == "linked" }
        presenter.onIntent(TreeIntent.Open(linked))
        runCurrent()
        assertEquals("$ROOT/linked", presenter.state.value.folder)
        assertEquals(listOf("inside.txt"), presenter.state.value.names())
        assertEquals(1, events.size)
    }

    @Test
    fun `a symlink probe overtaken by another move is dropped`() = runTest {
        files.folders[ROOT] = listOf(link("$ROOT/note"), folder("$ROOT/src"))
        files.folders["$ROOT/src"] = emptyList()
        files.failures["$ROOT/note"] = FilesException(FilesFailure.ServerFailed)
        val gate = CompletableDeferred<Unit>()
        files.gates["$ROOT/note"] = gate
        val presenter = presenter()
        val events = events(presenter)
        runCurrent()

        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single { it.entry.name == "note" }))
        runCurrent()
        presenter.onIntent(TreeIntent.OpenFolder("src"))
        gate.complete(Unit)
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals("$ROOT/src", presenter.state.value.folder)
    }

    // Refresh, errors and retry

    @Test
    fun `refresh keeps the rows until the new ones arrive`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val presenter = presenter()
        runCurrent()

        files.folders[ROOT] = listOf(file("$ROOT/a.txt"), file("$ROOT/b.txt"))
        git.changes = listOf(VcsFile("b.txt", VcsFileStatus.Untracked))
        val gate = CompletableDeferred<Unit>()
        files.gates[ROOT] = gate
        presenter.onIntent(TreeIntent.Refresh)
        runCurrent()

        val refreshing = presenter.state.value
        assertTrue(refreshing.refreshing)
        assertFalse(refreshing.loading)
        assertEquals(listOf("a.txt"), refreshing.names())

        gate.complete(Unit)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.refreshing)
        assertEquals(listOf("a.txt", "b.txt"), state.names())
        assertEquals(GitBadge.Untracked, state.badge("b.txt"))
        assertEquals(2, git.calls.size)
    }

    @Test
    fun `a folder that cannot be listed shows the failure and retry lists it again`() = runTest {
        files.failures[ROOT] = FilesException(FilesFailure.PermissionDenied)
        val presenter = presenter()
        runCurrent()

        assertEquals(FilesFailure.PermissionDenied, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
        assertFalse(presenter.state.value.isEmpty)

        files.failures.clear()
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        presenter.onIntent(TreeIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf("a.txt"), presenter.state.value.names())
    }

    @Test
    fun `a failed refresh shows the failure instead of stale rows`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val presenter = presenter()
        runCurrent()

        files.failures[ROOT] = FilesException(FilesFailure.NotFound)
        presenter.onIntent(TreeIntent.Refresh)
        runCurrent()

        val state = presenter.state.value
        assertEquals(FilesFailure.NotFound, state.error)
        assertFalse(state.refreshing)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `an unexpected listing error is an unknown failure`() = runTest {
        files.failures[ROOT] = IllegalStateException("boom")
        val presenter = presenter()
        runCurrent()

        assertEquals(FilesFailure.Unknown, presenter.state.value.error)
    }

    @Test
    fun `retry does nothing without an error`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.Retry)
        runCurrent()

        assertEquals(1, files.calls.size)
    }

    @Test
    fun `refresh during the first load lists once more and still ends loaded`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val gate = CompletableDeferred<Unit>()
        files.gates[ROOT] = gate
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.Refresh)
        assertTrue(presenter.state.value.loading)
        assertFalse(presenter.state.value.refreshing)
        gate.complete(Unit)
        runCurrent()

        assertFalse(presenter.state.value.loading)
        assertEquals(listOf("a.txt"), presenter.state.value.names())
    }

    // Events for the rest of the destination

    @Test
    fun `actions, new folder, search, transfer and close go out as events`() = runTest {
        files.folders[ROOT] = listOf(folder("$ROOT/src"))
        files.folders["$ROOT/src"] = listOf(file("$ROOT/src/a.kt"))
        val presenter = presenter()
        val events = events(presenter)
        runCurrent()
        presenter.onIntent(TreeIntent.OpenFolder("src"))
        runCurrent()

        val row = presenter.state.value.entries.single()
        presenter.onIntent(TreeIntent.ShowActions(row))
        presenter.onIntent(TreeIntent.NewFolder)
        presenter.onIntent(TreeIntent.OpenSearch)
        presenter.onIntent(TreeIntent.OpenTransfer)
        presenter.onIntent(TreeIntent.Close)
        runCurrent()

        assertEquals(
            listOf(
                TreeEvent.OpenActions(FileOpsRequest.Actions(row.entry, "$ROOT/src")),
                TreeEvent.OpenActions(FileOpsRequest.NewFolder("$ROOT/src")),
                TreeEvent.OpenSearch,
                TreeEvent.OpenTransfer("$ROOT/src"),
                TreeEvent.Close,
            ),
            events,
        )
    }

    @Test
    fun `events sent while nobody collects wait for the next collector`() = runTest {
        files.folders[ROOT] = emptyList()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(TreeIntent.OpenSearch)
        val events = events(presenter)
        runCurrent()

        assertEquals(listOf<TreeEvent>(TreeEvent.OpenSearch), events)
    }

    /**
     * Every user intent but the view options drops a symlink probe still in flight: neither a probe
     * that would list ("dir") nor one that would open as a file ("note") may act afterwards.
     */
    @Test
    fun `every move or selection drops a pending symlink probe`() {
        val intents: List<Pair<String, (TreeState) -> TreeIntent>> = listOf(
            "open file" to { s -> TreeIntent.Open(s.entries.single { it.entry.name == "a.txt" }) },
            "open folder row" to { s -> TreeIntent.Open(s.entries.single { it.entry.name == "src" }) },
            "open other link" to { s -> TreeIntent.Open(s.entries.single { it.entry.name == "other" }) },
            "up" to { _ -> TreeIntent.Up },
            "open folder" to { _ -> TreeIntent.OpenFolder("src") },
            "refresh" to { _ -> TreeIntent.Refresh },
            "retry" to { _ -> TreeIntent.Retry },
            "close" to { _ -> TreeIntent.Close },
            "actions" to { s -> TreeIntent.ShowActions(s.entries.single { it.entry.name == "a.txt" }) },
            "new folder" to { _ -> TreeIntent.NewFolder },
            "search" to { _ -> TreeIntent.OpenSearch },
            "transfer" to { _ -> TreeIntent.OpenTransfer },
        )
        for ((name, intent) in intents) {
            for (link in listOf("dir", "note")) {
                runTest {
                    val listing = FakeListing()
                    listing.folders[ROOT] =
                        listOf(link("$ROOT/dir"), link("$ROOT/note"), link("$ROOT/other"), file("$ROOT/a.txt"), folder("$ROOT/src"))
                    listing.folders["$ROOT/dir"] = listOf(file("$ROOT/dir/inside.txt"))
                    listing.failures["$ROOT/note"] = FilesException(FilesFailure.ServerFailed)
                    listing.folders["$ROOT/src"] = emptyList()
                    // The other link is never let through: only the first probe's fate is checked.
                    listing.gates["$ROOT/other"] = CompletableDeferred()
                    val gate = CompletableDeferred<Unit>()
                    listing.gates["$ROOT/$link"] = gate
                    val presenter = DefaultTreePresenter(ROOT, listing, git, backgroundScope)
                    val events = events(presenter)
                    val folders = mutableListOf<String>()
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        presenter.state.collect { folders += it.folder }
                    }
                    runCurrent()

                    presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single { it.entry.name == link }))
                    runCurrent()
                    presenter.onIntent(intent(presenter.state.value))
                    gate.complete(Unit)
                    runCurrent()

                    assertFalse("$ROOT/dir" in folders, "$name let the folder probe land")
                    assertFalse(
                        events.any { it == TreeEvent.OpenFile("$ROOT/$link") },
                        "$name let the $link probe open a file",
                    )
                }
            }
        }
    }

    /** Every state the tree publishes pairs a folder with that folder's rows only. */
    @Test
    fun `the rows shown always belong to the folder shown`() = runTest {
        files.folders[ROOT] = listOf(folder("$ROOT/src"), link("$ROOT/dir"), file("$ROOT/a.txt"))
        files.folders["$ROOT/src"] = listOf(file("$ROOT/src/main.kt"), link("$ROOT/src/up"))
        files.folders["$ROOT/dir"] = listOf(file("$ROOT/dir/inside.txt"))
        files.folders["$ROOT/src/up"] = listOf(file("$ROOT/src/up/x.txt"))
        val presenter = presenter()
        val seen = mutableListOf<TreeState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { presenter.state.collect { seen += it } }
        runCurrent()

        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single { it.entry.name == "dir" }))
        runCurrent()
        presenter.onIntent(TreeIntent.Up)
        runCurrent()
        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single { it.entry.name == "src" }))
        runCurrent()
        presenter.onIntent(TreeIntent.Open(presenter.state.value.entries.single { it.entry.name == "up" }))
        runCurrent()
        presenter.onIntent(TreeIntent.OpenFolder("src"))
        presenter.onIntent(TreeIntent.Refresh)
        runCurrent()

        val moves = seen.map { it.folder }.zipWithNext().filter { (a, b) -> a != b }.map { it.second }
        assertEquals(listOf("$ROOT/dir", ROOT, "$ROOT/src", "$ROOT/src/up", "$ROOT/src"), moves)
        assertEquals(listOf("main.kt", "up"), presenter.state.value.names())
        for (state in seen) {
            for (row in state.entries) {
                assertEquals(state.folder, row.entry.path.substringBeforeLast('/'), "a row of another folder in $state")
            }
        }
    }

    /**
     * A probe that fails decides to open the link as a file and sends that in one locked step, so
     * no move can come between them. The event channel is swapped (by reflection) for one that, as
     * the probe sends, tries a move from another thread and waits a while for it: the move must not
     * get through before the send.
     */
    @Test
    fun `no move gets between a probe's check and its open file`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            files.folders[ROOT] = listOf(link("$ROOT/note"), folder("$ROOT/src"))
            files.folders["$ROOT/src"] = emptyList()
            files.failures["$ROOT/note"] = FilesException(FilesFailure.ServerFailed)
            val presenter = DefaultTreePresenter(directory = ROOT, files = files, git = git, scope = scope)
            val rows = withTimeout(10.seconds) { presenter.state.first { !it.loading } }.entries

            val field = DefaultTreePresenter::class.java.getDeclaredField("channel").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val real = field.get(presenter) as Channel<TreeEvent>
            var mover: Thread? = null
            var movedFirst: Boolean? = null
            field.set(
                presenter,
                object : Channel<TreeEvent> by real {
                    override fun trySend(element: TreeEvent): ChannelResult<Unit> {
                        if (element == TreeEvent.OpenFile("$ROOT/note")) {
                            val thread = Thread { presenter.onIntent(TreeIntent.OpenFolder("src")) }
                            thread.start()
                            thread.join(500)
                            movedFirst = !thread.isAlive
                            mover = thread
                        }
                        return real.trySend(element)
                    }
                },
            )

            presenter.onIntent(TreeIntent.Open(rows.single { it.entry.name == "note" }))
            val event = withTimeout(10.seconds) { presenter.events.first() }
            mover?.join(10_000)

            assertEquals(TreeEvent.OpenFile("$ROOT/note"), event)
            assertEquals(false, movedFirst)
            withTimeout(10.seconds) { presenter.state.first { it.folder == "$ROOT/src" && !it.loading } }
            Unit
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }

    @Test
    fun `no event is dropped however many wait for a collector`() = runTest {
        files.folders[ROOT] = listOf(file("$ROOT/a.txt"))
        val presenter = presenter()
        runCurrent()
        val count = 500

        repeat(count) { presenter.onIntent(TreeIntent.OpenSearch) }
        val seen = mutableListOf<TreeEvent>()
        backgroundScope.launch { presenter.events.take(count).toList(seen) }
        runCurrent()

        assertEquals(count, seen.size)
    }

    /**
     * The race of two quick symlink opens on a real thread: the first probe has its listing and is
     * waiting for the presenter's lock when the second open comes. The test holds that lock (read
     * by reflection, as nothing else can park a coroutine exactly there) while it sends the second
     * open, so the first probe lands only after it. Only the second may take effect.
     */
    @Test
    fun `a probe overtaken while it lands does not apply`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        try {
            files.folders[ROOT] = listOf(link("$ROOT/first"), link("$ROOT/second"))
            files.folders["$ROOT/first"] = listOf(file("$ROOT/first/one.txt"))
            files.folders["$ROOT/second"] = listOf(file("$ROOT/second/two.txt"))
            val second = CompletableDeferred<Unit>()
            files.gates["$ROOT/second"] = second
            val presenter = DefaultTreePresenter(directory = ROOT, files = files, git = git, scope = scope)
            val rows = withTimeout(10.seconds) { presenter.state.first { !it.loading } }.entries
            val worker = executor.submit<Thread> { Thread.currentThread() }.get()
            val lock = DefaultTreePresenter::class.java.getDeclaredField("lock")
                .apply { isAccessible = true }
                .get(presenter)

            synchronized(lock) {
                presenter.onIntent(TreeIntent.Open(rows.single { it.entry.name == "first" }))
                val deadline = System.nanoTime() + 10_000_000_000L
                while (worker.state != Thread.State.BLOCKED) {
                    check(System.nanoTime() < deadline) { "The first probe never reached the lock" }
                    Thread.sleep(1)
                }
                presenter.onIntent(TreeIntent.Open(rows.single { it.entry.name == "second" }))
            }
            // Everything queued on the worker so far has run: the first probe has landed or quit.
            executor.submit { }.get()

            assertEquals(ROOT, presenter.state.value.folder)
            assertEquals(listOf("first", "second"), presenter.state.value.names())

            second.complete(Unit)
            val opened = withTimeout(10.seconds) { presenter.state.first { it.folder == "$ROOT/second" } }
            assertEquals(listOf("two.txt"), opened.names())
        } finally {
            scope.cancel()
            executor.shutdown()
        }
    }
}
