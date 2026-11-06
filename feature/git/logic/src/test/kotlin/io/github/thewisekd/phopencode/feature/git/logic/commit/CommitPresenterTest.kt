package io.github.thewisekd.phopencode.feature.git.logic.commit

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcess
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcessEnd
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitMessageProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private const val DIR = "/srv/çalışma alanı/sub"
private const val ROOT = "/srv/çalışma alanı"
private val OID = "a".repeat(40)
private val BLOB = "b".repeat(40)
private val NEW_HEAD = "c".repeat(40)

private fun ByteArrayOutputStream.record(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
    write(0)
}

private fun changed(xy: String, path: String) = "1 $xy N... 100644 100644 100644 $BLOB $BLOB $path"

private fun statusOutput(build: ByteArrayOutputStream.() -> Unit = {}): ByteArray = ByteArrayOutputStream().apply {
    record("# branch.oid $OID")
    record("# branch.head main")
    build()
}.toByteArray()

/** A row in every group the commit screen shows. */
private val MIXED = statusOutput {
    record(changed("M.", "staged.txt"))
    record(changed(".M", "unstaged.txt"))
    record(changed(".D", "deleted.txt"))
    record("2 R. N... 100644 100644 100644 $BLOB $BLOB R100 new name.txt")
    record("old name.txt")
    record("u UU N... 100644 100644 100644 100644 $BLOB $BLOB $BLOB conflict.txt")
    record("? -rf")
}

private val STAGED = GitFileChange("staged.txt", GitChangeKind.Modified)
private val RENAMED = GitFileChange("new name.txt", GitChangeKind.Renamed, "old name.txt")
private val UNSTAGED = GitFileChange("unstaged.txt", GitChangeKind.Modified)
private val DELETED = GitFileChange("deleted.txt", GitChangeKind.Deleted)
private val CONFLICT = GitFileChange("conflict.txt", GitChangeKind.Unmerged)
private val UNTRACKED = GitFileChange("-rf", GitChangeKind.Untracked)

private val TOPLEVEL = GitCommands.revParseToplevel(DIR).commandLine
private val STATUS = GitCommands.status(ROOT).commandLine
private val HEAD = GitCommands.log(ROOT, "%H", maxCount = 1).commandLine

private fun add(vararg paths: String) = GitCommands.add(ROOT, paths.toList()).commandLine

private fun unstage(vararg paths: String) = GitCommands.unstage(ROOT, paths.toList()).commandLine

private fun commit(message: String) = GitCommands.commit(ROOT, message).commandLine

/** Runs each call to the end even when its coroutine is cancelled, as a slow server may. */
private class UncancellableConnection(private val inner: GitConnection) : GitConnection {
    override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = withContext(NonCancellable) { inner.withExec(block) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CommitPresenterTest {
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
        exec.on(HEAD) {
            stdout("$NEW_HEAD\u0000")
            exit(0)
        }
    }

    private fun TestScope.presenter(scope: CoroutineScope = backgroundScope, git: GitConnection = ExecGitConnection(exec)) =
        DefaultCommitPresenter(directory = DIR, git = git, scope = scope)

    private fun TestScope.loaded(output: ByteArray = MIXED): DefaultCommitPresenter {
        scriptRepository(output)
        val presenter = presenter()
        runCurrent()
        assertFalse(presenter.state.value.loading)
        return presenter
    }

    private fun commandsRun(): List<String> = exec.executions.map { it.command }

    private fun exitWith(code: Int, stdout: String = "", stderr: String = ""): suspend FakeProcess.() -> ExecEvent.Exited = {
        if (stdout.isNotEmpty()) stdout(stdout)
        if (stderr.isNotEmpty()) stderr(stderr)
        exit(code)
    }

    // Loading

    @Test
    fun `the first frame shows the initial state and nothing runs before the scope does`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val presenter = presenter()

        assertEquals(CommitState(directory = DIR), presenter.state.value)
        assertTrue(exec.executions.isEmpty())
    }

