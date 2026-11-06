package io.github.thewisekd.phopencode.feature.git.logic.diff

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.git.command.DiffOutput
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.git.command.ShowOutput
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeCommand
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcessEnd
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffHunk
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLine
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLineKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val DIR = "/srv/proje alanı/alt"
private const val ROOT = "/srv/proje alanı"
private const val PATH = "src/çekirdek.kt"
private const val HASH = "0123456789abcdef0123456789abcdef01234567"
private const val PARENT = "89abcdef0123456789abcdef0123456789abcdef"
private const val REVISION = "$HASH^{commit}"

private val TWO_HUNKS = """
    diff --git a/src/çekirdek.kt b/src/çekirdek.kt
    index 1111111111111111111111111111111111111111..2222222222222222222222222222222222222222 100644
    --- a/src/çekirdek.kt
    +++ b/src/çekirdek.kt
    @@ -1,3 +1,3 @@ fun main
     a
    -b
    +B
     c
    @@ -10,2 +10,3 @@
     x
    +y
     z
    \ No newline at end of file

""".trimIndent()

/**
 * The presenter on a scripted command runner: which commands it runs, in which folder, and how it
 * turns their output and failures into [DiffState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiffPresenterTest {
    private val exec = FakeRemoteExec()
    private val git = ExecGitConnection(exec)

    private fun TestScope.presenter(target: DiffTarget, directory: String = DIR, maxBytes: Long = DefaultDiffPresenter.DEFAULT_MAX_BYTES) =
        DefaultDiffPresenter(directory = directory, target = target, git = git, scope = backgroundScope, maxBytes = maxBytes)

    private suspend fun DefaultDiffPresenter.loaded(): DiffState = state.first { !it.loading }

    private fun script(command: String, block: FakeCommand) = exec.on(command, block)

    private fun output(command: String, stdout: String, exit: Int = 0) = script(command) {
        stdout(stdout)
        exit(exit)
    }

    private fun failing(command: String, stderr: String, exit: Int) = script(command) {
        stderr(stderr)
        exit(exit)
    }

    private fun toplevel() = output(GitCommands.revParseToplevel(DIR).commandLine, "$ROOT\n")

    private fun nameStatus(staged: Boolean, stdout: String) =
        output(GitCommands.diff(ROOT, staged = staged, output = DiffOutput.NameStatus).commandLine, stdout)

    private fun patchCommand(staged: Boolean, paths: List<String> = listOf(PATH)) =
        GitCommands.diff(ROOT, staged = staged, paths = paths).commandLine

    private fun numStatCommand(staged: Boolean, paths: List<String> = listOf(PATH)) =
        GitCommands.diff(ROOT, staged = staged, paths = paths, output = DiffOutput.NumStat).commandLine

    private fun parents(stdout: String) =
        output(GitCommands.show(ROOT, REVISION, format = "%P", output = ShowOutput.None).commandLine, stdout)

    private fun commitNameStatus(stdout: String) =
        output(GitCommands.show(ROOT, REVISION, output = ShowOutput.NameStatus).commandLine, stdout)

    private fun commitPatchCommand(paths: List<String> = listOf(PATH)) = GitCommands.show(ROOT, REVISION, paths = paths).commandLine

    private val commands: List<String> get() = exec.executions.map { it.command }

    @Test
    fun `the working diff runs in the repository root and keeps hunks, line kinds and numbers`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), TWO_HUNKS)
        val presenter = presenter(DiffTarget.Working(PATH))

        assertEquals(DiffState(DiffTarget.Working(PATH)), presenter.state.value)
        val state = presenter.loaded()

        assertNull(state.error)
        val expected = GitFileDiff(
            path = PATH,
            kind = GitChangeKind.Modified,
            content = DiffContent.Text(
                listOf(
                    DiffHunk(
                        1, 3, 1, 3, "fun main",
                        listOf(
                            DiffLine(DiffLineKind.Context, "a", 1, 1),
                            DiffLine(DiffLineKind.Deleted, "b", 2, null),
                            DiffLine(DiffLineKind.Added, "B", null, 2),
                            DiffLine(DiffLineKind.Context, "c", 3, 3),
                        ),
                    ),
                    DiffHunk(
                        10, 2, 10, 3, null,
                        listOf(
                            DiffLine(DiffLineKind.Context, "x", 10, 10),
                            DiffLine(DiffLineKind.Added, "y", null, 11),
                            DiffLine(DiffLineKind.Context, "z", 11, 12),
                            DiffLine(DiffLineKind.NoNewline, "\\ No newline at end of file", null, null),
                        ),
                    ),
                ),
            ),
            additions = 2,
            deletions = 1,
        )
        assertEquals(expected, state.diff)
        assertEquals(
            listOf(GitCommands.revParseToplevel(DIR).commandLine, GitCommands.diff(ROOT, false, output = DiffOutput.NameStatus).commandLine, patchCommand(false)),
            commands,
        )
    }

    @Test
    fun `the staged diff reads the index with --cached`() = runTest {
        toplevel()
        nameStatus(staged = true, "M\u0000$PATH\u0000")
        output(patchCommand(staged = true), TWO_HUNKS)

        val state = presenter(DiffTarget.Staged(PATH)).loaded()

        assertEquals(2, (state.diff?.content as DiffContent.Text).hunks.size)
        assertTrue(commands.last().contains("'--cached'"))
    }

    @Test
    fun `a rename diffs both paths so git pairs them`() = runTest {
        toplevel()
        nameStatus(staged = true, "M\u0000other\u0000R087\u0000eski ad.kt\u0000$PATH\u0000")
        output(
            patchCommand(staged = true, listOf("eski ad.kt", PATH)),
            """
            diff --git a/eski ad.kt b/src/çekirdek.kt
            similarity index 87%
            rename from eski ad.kt
            rename to src/çekirdek.kt
            index 1111111111111111111111111111111111111111..2222222222222222222222222222222222222222 100644
            --- a/eski ad.kt
            +++ b/src/çekirdek.kt
            @@ -1 +1 @@
            -a
            +b

            """.trimIndent(),
        )

        val diff = presenter(DiffTarget.Staged(PATH)).loaded().diff!!

        assertEquals(GitChangeKind.Renamed, diff.kind)
        assertEquals("eski ad.kt", diff.oldPath)
        assertEquals(PATH, diff.path)
        assertEquals(1 to 1, diff.additions to diff.deletions)
    }

    @Test
    fun `a pure rename has no lines`() = runTest {
        toplevel()
        nameStatus(staged = true, "R100\u0000old.kt\u0000$PATH\u0000")
        output(
            patchCommand(staged = true, listOf("old.kt", PATH)),
            "diff --git a/old.kt b/$PATH\nsimilarity index 100%\nrename from old.kt\nrename to $PATH\n",
        )

        val diff = presenter(DiffTarget.Staged(PATH)).loaded().diff!!

        assertEquals(GitChangeKind.Renamed, diff.kind)
        assertEquals(DiffContent.Unchanged, diff.content)
    }

    @Test
    fun `a name list that runs too long only loses the rename pairing`() = runTest {
        toplevel()
        script(GitCommands.diff(ROOT, staged = true, output = DiffOutput.NameStatus).commandLine) { awaitCancellation() }
        output(patchCommand(staged = true), TWO_HUNKS)

        val state = presenter(DiffTarget.Staged(PATH)).loaded()

        assertNull(state.error)
        assertEquals(GitChangeKind.Modified, state.diff?.kind)
    }

    @Test
    fun `new and deleted files keep their kind`() = runTest {
        toplevel()
        nameStatus(staged = true, "A\u0000$PATH\u0000")
        output(
            patchCommand(staged = true),
            "diff --git a/$PATH b/$PATH\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/$PATH\n@@ -0,0 +1,2 @@\n+a\n+b\n",
        )
        val added = presenter(DiffTarget.Staged(PATH)).loaded().diff!!
        assertEquals(GitChangeKind.Added, added.kind)
        assertEquals(listOf(null to 1, null to 2), (added.content as DiffContent.Text).hunks.single().lines.map { it.oldNumber to it.newNumber })

        nameStatus(staged = false, "D\u0000$PATH\u0000")
        output(
            patchCommand(staged = false),
            "diff --git a/$PATH b/$PATH\ndeleted file mode 100644\nindex 1111111..0000000\n--- a/$PATH\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n",
        )
        val deleted = presenter(DiffTarget.Working(PATH)).loaded().diff!!
        assertEquals(GitChangeKind.Deleted, deleted.kind)
        assertEquals(0 to 1, deleted.additions to deleted.deletions)
    }

    @Test
    fun `a file that became a symlink is one type change`() = runTest {
        toplevel()
        nameStatus(staged = false, "T\u0000$PATH\u0000")
        output(
            patchCommand(staged = false),
            "diff --git a/$PATH b/$PATH\ndeleted file mode 100644\nindex 1111111..0000000\n--- a/$PATH\n+++ /dev/null\n@@ -1 +0,0 @@\n-a\n" +
                "diff --git a/$PATH b/$PATH\nnew file mode 120000\nindex 0000000..2222222\n--- /dev/null\n+++ b/$PATH\n@@ -0,0 +1 @@\n+x\n\\ No newline at end of file\n",
        )

        val diff = presenter(DiffTarget.Working(PATH)).loaded().diff!!

        assertEquals(GitChangeKind.TypeChanged, diff.kind)
        assertEquals(2, (diff.content as DiffContent.Text).hunks.size)
        assertEquals(1 to 1, diff.additions to diff.deletions)
    }

    @Test
    fun `a binary file has no lines`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(
            patchCommand(staged = false),
            "diff --git a/$PATH b/$PATH\nindex 1111111..2222222 100644\nBinary files a/$PATH and b/$PATH differ\n",
        )

        val diff = presenter(DiffTarget.Working(PATH)).loaded().diff!!

        assertTrue(diff.isBinary)
        assertEquals(0 to 0, diff.additions to diff.deletions)
    }

    @Test
    fun `a mode change alone is unchanged`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), "diff --git a/$PATH b/$PATH\nold mode 100644\nnew mode 100755\n")

        assertEquals(DiffContent.Unchanged, presenter(DiffTarget.Working(PATH)).loaded().diff?.content)
    }

    @Test
    fun `a diff over the limit is too large and counted by numstat, never read whole`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), "x".repeat(200))
        output(numStatCommand(staged = false), "120\t7\t$PATH\u0000")

        val state = presenter(DiffTarget.Working(PATH), maxBytes = 100).loaded()

        assertNull(state.error)
        assertEquals(GitFileDiff(PATH, null, GitChangeKind.Modified, DiffContent.TooLarge(100), 120, 7), state.diff)
        assertEquals(FakeProcessEnd.Killed, exec.executions.single { it.command == patchCommand(false) }.end)
    }

    @Test
    fun `a too large rename keeps its old path and unknown counts are 0`() = runTest {
        toplevel()
        nameStatus(staged = true, "R090\u0000old.kt\u0000$PATH\u0000")
        output(patchCommand(staged = true, listOf("old.kt", PATH)), "x".repeat(200))
        failing(numStatCommand(staged = true, listOf("old.kt", PATH)), "fatal: something", 128)

        val diff = presenter(DiffTarget.Staged(PATH), maxBytes = 100).loaded().diff!!

        assertEquals(GitFileDiff(PATH, "old.kt", GitChangeKind.Renamed, DiffContent.TooLarge(100), 0, 0), diff)
    }

    @Test
    fun `a numstat of a rename is matched by its new path`() = runTest {
        toplevel()
        nameStatus(staged = true, "R090\u0000old.kt\u0000$PATH\u0000")
        output(patchCommand(staged = true, listOf("old.kt", PATH)), "x".repeat(200))
        output(numStatCommand(staged = true, listOf("old.kt", PATH)), "3\t4\t\u0000old.kt\u0000$PATH\u0000")

        val diff = presenter(DiffTarget.Staged(PATH), maxBytes = 100).loaded().diff!!

        assertEquals(3 to 4, diff.additions to diff.deletions)
    }

    @Test
    fun `an untracked file shows as untracked when git diff has nothing`() = runTest {
        toplevel()
        nameStatus(staged = false, "")
        output(patchCommand(staged = false), "")
        output(
            GitCommands.status(ROOT, includeUntracked = true, paths = listOf(PATH)).commandLine,
            "# branch.oid (initial)\u0000# branch.head main\u0000? $PATH\u0000",
        )

        val diff = presenter(DiffTarget.Working(PATH)).loaded().diff!!

        assertEquals(GitFileDiff(PATH, null, GitChangeKind.Untracked, DiffContent.Unchanged, 0, 0), diff)
    }

    @Test
    fun `nothing to show is unchanged`() = runTest {
        toplevel()
        nameStatus(staged = true, "")
        output(patchCommand(staged = true), "")

        val state = presenter(DiffTarget.Staged(PATH)).loaded()

        assertEquals(GitFileDiff(PATH, null, GitChangeKind.Modified, DiffContent.Unchanged, 0, 0), state.diff)
        assertNull(state.error)
    }

    @Test
    fun `a commit file diff reads the commit against its parent`() = runTest {
        toplevel()
        parents("$PARENT\n")
        commitNameStatus("M\u0000$PATH\u0000")
        output(commitPatchCommand(), TWO_HUNKS)

        val diff = presenter(DiffTarget.InCommit(HASH, PATH)).loaded().diff!!

        assertEquals(2 to 1, diff.additions to diff.deletions)
        assertEquals(2, (diff.content as DiffContent.Text).hunks.size)
        assertEquals(commitPatchCommand(), commands.last())
    }

    @Test
    fun `a root commit shows the file as added`() = runTest {
        toplevel()
        parents("\n")
        commitNameStatus("A\u0000$PATH\u0000")
        output(commitPatchCommand(), "diff --git a/$PATH b/$PATH\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/$PATH\n@@ -0,0 +1 @@\n+a\n")

        assertEquals(GitChangeKind.Added, presenter(DiffTarget.InCommit(HASH, PATH)).loaded().diff?.kind)
    }

    @Test
    fun `a merge commit is refused before its combined diff runs`() = runTest {
        toplevel()
        parents("$PARENT $HASH\n")

        val state = presenter(DiffTarget.InCommit(HASH, PATH)).loaded()

        assertEquals(GitFailure.Unknown, state.error)
        assertNull(state.diff)
        assertFalse(commands.any { it == commitPatchCommand() })
    }

    @Test
    fun `a commit that is not there is not found`() = runTest {
        toplevel()
        failing(
            GitCommands.show(ROOT, REVISION, format = "%P", output = ShowOutput.None).commandLine,
            "fatal: bad object $HASH\n",
            128,
        )

        assertEquals(GitFailure.NotFound, presenter(DiffTarget.InCommit(HASH, PATH)).loaded().error)
    }

    @Test
    fun `a folder outside a repository is not a repository`() = runTest {
        failing(GitCommands.revParseToplevel(DIR).commandLine, "fatal: not a git repository (or any of the parent directories): .git\n", 128)

        assertEquals(GitFailure.NotRepository, presenter(DiffTarget.Working(PATH)).loaded().error)
    }

    @Test
    fun `git missing on the server`() = runTest {
        // An unscripted command exits 127, as a shell that cannot find git.
        assertEquals(GitFailure.GitMissing, presenter(DiffTarget.Working(PATH)).loaded().error)
    }

    @Test
    fun `another non-zero exit keeps its code`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        failing(patchCommand(staged = false), "fatal: something else\n", 2)

        assertEquals(GitFailure.GitError(2), presenter(DiffTarget.Working(PATH)).loaded().error)
    }

    @Test
    fun `malformed output is an unknown failure`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), "this is not a diff\n")

        assertEquals(GitFailure.Unknown, presenter(DiffTarget.Working(PATH)).loaded().error)
    }

    @Test
    fun `an unmerged file is a conflict, in the working tree and in the index`() = runTest {
        toplevel()
        nameStatus(staged = false, "U\u0000$PATH\u0000")
        output(patchCommand(staged = false), "diff --cc $PATH\nindex 1111111,2222222..0000000\n--- a/$PATH\n+++ b/$PATH\n@@@ -1,1 -1,1 +1,1 @@@\n")
        assertEquals(GitFailure.Conflict, presenter(DiffTarget.Working(PATH)).loaded().error)

        nameStatus(staged = true, "U\u0000$PATH\u0000")
        output(patchCommand(staged = true), "* Unmerged path $PATH\n")
        assertEquals(GitFailure.Conflict, presenter(DiffTarget.Staged(PATH)).loaded().error)
    }

    @Test
    fun `an unmerged file is a conflict before its patch is read, however large`() = runTest {
        toplevel()
        nameStatus(staged = false, "U\u0000$PATH\u0000")
        output(patchCommand(staged = false), "x".repeat(200))

        assertEquals(GitFailure.Conflict, presenter(DiffTarget.Working(PATH), maxBytes = 100).loaded().error)
        assertFalse(commands.any { it == patchCommand(false) })
    }

    @Test
    fun `without the name list, status finds the conflict behind a too large patch`() = runTest {
        toplevel()
        script(GitCommands.diff(ROOT, staged = false, output = DiffOutput.NameStatus).commandLine) { awaitCancellation() }
        output(patchCommand(staged = false), "x".repeat(200))
        output(
            GitCommands.status(ROOT, includeUntracked = true, paths = listOf(PATH)).commandLine,
            "u UU N... 100644 100644 100644 100644 " +
                "1111111111111111111111111111111111111111 2222222222222222222222222222222222222222 " +
                "3333333333333333333333333333333333333333 $PATH\u0000",
        )

        assertEquals(GitFailure.Conflict, presenter(DiffTarget.Working(PATH), maxBytes = 100).loaded().error)
    }

    @Test
    fun `a name that is not UTF-8 never matches a route path holding U+FFFD`() = runTest {
        val literal = "caf�.txt"
        val raw = "caf".toByteArray() + byteArrayOf(0xE9.toByte()) + ".txt".toByteArray()
        val nul = byteArrayOf(0)
        toplevel()
        script(GitCommands.diff(ROOT, staged = false, output = DiffOutput.NameStatus).commandLine) {
            stdout("U".toByteArray() + nul + raw + nul)
            exit(0)
        }
        script(GitCommands.diff(ROOT, staged = false, paths = listOf(literal)).commandLine) {
            stdout(
                "diff --git a/".toByteArray() + raw + " b/".toByteArray() + raw +
                    "\nindex 1111111..2222222 100644\n--- a/".toByteArray() + raw + "\n+++ b/".toByteArray() + raw +
                    "\n@@ -1 +1 @@\n-a\n+b\n".toByteArray(),
            )
            exit(0)
        }
        script(GitCommands.status(ROOT, includeUntracked = true, paths = listOf(literal)).commandLine) {
            stdout("? ".toByteArray() + raw + nul)
            exit(0)
        }

        val state = presenter(DiffTarget.Working(literal)).loaded()

        assertNull(state.error)
        assertEquals(GitFileDiff(literal, null, GitChangeKind.Modified, DiffContent.Unchanged, 0, 0), state.diff)
    }

    @Test
    fun `a relative folder or a bad commit is rejected before anything runs`() = runTest {
        toplevel()
        assertEquals(GitFailure.Rejected, presenter(DiffTarget.Working(PATH), directory = "relative/dir").loaded().error)
        assertEquals(GitFailure.Rejected, presenter(DiffTarget.InCommit("--output=/tmp/x", PATH)).loaded().error)
        assertEquals(GitFailure.Rejected, presenter(DiffTarget.Working("")).loaded().error)
        assertEquals(GitFailure.Rejected, presenter(DiffTarget.Staged("a\u0000b")).loaded().error)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `a timeout and a lost connection are mapped`() = runTest {
        script(GitCommands.revParseToplevel(DIR).commandLine) { awaitCancellation() }
        assertEquals(GitFailure.TimedOut, presenter(DiffTarget.Working(PATH)).loaded().error)

        exec.failNext(SshException.Disconnected())
        assertEquals(GitFailure.NotConnected, presenter(DiffTarget.Working(PATH)).loaded().error)
    }

    @Test
    fun `output over the limit in a step other than the patch is a failure`() = runTest {
        toplevel()
        output(GitCommands.show(ROOT, REVISION, format = "%P", output = ShowOutput.None).commandLine, "a".repeat(70_000))

        assertEquals(GitFailure.OutputTooLarge, presenter(DiffTarget.InCommit(HASH, PATH)).loaded().error)
    }

    @Test
    fun `retry reads again after an error and keeps the target`() = runTest {
        failing(GitCommands.revParseToplevel(DIR).commandLine, "fatal: something\n", 1)
        val presenter = presenter(DiffTarget.Working(PATH))
        assertEquals(GitFailure.GitError(1), presenter.loaded().error)

        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), TWO_HUNKS)
        presenter.onIntent(DiffIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)

        val state = presenter.loaded()
        assertNull(state.error)
        assertEquals(DiffTarget.Working(PATH), state.target)
        assertEquals(2, (state.diff?.content as DiffContent.Text).hunks.size)
    }

    @Test
    fun `retry without an error does nothing`() = runTest {
        toplevel()
        nameStatus(staged = false, "")
        output(patchCommand(staged = false), "")
        output(GitCommands.status(ROOT, includeUntracked = true, paths = listOf(PATH)).commandLine, "")
        val presenter = presenter(DiffTarget.Working(PATH))
        presenter.loaded()
        val before = commands.size

        presenter.onIntent(DiffIntent.Retry)
        advanceUntilIdle()

        assertEquals(before, commands.size)
    }

    @Test
    fun `refresh reads again and keeps the shown diff while loading`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), TWO_HUNKS)
        val presenter = presenter(DiffTarget.Working(PATH))
        val first = presenter.loaded().diff

        script(patchCommand(staged = false)) { awaitCancellation() }
        presenter.onIntent(DiffIntent.Refresh)
        runCurrent()
        assertTrue(presenter.state.value.loading)
        assertEquals(first, presenter.state.value.diff)

        // A second refresh while one runs starts nothing new.
        presenter.onIntent(DiffIntent.Refresh)
        runCurrent()
        assertEquals(2, exec.executions.count { it.command == patchCommand(false) })
    }

    @Test
    fun `close is a one-time event`() = runTest {
        val presenter = presenter(DiffTarget.Working(PATH))
        presenter.onIntent(DiffIntent.Close)

        presenter.events.test {
            assertEquals(DiffEvent.Close, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `nothing runs before the scope does, and the first frame is the initial state`() = runTest {
        val presenter = presenter(DiffTarget.Staged(PATH))

        assertEquals(DiffState(DiffTarget.Staged(PATH)), presenter.state.value)
        assertTrue(commands.isEmpty())
    }

    @Test
    fun `leaving the screen ends the running command`() = runTest {
        script(GitCommands.revParseToplevel(DIR).commandLine) { awaitCancellation() }
        val scope = CoroutineScope(coroutineContext + Job())
        DefaultDiffPresenter(DIR, DiffTarget.Working(PATH), git, scope)
        runCurrent()
        exec.awaitRunning(GitCommands.revParseToplevel(DIR).commandLine)

        scope.cancel()
        runCurrent()

        assertEquals(FakeProcessEnd.Killed, exec.executions.single().end)
    }

    @Test
    fun `a limit that is not positive is refused`() = runTest {
        assertFailsWith<IllegalArgumentException> { presenter(DiffTarget.Working(PATH), maxBytes = 0) }
    }

    @Test
    fun `printing the state shows no path or content`() = runTest {
        toplevel()
        nameStatus(staged = false, "M\u0000$PATH\u0000")
        output(patchCommand(staged = false), TWO_HUNKS)

        val printed = presenter(DiffTarget.Working(PATH)).loaded().toString()

        assertFalse(PATH in printed || "çekirdek" in printed || "fun main" in printed, printed)
    }
}
