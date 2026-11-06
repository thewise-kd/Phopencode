package io.github.thewisekd.phopencode.feature.git.logic.status

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcess
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcessEnd
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitException
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val DIR = "/srv/çalışma alanı/sub"
private const val ROOT = "/srv/çalışma alanı"
private val OID = "a".repeat(40)
private val HASH = "b".repeat(40)

/** One NUL-terminated record of `status --porcelain=v2 -z`. */
private fun ByteArrayOutputStream.record(text: String) = record(text.toByteArray(Charsets.UTF_8))

private fun ByteArrayOutputStream.record(bytes: ByteArray) {
    write(bytes)
    write(0)
}

private fun changed(xy: String, path: String) = "1 $xy N... 100644 100644 100644 $HASH $HASH $path"

private fun statusOutput(
    head: String? = "main",
    oid: String? = OID,
    upstream: String? = null,
    ab: String? = null,
    build: ByteArrayOutputStream.() -> Unit = {},
): ByteArray = ByteArrayOutputStream().apply {
    record("# branch.oid ${oid ?: "(initial)"}")
    record("# branch.head ${head ?: "(detached)"}")
    if (upstream != null) record("# branch.upstream $upstream")
    if (ab != null) record("# branch.ab $ab")
    build()
}.toByteArray()

/** A repository with a row in every group. */
private val MIXED = statusOutput(upstream = "origin/main", ab = "+2 -1") {
    record(changed("M.", "staged.txt"))
    record(changed(".M", "unstaged.txt"))
    record(changed("MM", "both.txt"))
    record(changed("D.", "gone.txt"))
    record("2 R. N... 100644 100644 100644 $HASH $HASH R100 new name.txt")
    record("old name.txt")
    record("u UU N... 100644 100644 100644 100644 $HASH $HASH $HASH conflict.txt")
    record("? -rf")
    record("! ignored.log")
}

private val TOPLEVEL = GitCommands.revParseToplevel(DIR).commandLine
private val STATUS = GitCommands.status(ROOT).commandLine

private fun add(vararg paths: String) = GitCommands.add(ROOT, paths.toList()).commandLine

private fun unstage(vararg paths: String) = GitCommands.unstage(ROOT, paths.toList()).commandLine