    @Test
    fun `loading resolves the root and shows staged and unstaged rows`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        val state = presenter.state.value
        assertNull(state.error)
        assertEquals(listOf(STAGED, RENAMED), state.staged)
        assertEquals(listOf(CONFLICT, UNSTAGED, DELETED, UNTRACKED), state.unstaged)
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun())
    }

    @Test
    fun `a folder outside a repository shows the failure and refresh tries again`() = runTest(timeout = 10.seconds) {
        exec.on(TOPLEVEL, exitWith(128, stderr = "fatal: not a git repository (or any of the parent directories): .git\n"))
        val presenter = presenter()
        runCurrent()

        val failed = presenter.state.value
        assertFalse(failed.loading)
        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.NotRepository), failed.error)

        scriptRepository()
        presenter.onIntent(CommitIntent.Refresh)
        assertTrue(presenter.state.value.loading)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(listOf(STAGED, RENAMED), presenter.state.value.staged)
    }

    @Test
    fun `a missing git is GitMissing`() = runTest(timeout = 10.seconds) {
        // FakeRemoteExec answers an unscripted command with exit 127, as a shell does.
        val presenter = presenter()
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.GitMissing), presenter.state.value.error)
    }

    @Test
    fun `a lost connection while loading is NotConnected`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        exec.failNext(SshException.Disconnected())
        val presenter = presenter()
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.NotConnected), presenter.state.value.error)
    }

    @Test
    fun `refresh keeps the message`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(CommitIntent.EditMessage("Fix it"))
        statusBytes = statusOutput()

        presenter.onIntent(CommitIntent.Refresh)
        runCurrent()

        assertEquals("Fix it", presenter.state.value.message)
        assertEquals(emptyList(), presenter.state.value.staged)
    }

    // Message

    @Test
    fun `editing the message clears the problem`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(CommitIntent.Commit)
        assertEquals(CommitMessageProblem.Blank, presenter.state.value.messageProblem)

        presenter.onIntent(CommitIntent.EditMessage("x"))

        assertEquals("x", presenter.state.value.message)
        assertNull(presenter.state.value.messageProblem)
    }

    @Test
    fun `a blank message is refused before any command`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun()

        for (blank in listOf("", "   ", "\n\t \n", " ")) {
            presenter.onIntent(CommitIntent.EditMessage(blank))
            presenter.onIntent(CommitIntent.Commit)
            runCurrent()
            assertEquals(CommitMessageProblem.Blank, presenter.state.value.messageProblem)
        }

        assertEquals(before, commandsRun())
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `committing with nothing staged runs nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(statusOutput { record(changed(".M", "unstaged.txt")) })
        val before = commandsRun()
        presenter.onIntent(CommitIntent.EditMessage("Fix"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.NothingStaged, presenter.state.value.error)
        assertEquals(before, commandsRun())
    }

    // Commit

    @Test
    fun `a commit sends the message on stdin, reports the new hash and clears the message`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val message = "Düzelt: ığüşöç İ\n\n$(touch pwned) `x` 'q'"
        val gate = CompletableDeferred<Unit>()
        exec.on(commit(message)) {
            gate.await()
            exit(0)
        }

        presenter.events.test {
            presenter.onIntent(CommitIntent.EditMessage(message))
            presenter.onIntent(CommitIntent.Commit)
            assertEquals(CommitOp.Commit, presenter.state.value.busy)
            runCurrent()
            assertEquals(CommitOp.Commit, presenter.state.value.busy)

            statusBytes = statusOutput { record(changed(".M", "unstaged.txt")) }
            gate.complete(Unit)
            runCurrent()

            assertEquals(CommitEvent.Committed(NEW_HEAD), awaitItem())
        }
        val state = presenter.state.value
        assertNull(state.busy)
        assertNull(state.error)
        assertEquals("", state.message)
        assertEquals(emptyList(), state.staged)
        val run = exec.executions.single { it.stdin != null }
        assertContentEquals(message.toByteArray(Charsets.UTF_8), run.stdin)
        assertFalse(message in run.command)
        assertFalse("touch" in run.command)
        assertEquals(listOf(commit(message), HEAD, TOPLEVEL, STATUS), commandsRun().drop(2))
    }

    @Test
    fun `a message edited while committing is kept`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(commit("First")) {
            gate.await()
            exit(0)
        }
        presenter.onIntent(CommitIntent.EditMessage("First"))
        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        presenter.onIntent(CommitIntent.EditMessage("Second"))
        gate.complete(Unit)
        runCurrent()

        assertEquals("Second", presenter.state.value.message)
    }

    @Test
    fun `a failing hook shows its output and keeps the message`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(commit("Fix"), exitWith(1, stderr = "lint: 3 problems in staged.txt\n"))

        presenter.events.test {
            presenter.onIntent(CommitIntent.EditMessage("Fix"))
            presenter.onIntent(CommitIntent.Commit)
            runCurrent()
            expectNoEvents()
        }

        val state = presenter.state.value
        assertEquals(CommitError.HookRejected("lint: 3 problems in staged.txt"), state.error)
        assertEquals("Fix", state.message)
        assertNull(state.busy)
        // The hook may have changed files: the status is read again.
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun().takeLast(2))
    }

    @Test
    fun `a hook that prints nothing is still a hook rejection`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(commit("Fix"), exitWith(1))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.HookRejected(""), presenter.state.value.error)
    }

    @Test
    fun `long hook output is cut`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val long = "ş".repeat(DefaultCommitPresenter.MAX_HOOK_OUTPUT_CHARS * 2)
        exec.on(commit("Fix"), exitWith(1, stderr = long))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        val error = presenter.state.value.error as CommitError.HookRejected
        assertTrue(error.output.length <= DefaultCommitPresenter.MAX_HOOK_OUTPUT_CHARS + 1)
        assertTrue(long.startsWith(error.output.removeSuffix("…")))
    }

    @Test
    fun `nothing to commit after a stale read is NothingStaged`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(commit("Fix"), exitWith(1, stdout = "On branch main\nnothing to commit, working tree clean\n"))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.NothingStaged, presenter.state.value.error)
    }

    @Test
    fun `unmerged files make the commit a conflict`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(
            commit("Fix"),
            exitWith(
                128,
                stderr = "error: Committing is not possible because you have unmerged files.\n" +
                    "hint: Fix them up in the work tree.\nfatal: Exiting because of an unresolved conflict.\n",
            ),
        )
        presenter.onIntent(CommitIntent.EditMessage("Fix"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.Conflict), presenter.state.value.error)
    }

    @Test
    fun `other commit failures keep git's exit code`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(commit("Fix"), exitWith(128, stderr = "fatal: Unable to create '/srv/x/.git/index.lock': File exists.\n"))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.GitError(128)), presenter.state.value.error)
        assertEquals("Fix", presenter.state.value.message)
    }

    @Test
    fun `a message git cannot take is rejected without running commit`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun()
        presenter.onIntent(CommitIntent.EditMessage("bad\u0000byte"))

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.Rejected), presenter.state.value.error)
        assertEquals(before, commandsRun())
    }

    @Test
    fun `a commit that times out is TimedOut and the status is read again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        exec.failNext(SshException.Timeout())

        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.TimedOut), presenter.state.value.error)
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun().takeLast(2))
    }

    @Test
    fun `dismissing clears the error`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(commit("Fix"), exitWith(1, stderr = "no\n"))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        presenter.onIntent(CommitIntent.DismissError)

        assertNull(presenter.state.value.error)
    }

    // Stage and unstage

    @Test
    fun `staging a row adds its path from the root, then reads the status again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(add("unstaged.txt")) {
            gate.await()
            exit(0)
        }

        presenter.onIntent(CommitIntent.Stage(UNSTAGED))
        assertEquals(CommitOp.Stage, presenter.state.value.busy)
        assertFalse(presenter.state.value.canCommit)
        runCurrent()
        statusBytes = statusOutput { record(changed("M.", "unstaged.txt")) }
        gate.complete(Unit)
        runCurrent()

        val state = presenter.state.value
        assertNull(state.busy)
        assertNull(state.error)
        assertEquals(listOf(UNSTAGED), state.staged)
        assertEquals(listOf(add("unstaged.txt"), TOPLEVEL, STATUS), commandsRun().drop(2))
    }

    @Test
    fun `staging an untracked file with a dash name passes it as a path`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("-rf"), exitWith(0))

        presenter.onIntent(CommitIntent.Stage(UNTRACKED))
        runCurrent()

        assertTrue(add("-rf") in commandsRun())
        assertTrue(add("-rf").endsWith(" '--' '-rf'"))
    }

    @Test
    fun `unstaging a rename moves both of its paths`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(unstage("new name.txt", "old name.txt"), exitWith(0))

        presenter.onIntent(CommitIntent.Unstage(RENAMED))
        runCurrent()

        assertTrue(unstage("new name.txt", "old name.txt") in commandsRun())
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `stage all takes every unstaged row but the conflicted one`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("unstaged.txt", "deleted.txt", "-rf"), exitWith(0))

        presenter.onIntent(CommitIntent.StageAll)
        runCurrent()

        assertTrue(add("unstaged.txt", "deleted.txt", "-rf") in commandsRun())
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `unstage all takes every staged row`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(unstage("staged.txt", "new name.txt", "old name.txt"), exitWith(0))

        presenter.onIntent(CommitIntent.UnstageAll)
        runCurrent()

        assertTrue(unstage("staged.txt", "new name.txt", "old name.txt") in commandsRun())
    }

    @Test
    fun `a row that is not shown is ignored`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun()

        presenter.onIntent(CommitIntent.Stage(GitFileChange("elsewhere.txt", GitChangeKind.Modified)))
        presenter.onIntent(CommitIntent.Unstage(UNSTAGED))
        runCurrent()

        assertEquals(before, commandsRun())
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `a stage that fails shows the failure and reads the status again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("unstaged.txt"), exitWith(128, stderr = "fatal: pathspec 'unstaged.txt' did not match any files\n"))

        presenter.onIntent(CommitIntent.Stage(UNSTAGED))
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Stage, GitFailure.NotFound), presenter.state.value.error)
        assertNull(presenter.state.value.busy)
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun().takeLast(2))
    }

    @Test
    fun `nothing else starts while an action runs`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(add("unstaged.txt")) {
            gate.await()
            exit(0)
        }
        presenter.onIntent(CommitIntent.Stage(UNSTAGED))
        runCurrent()

        presenter.onIntent(CommitIntent.Stage(DELETED))
        presenter.onIntent(CommitIntent.Unstage(STAGED))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        presenter.onIntent(CommitIntent.Commit)
        presenter.onIntent(CommitIntent.RequestDiscard(DELETED))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf(add("unstaged.txt"), TOPLEVEL, STATUS), commandsRun().drop(2))
        assertNull(presenter.state.value.confirmDiscard)
    }

    @Test
    fun `a failed stage of a later piece still reads the status again`() = runTest(timeout = 10.seconds) {
        val many = statusOutput {
            repeat(3) { record(changed(".M", "f$it-" + "x".repeat(DefaultCommitPresenter.MAX_PATH_BYTES_PER_COMMAND / 2))) }
        }
        val presenter = loaded(many)
        val rows = presenter.state.value.unstaged.map { it.path }
        exec.on(add(rows[0]), exitWith(0))
        exec.on(add(rows[1]), exitWith(128, stderr = "fatal: something\n"))

        presenter.onIntent(CommitIntent.StageAll)
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Stage, GitFailure.GitError(128)), presenter.state.value.error)
        assertFalse(add(rows[2]) in commandsRun())
        assertEquals(listOf(TOPLEVEL, STATUS), commandsRun().takeLast(2))
    }

    @Test
    fun `a failed read after a stage blocks every row action until a refresh succeeds`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(add("unstaged.txt"), exitWith(0))
        exec.on(STATUS, exitWith(128, stderr = "fatal: something\n"))

        presenter.onIntent(CommitIntent.Stage(UNSTAGED))
        runCurrent()
        assertEquals(CommitError.Failed(CommitOp.Stage, GitFailure.GitError(128)), presenter.state.value.error)
        assertNull(presenter.state.value.busy)
        val before = commandsRun()

        presenter.onIntent(CommitIntent.RequestDiscard(DELETED))
        presenter.onIntent(CommitIntent.ConfirmDiscard)
        presenter.onIntent(CommitIntent.Stage(DELETED))
        presenter.onIntent(CommitIntent.Unstage(STAGED))
        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        presenter.onIntent(CommitIntent.Commit)
        runCurrent()

        assertNull(presenter.state.value.confirmDiscard)
        assertNull(presenter.state.value.busy)
        assertEquals(before, commandsRun())

        exec.on(STATUS) {
            stdout(statusBytes)
            exit(0)
        }
        presenter.onIntent(CommitIntent.Refresh)
        runCurrent()
        assertNull(presenter.state.value.error)

        presenter.onIntent(CommitIntent.RequestDiscard(DELETED))
        assertEquals(DELETED, presenter.state.value.confirmDiscard)
        presenter.onIntent(CommitIntent.CancelDiscard)
        exec.on(add("deleted.txt"), exitWith(0))
        presenter.onIntent(CommitIntent.Stage(DELETED))
        runCurrent()
        assertTrue(add("deleted.txt") in commandsRun())
    }

    /** A file that is unstaged, then found conflicted: a merge started behind the screen's back. */
    private val beforeMerge = statusOutput {
        record(changed(".M", "conflict.txt"))
        record(changed(".M", "unstaged.txt"))
    }

    private val afterMerge = statusOutput {
        record("u UU N... 100644 100644 100644 100644 $BLOB $BLOB $BLOB conflict.txt")
        record(changed(".M", "unstaged.txt"))
    }

    /** Status calls so far, and per call number a gate to wait on and the output to print. */
    private var statusCalls = 0
    private val statusGates = HashMap<Int, CompletableDeferred<Unit>>()
    private val statusOutputs = HashMap<Int, ByteArray>()

    private fun scriptNumberedStatus() {
        exec.on(STATUS) {
            val call = ++statusCalls
            statusGates[call]?.await()
            stdout(statusOutputs[call] ?: statusBytes)
            exit(0)
        }
    }

    private fun anyAdd(): Boolean = commandsRun().any { "'add'" in it }

    private fun stagedConflictFile(): Boolean = commandsRun().any { "'add'" in it && "conflict.txt" in it }

    @Test
    fun `an old read that finishes after a newer refresh started publishes nothing`() = runTest(timeout = 10.seconds) {
        scriptRepository(beforeMerge)
        scriptNumberedStatus()
        exec.on(add("unstaged.txt"), exitWith(0))
        // Cancelling the old read does not stop it: its result arrives after the newer refresh began.
        val presenter = presenter(git = UncancellableConnection(ExecGitConnection(exec)))
        runCurrent()
        assertEquals(1, statusCalls)
        val oldRead = CompletableDeferred<Unit>().also { statusGates[2] = it }
        val newRead = CompletableDeferred<Unit>().also { statusGates[3] = it }
        statusOutputs[2] = beforeMerge
        statusOutputs[3] = afterMerge
        statusBytes = afterMerge

        presenter.onIntent(CommitIntent.Refresh)
        runCurrent()
        presenter.onIntent(CommitIntent.Refresh)
        presenter.onIntent(CommitIntent.StageAll)
        runCurrent()
        assertTrue(presenter.state.value.loading)

        oldRead.complete(Unit)
        runCurrent()
        assertEquals(3, statusCalls)
        assertTrue(presenter.state.value.loading)
        assertFalse(anyAdd())

        newRead.complete(Unit)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.busy)
        assertEquals(listOf(CONFLICT, UNSTAGED), state.unstaged)
        assertFalse(stagedConflictFile())
        assertTrue(add("unstaged.txt") in commandsRun())
    }

    @Test
    fun `a refresh that takes the lock before a queued stage all decides what it stages`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(beforeMerge)
        scriptNumberedStatus()
        val gate = CompletableDeferred<Unit>().also { statusGates[1] = it }
        statusOutputs[1] = afterMerge
        statusBytes = afterMerge
        exec.on(add("unstaged.txt"), exitWith(0))

        presenter.onIntent(CommitIntent.Refresh)
        presenter.onIntent(CommitIntent.StageAll)
        presenter.onIntent(CommitIntent.RequestDiscard(GitFileChange("conflict.txt", GitChangeKind.Modified)))
        runCurrent()
        assertTrue(presenter.state.value.loading)
        assertEquals(CommitOp.Stage, presenter.state.value.busy)
        assertNull(presenter.state.value.confirmDiscard)
        assertFalse(anyAdd())

        gate.complete(Unit)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.busy)
        assertFalse(stagedConflictFile())
        assertTrue(add("unstaged.txt") in commandsRun())
    }

    @Test
    fun `a stage all that takes the lock while a refresh is still asked for runs nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(beforeMerge)
        scriptNumberedStatus()
        statusOutputs[1] = afterMerge
        statusBytes = afterMerge

        presenter.onIntent(CommitIntent.StageAll)
        presenter.onIntent(CommitIntent.Refresh)
        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.busy)
        assertEquals(listOf(CONFLICT, UNSTAGED), state.unstaged)
        assertFalse(anyAdd())
    }

    @Test
    fun `a stage of a row that became conflicted runs nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded(beforeMerge)
        scriptNumberedStatus()
        statusOutputs[1] = afterMerge
        statusBytes = afterMerge
        val stale = GitFileChange("conflict.txt", GitChangeKind.Modified)

        presenter.onIntent(CommitIntent.Refresh)
        presenter.onIntent(CommitIntent.Stage(stale))
        runCurrent()

        assertNull(presenter.state.value.busy)
        assertFalse(stagedConflictFile())
    }

    @Test
    fun `a confirmation waits while a refresh runs`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(STATUS) {
            gate.await()
            stdout(statusBytes)
            exit(0)
        }
        presenter.onIntent(CommitIntent.RequestDiscard(UNSTAGED))
        presenter.onIntent(CommitIntent.Refresh)
        val before = commandsRun().size

        presenter.onIntent(CommitIntent.ConfirmDiscard)
        assertEquals(UNSTAGED, presenter.state.value.confirmDiscard)
        assertNull(presenter.state.value.busy)
        gate.complete(Unit)
        runCurrent()
        assertEquals(UNSTAGED, presenter.state.value.confirmDiscard)

        presenter.onIntent(CommitIntent.ConfirmDiscard)
        runCurrent()

        assertTrue(commandsRun().drop(before + 2).first().contains("'--ignored=matching'"))
    }

    // Discard

    @Test
    fun `a discard waits for the confirmation`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun()

        presenter.onIntent(CommitIntent.RequestDiscard(UNSTAGED))
        runCurrent()

        assertEquals(UNSTAGED, presenter.state.value.confirmDiscard)
        assertEquals(before, commandsRun())

        presenter.onIntent(CommitIntent.CancelDiscard)
        runCurrent()

        assertNull(presenter.state.value.confirmDiscard)
        assertEquals(before, commandsRun())
    }

    @Test
    fun `confirming runs the checked discard`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun().size
        presenter.onIntent(CommitIntent.RequestDiscard(DELETED))

        presenter.onIntent(CommitIntent.ConfirmDiscard)
        assertNull(presenter.state.value.confirmDiscard)
        assertEquals(CommitOp.Discard, presenter.state.value.busy)
        runCurrent()

        // The discard check is not scripted, so the fake shell answers 127.
        val ran = commandsRun().drop(before)
        assertTrue(ran.first().contains("'--ignored=matching'"))
        assertFalse(ran.any { "checkout-index" in it })
        assertEquals(CommitError.Failed(CommitOp.Discard, GitFailure.GitMissing), presenter.state.value.error)
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `confirming without a request does nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun()

        presenter.onIntent(CommitIntent.ConfirmDiscard)
        runCurrent()

        assertEquals(before, commandsRun())
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `untracked, unmerged and staged rows are never discarded`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val before = commandsRun()

        for (row in listOf(UNTRACKED, CONFLICT)) {
            presenter.onIntent(CommitIntent.RequestDiscard(row))
            assertNull(presenter.state.value.confirmDiscard)
            assertEquals(CommitError.Failed(CommitOp.Discard, GitFailure.Rejected), presenter.state.value.error)
            presenter.onIntent(CommitIntent.DismissError)
        }
        presenter.onIntent(CommitIntent.RequestDiscard(STAGED))
        presenter.onIntent(CommitIntent.ConfirmDiscard)
        runCurrent()

        assertNull(presenter.state.value.confirmDiscard)
        assertEquals(before, commandsRun())
    }

    @Test
    fun `a confirmation whose row went away runs nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(CommitIntent.RequestDiscard(UNSTAGED))
        statusBytes = statusOutput()
        presenter.onIntent(CommitIntent.Refresh)
        runCurrent()
        val before = commandsRun()

        presenter.onIntent(CommitIntent.ConfirmDiscard)
        runCurrent()

        assertNull(presenter.state.value.confirmDiscard)
        assertEquals(before, commandsRun())
    }

    // Navigation

    @Test
    fun `open diff and close are events`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.events.test {
            presenter.onIntent(CommitIntent.OpenDiff(STAGED, staged = true))
            assertEquals(CommitEvent.OpenDiff("staged.txt", staged = true), awaitItem())
            presenter.onIntent(CommitIntent.OpenDiff(UNSTAGED, staged = false))
            assertEquals(CommitEvent.OpenDiff("unstaged.txt", staged = false), awaitItem())
            presenter.onIntent(CommitIntent.Close)
            assertEquals(CommitEvent.Close, awaitItem())
        }
    }

    @Test
    fun `a path that is not UTF-8 is refused instead of run on another file`() = runTest(timeout = 10.seconds) {
        val bad = ByteArrayOutputStream().apply {
            record("# branch.oid $OID")
            record("# branch.head main")
            write("1 .M N... 100644 100644 100644 $BLOB $BLOB bad".toByteArray())
            write(0xff)
            write(0)
        }.toByteArray()
        val presenter = loaded(bad)
        val row = presenter.state.value.unstaged.single()
        val before = commandsRun()

        presenter.onIntent(CommitIntent.Stage(row))
        runCurrent()

        assertEquals(CommitError.Failed(CommitOp.Stage, GitFailure.Rejected), presenter.state.value.error)
        presenter.onIntent(CommitIntent.RequestDiscard(row))
        assertNull(presenter.state.value.confirmDiscard)
        assertEquals(before, commandsRun())
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
}