private fun ok(): suspend FakeProcess.() -> ExecEvent.Exited = { exit(0) }

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultStatusPresenterTest {
    private val exec = FakeRemoteExec()

    /** What `status` prints next; tests change it to play a repository that changed. */
    private var statusBytes: ByteArray = MIXED

    private fun scriptRepository(output: ByteArray = MIXED) {
        statusBytes = output
        exec.on(TOPLEVEL) {
            stdout("$ROOT\n")
            exit(0)
        }
        exec.on(STATUS) {
            stdout(statusBytes)
            exit(0)
        }
    }

    private fun TestScope.presenter(git: GitConnection = ExecGitConnection(exec), scope: CoroutineScope = backgroundScope) =
        DefaultStatusPresenter(directory = DIR, git = git, scope = scope)

    private fun TestScope.loaded(output: ByteArray = MIXED): DefaultStatusPresenter {
        scriptRepository(output)
        val presenter = presenter()
        runCurrent()
        assertFalse(presenter.state.value.loading)
        return presenter
    }

    private fun commandsRun(): List<String> = exec.executions.map { it.command }

    // Loading

    @Test
    fun `the first frame shows the initial state and nothing runs before the scope does`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val presenter = presenter()

        assertEquals(StatusState(directory = DIR), presenter.state.value)
        assertTrue(exec.executions.isEmpty())
    }

    @Test
    fun `staged, unstaged, untracked and conflicted files are separated`() = runTest(timeout = 10.seconds) {
        val state = loaded().state.value

        assertEquals(
            listOf(
                GitFileChange("staged.txt", GitChangeKind.Modified),
                GitFileChange("both.txt", GitChangeKind.Modified),
                GitFileChange("gone.txt", GitChangeKind.Deleted),
                GitFileChange("new name.txt", GitChangeKind.Renamed, originalPath = "old name.txt"),
            ),
            state.staged,
        )
        assertEquals(
            listOf(GitFileChange("unstaged.txt", GitChangeKind.Modified), GitFileChange("both.txt", GitChangeKind.Modified)),
            state.unstaged,
        )
        assertEquals(listOf(GitFileChange("-rf", GitChangeKind.Untracked)), state.untracked)
        assertEquals(listOf(GitFileChange("conflict.txt", GitChangeKind.Unmerged)), state.conflicted)
        assertFalse(state.loading)
        assertFalse(state.refreshing)
        assertNull(state.error)
        assertFalse(state.isClean)
    }

    @Test
    fun `the root is resolved and status runs there, since its paths are root-relative`() = runTest(timeout = 10.seconds) {
        loaded()

        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
    }

    @Test
    fun `branch name, commit, upstream and ahead and behind come from the headers`() = runTest(timeout = 10.seconds) {
        val state = loaded().state.value

        assertEquals(GitBranchStatus(head = "main", commit = OID, upstream = "origin/main", ahead = 2, behind = 1), state.branch)
    }

    @Test
    fun `every kind of change maps to its row kind`() = runTest(timeout = 10.seconds) {
        val output = statusOutput {
            record(changed("A.", "added"))
            record(changed("T.", "typechange"))
            record("2 C. N... 100644 100644 100644 $HASH $HASH C75 copy")
            record("source")
            record(changed(".D", "deleted"))
            record(changed(".T", "worktree-typechange"))
            record("2 .R N... 100644 100644 100644 $HASH $HASH R100 moved")
            record("origin")
            record("2 RM N... 100644 100644 100644 $HASH $HASH R90 renamed-then-edited")
            record("before")
            record("u AA N... 100644 100644 100644 100644 $HASH $HASH $HASH both-added")
            record("u DU N... 100644 100644 000000 100644 $HASH $HASH $HASH deleted-by-us")
        }
        val state = loaded(output).state.value

        assertEquals(
            listOf(
                GitFileChange("added", GitChangeKind.Added),
                GitFileChange("typechange", GitChangeKind.TypeChanged),
                GitFileChange("copy", GitChangeKind.Copied, originalPath = "source"),
                GitFileChange("renamed-then-edited", GitChangeKind.Renamed, originalPath = "before"),
            ),
            state.staged,
        )
        assertEquals(
            listOf(
                GitFileChange("deleted", GitChangeKind.Deleted),
                GitFileChange("worktree-typechange", GitChangeKind.TypeChanged),
                GitFileChange("moved", GitChangeKind.Renamed, originalPath = "origin"),
                GitFileChange("renamed-then-edited", GitChangeKind.Modified),
            ),
            state.unstaged,
        )
        assertEquals(
            listOf(GitFileChange("both-added", GitChangeKind.Unmerged), GitFileChange("deleted-by-us", GitChangeKind.Unmerged)),
            state.conflicted,
        )
    }

    @Test
    fun `a clean repository has no rows and is clean`() = runTest(timeout = 10.seconds) {
        val state = loaded(statusOutput(upstream = "origin/main", ab = "+0 -0")).state.value

        assertTrue(state.isClean)
        assertEquals(0, state.changeCount)
        assertEquals(GitBranchStatus(head = "main", commit = OID, upstream = "origin/main"), state.branch)
    }

    @Test
    fun `a detached HEAD has no branch name`() = runTest(timeout = 10.seconds) {
        val state = loaded(statusOutput(head = null)).state.value

        assertEquals(GitBranchStatus(head = null, commit = OID), state.branch)
        assertTrue(state.branch!!.isDetached)
    }

    @Test
    fun `a repository without commits has a branch but no commit`() = runTest(timeout = 10.seconds) {
        val state = loaded(statusOutput(oid = null) { record(changed("A.", "first.txt")) }).state.value

        assertEquals(GitBranchStatus(head = "main", commit = null), state.branch)
        assertEquals(listOf(GitFileChange("first.txt", GitChangeKind.Added)), state.staged)
    }

    @Test
    fun `without an upstream ahead and behind are zero`() = runTest(timeout = 10.seconds) {
        val branch = loaded(statusOutput()).state.value.branch!!

        assertFalse(branch.hasUpstream)
        assertEquals(0, branch.ahead)
        assertEquals(0, branch.behind)
    }

    @Test
    fun `an upstream that is gone keeps its name and is marked gone`() = runTest(timeout = 10.seconds) {
        val branch = loaded(statusOutput(upstream = "origin/removed")).state.value.branch!!

        assertEquals(GitBranchStatus(head = "main", commit = OID, upstream = "origin/removed", upstreamGone = true), branch)
        assertTrue(branch.hasUpstream)
    }

    @Test
    fun `uncounted ahead and behind keep the upstream with zero counts marked unknown`() = runTest(timeout = 10.seconds) {
        val branch = loaded(statusOutput(upstream = "origin/main", ab = "+? -?")).state.value.branch!!

        assertEquals(GitBranchStatus(head = "main", commit = OID, upstream = "origin/main", aheadBehindKnown = false), branch)
        assertFalse(branch.upstreamGone)
    }

    @Test
    fun `counted ahead and behind are known and not gone`() = runTest(timeout = 10.seconds) {
        val branch = loaded(statusOutput(upstream = "origin/main", ab = "+0 -0")).state.value.branch!!

        assertTrue(branch.aheadBehindKnown)
        assertFalse(branch.upstreamGone)
    }

    @Test
    fun `without an upstream nothing is gone and the zero counts are known`() = runTest(timeout = 10.seconds) {
        val branch = loaded(statusOutput()).state.value.branch!!

        assertFalse(branch.upstreamGone)
        assertTrue(branch.aheadBehindKnown)
    }

    // Failures

    private fun TestScope.failedWith(expected: GitFailure) {
        val presenter = presenter()
        runCurrent()
        val state = presenter.state.value
        assertEquals(expected, state.error)
        assertFalse(state.loading)
        assertNull(state.branch)
        assertEquals(0, state.changeCount)
        assertFalse(state.isClean)
    }

    @Test
    fun `a folder outside a repository is not a repository`() = runTest(timeout = 10.seconds) {
        exec.on(TOPLEVEL) {
            stderr("fatal: not a git repository (or any of the parent directories): .git\n")
            exit(128)
        }
        failedWith(GitFailure.NotRepository)
        assertEquals(listOf(TOPLEVEL), commandsRun())
    }

    @Test
    fun `a folder without a work tree is not a repository`() = runTest(timeout = 10.seconds) {
        exec.on(TOPLEVEL) {
            stderr("fatal: this operation must be run in a work tree\n")
            exit(128)
        }
        failedWith(GitFailure.NotRepository)
    }

    @Test
    fun `a missing folder is not found`() = runTest(timeout = 10.seconds) {
        exec.on(TOPLEVEL) {
            stderr("fatal: cannot change to '$DIR': No such file or directory\n")
            exit(128)
        }
        failedWith(GitFailure.NotFound)
    }

    @Test
    fun `git that the shell cannot find is missing`() = runTest(timeout = 10.seconds) {
        // FakeRemoteExec answers an unscripted command as a shell would: exit 127.
        failedWith(GitFailure.GitMissing)
    }

    @Test
    fun `any other exit is a git error with its code`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        exec.on(STATUS) {
            stderr("fatal: detected dubious ownership\n")
            exit(128)
        }
        failedWith(GitFailure.GitError(128))
    }

    @Test
    fun `a process that ended without a code is a git error without one`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        exec.on(STATUS) { killedBy("TERM") }
        failedWith(GitFailure.GitError(null))
    }

    @Test
    fun `output that does not parse is unknown`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        exec.on(STATUS) {
            stdout("this is not porcelain")
            exit(0)
        }
        failedWith(GitFailure.Unknown)
    }

    @Test
    fun `a root that is not an absolute path is unknown`() = runTest(timeout = 10.seconds) {
        exec.on(TOPLEVEL) {
            stdout("relative\n")
            exit(0)
        }
        failedWith(GitFailure.Unknown)
    }

    @Test
    fun `a relative directory is rejected before anything runs`() = runTest(timeout = 10.seconds) {
        val presenter = DefaultStatusPresenter(directory = "relative/path", git = ExecGitConnection(exec), scope = backgroundScope)
        runCurrent()

        assertEquals(GitFailure.Rejected, presenter.state.value.error)
        assertTrue(exec.executions.isEmpty())
    }

    @Test
    fun `a command past its limit is timed out`() = runTest(timeout = 10.seconds) {
        exec.failNext(SshException.Timeout())
        failedWith(GitFailure.TimedOut)
    }

    @Test
    fun `output past the limit is too large`() = runTest(timeout = 10.seconds) {
        exec.failNext(SshException.OutputLimitExceeded(16))
        failedWith(GitFailure.OutputTooLarge)
    }

    @Test
    fun `a dropped connection is not connected`() = runTest(timeout = 10.seconds) {
        exec.disconnect()
        failedWith(GitFailure.NotConnected)
    }

    @Test
    fun `an unreachable server is unreachable`() = runTest(timeout = 10.seconds) {
        exec.failNext(SshException.ConnectionFailed())
        failedWith(GitFailure.Unreachable)
    }

    @Test
    fun `a failure of the connection port passes through`() = runTest(timeout = 10.seconds) {
        val git = object : GitConnection {
            override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = throw GitException(GitFailure.NotConnected)
        }
        val presenter = presenter(git)
        runCurrent()

        assertEquals(GitFailure.NotConnected, presenter.state.value.error)
    }

    @Test
    fun `retry after an error loads again`() = runTest(timeout = 10.seconds) {
        exec.failNext(SshException.Timeout())
        scriptRepository()
        val presenter = presenter()
        runCurrent()
        assertEquals(GitFailure.TimedOut, presenter.state.value.error)

        presenter.onIntent(StatusIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(4, presenter.state.value.staged.size)
    }

    // Refresh

    @Test
    fun `refresh keeps the rows until the new ones arrive`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(STATUS) {
            gate.await()
            stdout(statusOutput())
            exit(0)
        }

        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        val during = presenter.state.value
        assertTrue(during.refreshing)
        assertFalse(during.loading)
        assertEquals(4, during.staged.size)

        gate.complete(Unit)
        runCurrent()
        val after = presenter.state.value
        assertFalse(after.refreshing)
        assertTrue(after.isClean)
    }

    @Test
    fun `a refresh that fails shows the error alone`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.failNext(SshException.Disconnected())

        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        val state = presenter.state.value
        assertEquals(GitFailure.NotConnected, state.error)
        assertFalse(state.refreshing)
        assertEquals(0, state.changeCount)
    }

    @Test
    fun `a second refresh replaces the first`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val never = CompletableDeferred<Unit>()
        exec.on(STATUS) {
            never.await()
            exit(0)
        }
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        val first = exec.executions.last()
        exec.on(STATUS) {
            stdout(statusOutput())
            exit(0)
        }

        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(FakeProcessEnd.Killed, first.end)
        assertTrue(presenter.state.value.isClean)
    }

    @Test
    fun `cancelling the scope ends the running command and leaves no error`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val never = CompletableDeferred<Unit>()
        exec.on(STATUS) {
            never.await()
            exit(0)
        }
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val presenter = presenter(scope = scope)
        runCurrent()

        scope.cancel()
        runCurrent()

        assertEquals(FakeProcessEnd.Killed, exec.executions.last().end)
        assertNull(presenter.state.value.error)
    }

    // Stage and unstage

    @Test
    fun `staging an unstaged row adds its path from the root, then reads the status again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(add("unstaged.txt")) {
            gate.await()
            exit(0)
        }

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        assertEquals(setOf("unstaged.txt"), presenter.state.value.pending)
        runCurrent()
        assertEquals(setOf("unstaged.txt"), presenter.state.value.pending)

        statusBytes = statusOutput { record(changed("M.", "unstaged.txt")) }
        gate.complete(Unit)
        runCurrent()

        val state = presenter.state.value
        assertEquals(emptySet(), state.pending)
        assertEquals(listOf(GitFileChange("unstaged.txt", GitChangeKind.Modified)), state.staged)
        assertEquals(emptyList(), state.unstaged)
        assertNull(state.actionError)
        assertEquals(listOf(TOPLEVEL, STATUS, add("unstaged.txt"), TOPLEVEL, STATUS), commandsRun())
    }

    @Test
    fun `staging an untracked row with a hostile name adds exactly that path`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("-rf"), ok())

        presenter.onIntent(StatusIntent.Stage(GitFileChange("-rf", GitChangeKind.Untracked)))
        runCurrent()

        assertTrue(add("-rf") in commandsRun())
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `staging a conflicted row marks it resolved`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("conflict.txt"), ok())

        presenter.onIntent(StatusIntent.Stage(GitFileChange("conflict.txt", GitChangeKind.Unmerged)))
        runCurrent()

        assertTrue(add("conflict.txt") in commandsRun())
    }

    @Test
    fun `unstaging a row takes it out of the index`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(unstage("staged.txt"), ok())

        presenter.onIntent(StatusIntent.Unstage(GitFileChange("staged.txt", GitChangeKind.Modified)))
        runCurrent()

        assertTrue(unstage("staged.txt") in commandsRun())
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `unstaging a rename takes both of its paths out of the index`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(unstage("new name.txt", "old name.txt"), ok())

        presenter.onIntent(StatusIntent.Unstage(GitFileChange("new name.txt", GitChangeKind.Renamed, "old name.txt")))
        runCurrent()

        assertTrue(unstage("new name.txt", "old name.txt") in commandsRun())
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `stage all adds unstaged and untracked rows and leaves conflicts`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val command = add("unstaged.txt", "both.txt", "-rf")
        exec.on(command, ok())

        presenter.onIntent(StatusIntent.StageAll)
        assertEquals(setOf("unstaged.txt", "both.txt", "-rf"), presenter.state.value.pending)
        runCurrent()

        assertTrue(command in commandsRun())
        assertEquals(emptySet(), presenter.state.value.pending)
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `unstage all unstages every staged row, renames with both paths`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val command = unstage("staged.txt", "both.txt", "gone.txt", "new name.txt", "old name.txt")
        exec.on(command, ok())

        presenter.onIntent(StatusIntent.UnstageAll)
        runCurrent()

        assertTrue(command in commandsRun())
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `stage all and unstage all with nothing to do run nothing`() = runTest(timeout = 10.seconds) {
        val onlyStaged = loaded(statusOutput { record(changed("M.", "staged.txt")) })
        onlyStaged.onIntent(StatusIntent.StageAll)
        runCurrent()
        val onlyUntracked = loaded(statusOutput { record("? new.txt") })
        onlyUntracked.onIntent(StatusIntent.UnstageAll)
        runCurrent()

        assertEquals(listOf(TOPLEVEL, STATUS, TOPLEVEL, STATUS), commandsRun())
        assertEquals(emptySet(), onlyStaged.state.value.pending)
        assertEquals(emptySet(), onlyUntracked.state.value.pending)
    }

    @Test
    fun `stage all splits a long list into several commands that together add every path in order`() = runTest(timeout = 10.seconds) {
        val names = (1..3000).map { "folder-with-a-long-name/file-number-$it-with-padding.txt" }
        scriptRepository(statusOutput { names.forEach { record("? $it") } })
        val addPrefix = add("x").removeSuffix(" " + ShellQuote.quote("x"))
        val adds = mutableListOf<String>()
        val recording = object : RemoteExec by exec {
            override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult {
                if (!request.command.startsWith("$addPrefix ")) return exec.run(request, maxOutputBytes)
                adds += request.command
                return ExecResult(ByteArray(0), ByteArray(0), 0, null)
            }
        }
        val presenter = presenter(ExecGitConnection(recording))
        runCurrent()

        presenter.onIntent(StatusIntent.StageAll)
        runCurrent()

        assertTrue(adds.size > 1, "expected several add commands, got ${adds.size}")
        var remaining = names
        for (command in adds) {
            val count = (1..remaining.size).firstOrNull { add(*remaining.take(it).toTypedArray()) == command }
            assertTrue(count != null, "an add command is not the next run of paths")
            remaining = remaining.drop(count)
        }
        assertEquals(emptyList(), remaining)
        assertNull(presenter.state.value.actionError)
        assertEquals(emptySet(), presenter.state.value.pending)
    }

    @Test
    fun `a failed stage keeps the rows and shows the error until dismissed`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = presenter.state.value
        exec.on(add("unstaged.txt")) {
            stderr("fatal: Unable to create index.lock: File exists.\n")
            exit(128)
        }

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        runCurrent()

        val state = presenter.state.value
        assertEquals(GitFailure.GitError(128), state.actionError)
        assertEquals(before.staged, state.staged)
        assertEquals(before.unstaged, state.unstaged)
        assertEquals(emptySet(), state.pending)
        assertNull(state.error)

        presenter.onIntent(StatusIntent.DismissError)
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `a path gone since the list was read is not found`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("unstaged.txt")) {
            stderr("fatal: pathspec 'unstaged.txt' did not match any files\n")
            exit(128)
        }

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        runCurrent()

        assertEquals(GitFailure.NotFound, presenter.state.value.actionError)
    }

    @Test
    fun `a dropped connection during a stage is an action error`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.failNext(SshException.Disconnected())

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        runCurrent()

        assertEquals(GitFailure.NotConnected, presenter.state.value.actionError)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `a row that is not in the list, or not in the right group, is ignored`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unknown.txt", GitChangeKind.Modified)))
        presenter.onIntent(StatusIntent.Stage(GitFileChange("staged.txt", GitChangeKind.Modified)))
        presenter.onIntent(StatusIntent.Unstage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        presenter.onIntent(StatusIntent.Unstage(GitFileChange("conflict.txt", GitChangeKind.Unmerged)))
        runCurrent()

        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
        assertEquals(emptySet(), presenter.state.value.pending)
    }

    @Test
    fun `a pending row ignores another stage`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(add("unstaged.txt")) {
            gate.await()
            exit(0)
        }
        val row = GitFileChange("unstaged.txt", GitChangeKind.Modified)

        presenter.onIntent(StatusIntent.Stage(row))
        presenter.onIntent(StatusIntent.Stage(row))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, commandsRun().count { it == add("unstaged.txt") })
    }

    @Test
    fun `stage and unstage are ignored before the status is loaded`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val presenter = presenter()

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        presenter.onIntent(StatusIntent.StageAll)
        presenter.onIntent(StatusIntent.UnstageAll)
        runCurrent()

        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
    }

    @Test
    fun `actions run one at a time`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(add("unstaged.txt")) {
            gate.await()
            exit(0)
        }
        exec.on(unstage("staged.txt"), ok())

        presenter.onIntent(StatusIntent.Stage(GitFileChange("unstaged.txt", GitChangeKind.Modified)))
        presenter.onIntent(StatusIntent.Unstage(GitFileChange("staged.txt", GitChangeKind.Modified)))
        runCurrent()
        assertFalse(unstage("staged.txt") in commandsRun())
        assertEquals(setOf("unstaged.txt", "staged.txt"), presenter.state.value.pending)

        gate.complete(Unit)
        runCurrent()

        assertTrue(unstage("staged.txt") in commandsRun())
        assertEquals(emptySet(), presenter.state.value.pending)
    }

    @Test
    fun `a failed stage that applied in part is followed by a fresh read`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val command = add("unstaged.txt", "both.txt", "-rf")
        exec.on(command) {
            // git staged two of the paths, then stopped on the third.
            statusBytes = statusOutput {
                record(changed("M.", "unstaged.txt"))
                record(changed("M.", "both.txt"))
                record("? -rf")
            }
            stderr("error: unable to index file\nfatal: adding files failed\n")
            exit(128)
        }

        presenter.onIntent(StatusIntent.StageAll)
        runCurrent()

        val state = presenter.state.value
        assertEquals(listOf(TOPLEVEL, STATUS, command, TOPLEVEL, STATUS), commandsRun())
        assertEquals(GitFailure.GitError(128), state.actionError)
        assertEquals(
            listOf(GitFileChange("unstaged.txt", GitChangeKind.Modified), GitFileChange("both.txt", GitChangeKind.Modified)),
            state.staged,
        )
        assertEquals(emptyList(), state.unstaged)
        assertEquals(listOf(GitFileChange("-rf", GitChangeKind.Untracked)), state.untracked)
        assertEquals(emptySet(), state.pending)
    }

    @Test
    fun `after a failed refresh a stale row opens no diff and stages nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(withBadPath())
        val stale = GitFileChange(badText, GitChangeKind.Untracked)
        exec.failNext(SshException.Disconnected())
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        assertEquals(GitFailure.NotConnected, presenter.state.value.error)

        presenter.events.test {
            presenter.onIntent(StatusIntent.OpenDiff(stale, staged = false))
            presenter.onIntent(StatusIntent.OpenDiff(GitFileChange("good.txt", GitChangeKind.Untracked), staged = false))
            presenter.onIntent(StatusIntent.Stage(stale))
            presenter.onIntent(StatusIntent.Unstage(GitFileChange("staged.txt", GitChangeKind.Modified)))
            runCurrent()
            expectNoEvents()
        }
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
        assertEquals(emptySet(), presenter.state.value.pending)
    }

    @Test
    fun `before the first read a diff is not opened`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val presenter = presenter()

        presenter.events.test {
            presenter.onIntent(StatusIntent.OpenDiff(GitFileChange("staged.txt", GitChangeKind.Modified), staged = true))
            expectNoEvents()
        }
    }

    // Races between a refresh and an action

    @Test
    fun `a row seen before a refresh that removed it is ignored`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val stale = GitFileChange("unstaged.txt", GitChangeKind.Modified)
        statusBytes = statusOutput()
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        presenter.onIntent(StatusIntent.Stage(stale))
        runCurrent()

        assertFalse(add("unstaged.txt") in commandsRun())
        assertEquals(emptySet(), presenter.state.value.pending)
    }

    @Test
    fun `a row whose text a refresh made ambiguous is checked against the new read and refused`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(statusOutput { record("? $badText") })
        val row = GitFileChange(badText, GitChangeKind.Untracked)
        // The same text now also names a path that is not UTF-8.
        statusBytes = statusOutput {
            record("? ".toByteArray() + badBytes)
            record("? $badText")
        }
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        presenter.onIntent(StatusIntent.Stage(row))
        runCurrent()

        assertEquals(GitFailure.Rejected, presenter.state.value.actionError)
        assertFalse(commandsRun().any { it.startsWith(add("x").removeSuffix(" " + ShellQuote.quote("x"))) })
    }

    @Test
    fun `under racing refreshes every stage uses the root of the read its row came from`() = runBlocking {
        // Each read alternates between two repositories: /a with the row a.txt and /b with b.txt.
        // A stage that checked its row against one read and took the root of another would run
        // `add` with a mixed pair, such as a.txt in /b.
        val toplevel = GitCommands.revParseToplevel(DIR).commandLine
        val reads = AtomicInteger()
        val adds = Collections.synchronizedList(mutableListOf<String>())
        val racing = object : RemoteExec by exec {
            override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult {
                val command = request.command
                val out: String = when {
                    command == toplevel -> if (reads.incrementAndGet() % 2 == 0) "/a\n" else "/b\n"
                    command == GitCommands.status("/a").commandLine -> String(statusOutput { record("? a.txt") }, Charsets.UTF_8)
                    command == GitCommands.status("/b").commandLine -> String(statusOutput { record("? b.txt") }, Charsets.UTF_8)
                    else -> {
                        adds += command
                        ""
                    }
                }
                return ExecResult(out.toByteArray(Charsets.UTF_8), ByteArray(0), 0, null)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val presenter = DefaultStatusPresenter(DIR, ExecGitConnection(racing), scope)
            withTimeout(20.seconds) { presenter.state.first { !it.loading } }
            val rowA = GitFileChange("a.txt", GitChangeKind.Untracked)
            val rowB = GitFileChange("b.txt", GitChangeKind.Untracked)
            withTimeout(20.seconds) {
                repeat(2000) {
                    presenter.onIntent(StatusIntent.Refresh)
                    presenter.onIntent(StatusIntent.Stage(rowA))
                    presenter.onIntent(StatusIntent.Stage(rowB))
                    if (it % 50 == 0) yield()
                }
                presenter.state.first { it.pending.isEmpty() && !it.refreshing }
            }
            val allowed = setOf(GitCommands.add("/a", listOf("a.txt")).commandLine, GitCommands.add("/b", listOf("b.txt")).commandLine)
            val mixed = adds.toList().filter { it !in allowed }
            assertTrue(adds.isNotEmpty(), "no stage ran")
            assertTrue(mixed.isEmpty(), "${mixed.size} of ${adds.size} stages mixed a row with another read's root")
        } finally {
            scope.cancel()
        }
    }

    // Paths that are not UTF-8

    private val badBytes = "bad".toByteArray() + byteArrayOf(0xFF.toByte()) + ".txt".toByteArray()
    private val badText = String(badBytes, Charsets.UTF_8)

    private fun withBadPath() = statusOutput {
        record("? ".toByteArray() + badBytes)
        record("? good.txt")
        record(changed("M.", "staged.txt"))
        record("1 M. N... 100644 100644 100644 $HASH $HASH ".toByteArray() + badBytes + "-staged".toByteArray())
    }

    @Test
    fun `a path that is not UTF-8 is shown with replacement characters`() = runTest(timeout = 10.seconds) {
        val state = loaded(withBadPath()).state.value

        assertEquals(listOf(GitFileChange(badText, GitChangeKind.Untracked), GitFileChange("good.txt", GitChangeKind.Untracked)), state.untracked)
        assertTrue('�' in state.untracked.first().path)
    }

    @Test
    fun `a row whose path is not UTF-8 is refused, since its bytes cannot be passed back`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(withBadPath())

        presenter.onIntent(StatusIntent.Stage(GitFileChange(badText, GitChangeKind.Untracked)))
        runCurrent()
        assertEquals(GitFailure.Rejected, presenter.state.value.actionError)

        presenter.onIntent(StatusIntent.Unstage(GitFileChange("$badText-staged", GitChangeKind.Modified)))
        runCurrent()
        assertEquals(GitFailure.Rejected, presenter.state.value.actionError)
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
        assertEquals(emptySet(), presenter.state.value.pending)
    }

    @Test
    fun `stage all stages what it can and reports the paths it could not`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(withBadPath())
        exec.on(add("good.txt"), ok())

        presenter.onIntent(StatusIntent.StageAll)
        runCurrent()

        assertTrue(add("good.txt") in commandsRun())
        assertEquals(GitFailure.Rejected, presenter.state.value.actionError)
    }

    @Test
    fun `a valid path that shares its text with an invalid one is refused too`() = runTest(timeout = 10.seconds) {
        val output = statusOutput {
            record("? ".toByteArray() + badBytes)
            record("? $badText")
        }
        val presenter = loaded(output)

        presenter.onIntent(StatusIntent.Stage(GitFileChange(badText, GitChangeKind.Untracked)))
        runCurrent()

        assertEquals(GitFailure.Rejected, presenter.state.value.actionError)
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
    }

    @Test
    fun `opening the diff of a path that is not UTF-8 is refused`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(withBadPath())

        presenter.events.test {
            presenter.onIntent(StatusIntent.OpenDiff(GitFileChange(badText, GitChangeKind.Untracked), staged = false))
            expectNoEvents()
        }
        assertEquals(GitFailure.Rejected, presenter.state.value.actionError)
    }

    // Navigation

    @Test
    fun `navigation intents send their events once each`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.events.test {
            presenter.onIntent(StatusIntent.OpenDiff(GitFileChange("staged.txt", GitChangeKind.Modified), staged = true))
            assertEquals(StatusEvent.OpenDiff("staged.txt", staged = true), awaitItem())
            presenter.onIntent(StatusIntent.OpenDiff(GitFileChange("unstaged.txt", GitChangeKind.Modified), staged = false))
            assertEquals(StatusEvent.OpenDiff("unstaged.txt", staged = false), awaitItem())
            presenter.onIntent(StatusIntent.OpenCommit)
            assertEquals(StatusEvent.OpenCommit, awaitItem())
            presenter.onIntent(StatusIntent.OpenLog)
            assertEquals(StatusEvent.OpenLog, awaitItem())
            presenter.onIntent(StatusIntent.OpenBranches)
            assertEquals(StatusEvent.OpenBranches, awaitItem())
            presenter.onIntent(StatusIntent.OpenRemote)
            assertEquals(StatusEvent.OpenRemote, awaitItem())
            presenter.onIntent(StatusIntent.Close)
            assertEquals(StatusEvent.Close, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `an event sent while nobody collects waits for the next collector`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(StatusIntent.OpenCommit)

        presenter.events.test {
            assertEquals(StatusEvent.OpenCommit, awaitItem())
        }
    }

    @Test
    fun `printing the state shows no path or branch name`() = runTest(timeout = 10.seconds) {
        val text = loaded().state.value.toString()

        listOf("staged.txt", "main", "origin", "çalışma", "-rf").forEach { assertFalse(it in text, it) }
    }
}
