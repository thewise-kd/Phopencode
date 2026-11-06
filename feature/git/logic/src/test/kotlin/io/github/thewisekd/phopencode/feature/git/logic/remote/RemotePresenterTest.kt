package io.github.thewisekd.phopencode.feature.git.logic.remote

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.git.command.GitCommands
import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcess
import io.github.thewisekd.phopencode.core.testing.ssh.FakeProcessEnd
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitRemote
import io.github.thewisekd.phopencode.feature.git.logic.contract.PushPlan
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private const val DIR = "/srv/çalışma alanı/sub"
private const val ROOT = "/srv/çalışma alanı"
private val OID = "a".repeat(40)
private val NEW_OID = "c".repeat(40)

private fun ByteArrayOutputStream.record(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
    write(0)
}

/** A status with only branch headers: [ab] is `+A -B`, null for none (no upstream, or gone). */
private fun statusOf(head: String? = "main", upstream: String? = "origin/main", ab: String? = "+2 -0", oid: String = OID): ByteArray =
    ByteArrayOutputStream().apply {
        record("# branch.oid $oid")
        record("# branch.head ${head ?: "(detached)"}")
        if (upstream != null) record("# branch.upstream $upstream")
        if (ab != null) record("# branch.ab $ab")
    }.toByteArray()

private val TOPLEVEL = GitCommands.revParseToplevel(DIR).commandLine
private val STATUS = GitCommands.status(ROOT).commandLine
private val REMOTES = GitCommands.remoteList(ROOT).commandLine

private fun fetch(remote: String) = GitCommands.fetch(ROOT, remote).commandLine

private fun merge(revision: String) = GitCommands.mergeFastForward(ROOT, revision).commandLine

private fun push(remote: String, branch: String, setUpstream: Boolean) = GitCommands.push(ROOT, remote, branch, setUpstream).commandLine

/** Runs each call to the end even when its coroutine is cancelled, as a slow server may. */
private class UncancellableConnection(private val inner: GitConnection) : GitConnection {
    override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = withContext(NonCancellable) { inner.withExec(block) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RemotePresenterTest {
    private val exec = FakeRemoteExec()

    /** What `status` and `remote` print next; tests change them to play a repository that changed. */
    private var statusBytes: ByteArray = statusOf()
    private var remoteNames: String = "origin\nçatal\n"

    private fun scriptRepository() {
        exec.on(TOPLEVEL) {
            stdout("$ROOT\n")
            exit(0)
        }
        exec.on(STATUS) {
            stdout(statusBytes)
            exit(0)
        }
        exec.on(REMOTES) {
            stdout(remoteNames)
            exit(0)
        }
    }

    private fun TestScope.presenter(scope: CoroutineScope = backgroundScope, git: GitConnection = ExecGitConnection(exec)) =
        DefaultRemotePresenter(directory = DIR, git = git, scope = scope)

    private fun TestScope.loaded(): DefaultRemotePresenter {
        scriptRepository()
        val presenter = presenter()
        runCurrent()
        assertFalse(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
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

        assertEquals(RemoteState(directory = DIR), presenter.state.value)
        assertTrue(exec.executions.isEmpty())
    }

    @Test
    fun `loading reads the branch, its upstream and the remotes and selects the upstream's remote`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(upstream = "çatal/main", ab = "+2 -1")
        val presenter = loaded()

        val state = presenter.state.value
        assertEquals(listOf(GitRemote("origin"), GitRemote("çatal")), state.remotes)
        assertEquals("çatal", state.selected)
        assertEquals(GitBranchStatus(head = "main", commit = OID, upstream = "çatal/main", ahead = 2, behind = 1), state.branch)
        assertTrue(state.canStart)
        assertEquals(listOf(TOPLEVEL, STATUS, REMOTES), commandsRun())
    }

    @Test
    fun `without an upstream origin is selected, else the first remote, and none disables everything`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(upstream = null, ab = null)
        val presenter = loaded()
        assertEquals("origin", presenter.state.value.selected)

        remoteNames = "çatal\nyedek\n"
        presenter.onIntent(RemoteIntent.Refresh)
        runCurrent()
        assertEquals("çatal", presenter.state.value.selected)

        remoteNames = ""
        presenter.onIntent(RemoteIntent.Refresh)
        runCurrent()
        assertNull(presenter.state.value.selected)
        assertEquals(emptyList(), presenter.state.value.remotes)
        assertFalse(presenter.state.value.canStart)
    }

    @Test
    fun `a gone upstream loads as gone`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(upstream = "origin/main", ab = null)
        val presenter = loaded()

        val branch = presenter.state.value.branch!!
        assertTrue(branch.upstreamGone)
        assertEquals("origin", presenter.state.value.selected)
    }

    @Test
    fun `remote names that cannot be passed back to git are left out`() = runTest(timeout = 10.seconds) {
        remoteNames = "origin\nup/stream\n-x\n"
        val presenter = loaded()

        assertEquals(listOf(GitRemote("origin")), presenter.state.value.remotes)
    }

    @Test
    fun `a folder outside a repository shows the failure and retry reads again`() = runTest(timeout = 10.seconds) {
        exec.on(TOPLEVEL, exitWith(128, stderr = "fatal: not a git repository (or any of the parent directories): .git\n"))
        val presenter = presenter()
        runCurrent()

        val failed = presenter.state.value
        assertFalse(failed.loading)
        assertEquals(GitFailure.NotRepository, failed.error)
        assertFalse(failed.canStart)

        scriptRepository()
        presenter.onIntent(RemoteIntent.Retry)
        assertTrue(presenter.state.value.loading)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals("origin", presenter.state.value.selected)
    }

    @Test
    fun `a lost connection while loading is NotConnected`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        exec.failNext(SshException.Disconnected())
        val presenter = presenter()
        runCurrent()

        assertEquals(GitFailure.NotConnected, presenter.state.value.error)
    }

    @Test
    fun `a stale read never publishes`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val gate = CompletableDeferred<Unit>()
        exec.on(STATUS) {
            gate.await()
            stdout(statusOf(head = "eski"))
            exit(0)
        }
        val presenter = presenter(git = UncancellableConnection(ExecGitConnection(exec)))
        runCurrent()
        assertTrue(presenter.state.value.loading)

        presenter.onIntent(RemoteIntent.Refresh)
        exec.on(STATUS) {
            stdout(statusOf(head = "yeni"))
            exit(0)
        }
        gate.complete(Unit)
        runCurrent()

        assertEquals("yeni", presenter.state.value.branch?.head)
        assertFalse(presenter.state.value.loading)
    }

    // Selecting

    @Test
    fun `select takes only a configured remote`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))
        assertEquals("çatal", presenter.state.value.selected)

        presenter.onIntent(RemoteIntent.SelectRemote("fork"))
        presenter.onIntent(RemoteIntent.SelectRemote("../çatal"))
        assertEquals("çatal", presenter.state.value.selected)
        runCurrent()
        assertEquals(listOf(TOPLEVEL, STATUS, REMOTES), commandsRun())
    }

    @Test
    fun `the selection stays across a refresh while the remote exists`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))

        presenter.onIntent(RemoteIntent.Refresh)
        runCurrent()
        assertEquals("çatal", presenter.state.value.selected)

        remoteNames = "origin\n"
        presenter.onIntent(RemoteIntent.Refresh)
        runCurrent()
        assertEquals("origin", presenter.state.value.selected)
    }

    // Fetch

    @Test
    fun `fetch runs fetch of the selected remote and reads again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(0, stderr = "Fetching origin\nFrom /srv/r\n   aaa..bbb  main -> origin/main\n"))
        statusBytes = statusOf(ab = "+2 -3")

        presenter.onIntent(RemoteIntent.Fetch)
        assertEquals(RemoteOp.Fetch, presenter.state.value.running)
        assertFalse(presenter.state.value.canStart)
        runCurrent()

        val state = presenter.state.value
        assertNull(state.running)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = false), state.result)
        assertEquals(3, state.branch?.behind)
        assertEquals(listOf(TOPLEVEL, STATUS, REMOTES, fetch("origin"), TOPLEVEL, STATUS, REMOTES), commandsRun())
    }

    @Test
    fun `a fetch that brings nothing is up to date`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(0, stderr = "Fetching origin\n"))

        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()

        assertEquals(RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = true), presenter.state.value.result)
        presenter.onIntent(RemoteIntent.DismissResult)
        assertNull(presenter.state.value.result)
    }

    @Test
    fun `credential prompts git refused are CredentialRequired`() = runTest(timeout = 10.seconds) {
        val messages = listOf(
            "fatal: could not read Username for 'https://example.invalid': terminal prompts disabled\n",
            "error: unable to read askpass response from 'false'\nfatal: could not read Password for 'https://u@example.invalid': No such device or address\n",
            "remote: Invalid username or password.\nfatal: Authentication failed for 'https://example.invalid/r.git/'\n",
            "git@example.invalid: Permission denied (publickey).\nfatal: Could not read from remote repository.\n",
            "error: unable to get password from user\n",
        )
        val presenter = loaded()
        for (message in messages) {
            exec.on(fetch("origin"), exitWith(128, stderr = message))
            presenter.onIntent(RemoteIntent.Fetch)
            runCurrent()
            assertEquals(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.CredentialRequired), presenter.state.value.result, message)
        }
    }

    @Test
    fun `a server message alone does not pass for a credential failure`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(1, stderr = "remote: Authentication failed\nremote: terminal prompts disabled\n"))

        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()

        assertEquals(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.GitError(1)), presenter.state.value.result)
    }

    @Test
    fun `fetch of a remote git does not know is NotFound`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(1, stderr = "fatal: no such remote or remote group: origin\n"))

        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()

        assertEquals(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.NotFound), presenter.state.value.result)
    }

    @Test
    fun `a remote removed since the read is refused without running another one`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))
        // A refresh is on its way and finds çatal gone; the fetch asked for meanwhile decides from it.
        val gate = CompletableDeferred<Unit>()
        exec.on(REMOTES) {
            gate.await()
            stdout("origin\n")
            exit(0)
        }
        presenter.onIntent(RemoteIntent.Refresh)
        runCurrent()
        presenter.onIntent(RemoteIntent.Fetch)
        gate.complete(Unit)
        runCurrent()

        assertTrue(commandsRun().none { it.contains("'fetch'") })
        assertEquals(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.NotFound), presenter.state.value.result)
        assertEquals("origin", presenter.state.value.selected)
        assertNull(presenter.state.value.running)
    }

    @Test
    fun `one operation at a time`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(fetch("origin")) {
            gate.await()
            exit(0)
        }

        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()
        presenter.onIntent(RemoteIntent.Pull)
        presenter.onIntent(RemoteIntent.RequestPush)
        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))
        assertEquals(RemoteOp.Fetch, presenter.state.value.running)
        assertNull(presenter.state.value.confirmPush)
        assertEquals("origin", presenter.state.value.selected)
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, commandsRun().count { it == fetch("origin") })
        assertTrue(commandsRun().none { it.contains("'merge'") || it.contains("'push'") })
    }

    @Test
    fun `a command running past its limit is TimedOut`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin")) { awaitCancellation() }

        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()
        advanceTimeBy(GitCommands.REMOTE_TIMEOUT + 1.minutes)
        runCurrent()

        assertEquals(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.TimedOut), presenter.state.value.result)
        assertNull(presenter.state.value.running)
    }

    // Cancel

    @Test
    fun `cancel ends the running fetch, reports Cancelled and reads again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin")) { awaitCancellation() }
        statusBytes = statusOf(ab = "+0 -5")

        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()
        assertTrue(exec.isRunning(fetch("origin")))
        presenter.onIntent(RemoteIntent.Cancel)
        runCurrent()

        val state = presenter.state.value
        assertEquals(RemoteResult.Cancelled(RemoteOp.Fetch), state.result)
        assertNull(state.running)
        assertEquals(FakeProcessEnd.Killed, exec.executions.single { it.command == fetch("origin") }.end)
        assertEquals(5, state.branch?.behind)
        assertTrue(state.canStart)
    }

    @Test
    fun `cancel during the fetch of a pull never merges`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin")) { awaitCancellation() }

        presenter.onIntent(RemoteIntent.Pull)
        runCurrent()
        presenter.onIntent(RemoteIntent.Cancel)
        runCurrent()

        assertEquals(RemoteResult.Cancelled(RemoteOp.Pull), presenter.state.value.result)
        assertTrue(commandsRun().none { it.contains("'merge'") })
    }

    @Test
    fun `cancel before the operation got the lock runs nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val gate = CompletableDeferred<Unit>()
        exec.on(STATUS) {
            gate.await()
            stdout(statusBytes)
            exit(0)
        }
        // The refresh holds the lock; the fetch waits for it.
        presenter.onIntent(RemoteIntent.Refresh)
        runCurrent()
        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()
        assertEquals(RemoteOp.Fetch, presenter.state.value.running)
        presenter.onIntent(RemoteIntent.Cancel)
        gate.complete(Unit)
        runCurrent()

        assertTrue(commandsRun().none { it == fetch("origin") })
        assertEquals(RemoteResult.Cancelled(RemoteOp.Fetch), presenter.state.value.result)
        assertNull(presenter.state.value.running)
    }

    @Test
    fun `cancel without a running operation does nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.Cancel)
        runCurrent()

        assertNull(presenter.state.value.result)
    }

    // Pull

    @Test
    fun `pull fetches then fast-forwards to the upstream`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(ab = "+0 -2")
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(0))
        exec.on(merge("@{upstream}"), exitWith(0))
        val pulled = statusOf(ab = "+0 -0", oid = NEW_OID)

        exec.on(STATUS) {
            stdout(if (commandsRun().contains(merge("@{upstream}"))) pulled else statusBytes)
            exit(0)
        }
        presenter.onIntent(RemoteIntent.Pull)
        runCurrent()

        val state = presenter.state.value
        assertEquals(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = false), state.result)
        assertEquals(NEW_OID, state.branch?.commit)
        val ran = commandsRun()
        assertTrue(ran.indexOf(fetch("origin")) < ran.indexOf(merge("@{upstream}")))
        assertTrue(ran.none { it.contains("'pull'") })
    }

    @Test
    fun `a pull that leaves HEAD where it was is up to date`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(0))
        exec.on(merge("@{upstream}"), exitWith(0))

        presenter.onIntent(RemoteIntent.Pull)
        runCurrent()

        assertEquals(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = true), presenter.state.value.result)
    }

    @Test
    fun `pull from a remote that is not the upstream's takes its branch of the same name`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = "özellik", upstream = "origin/özellik", ab = "+0 -0")
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))
        exec.on(fetch("çatal"), exitWith(0))
        exec.on(merge("refs/remotes/çatal/özellik"), exitWith(0))

        presenter.onIntent(RemoteIntent.Pull)
        runCurrent()

        assertEquals(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = true), presenter.state.value.result)
        assertTrue(commandsRun().contains(merge("refs/remotes/çatal/özellik")))
    }

    @Test
    fun `pull failures map to what the screen explains`() = runTest(timeout = 10.seconds) {
        val cases = listOf(
            "hint: Diverging branches can't be fast-forwarded, you need to either:\nfatal: Not possible to fast-forward, aborting.\n" to GitFailure.Diverged,
            "error: Your local changes to the following files would be overwritten by merge:\n\ta.txt\nAborting\n" to GitFailure.WouldOverwrite,
            "error: The following untracked working tree files would be overwritten by merge:\n\tg\nAborting\n" to GitFailure.WouldOverwrite,
            "error: Merging is not possible because you have unmerged files.\n" to GitFailure.Conflict,
            "fatal: You have not concluded your merge (MERGE_HEAD exists).\n" to GitFailure.Conflict,
            "merge: @{upstream} - not something we can merge\n" to GitFailure.NotFound,
        )
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(0))
        for ((stderr, failure) in cases) {
            exec.on(merge("@{upstream}"), exitWith(128, stderr = stderr))
            presenter.onIntent(RemoteIntent.Pull)
            runCurrent()
            assertEquals(RemoteResult.Failed(RemoteOp.Pull, failure), presenter.state.value.result, stderr)
        }
    }

    @Test
    fun `a failed fetch stops the pull`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(fetch("origin"), exitWith(128, stderr = "fatal: could not read Username for 'https://x': terminal prompts disabled\n"))

        presenter.onIntent(RemoteIntent.Pull)
        runCurrent()

        assertEquals(RemoteResult.Failed(RemoteOp.Pull, GitFailure.CredentialRequired), presenter.state.value.result)
        assertTrue(commandsRun().none { it.contains("'merge'") })
    }

    @Test
    fun `pull on a detached HEAD is refused without running`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = null, upstream = null, ab = null)
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.Pull)
        runCurrent()

        assertEquals(RemoteResult.Failed(RemoteOp.Pull, GitFailure.Rejected), presenter.state.value.result)
        assertEquals(listOf(TOPLEVEL, STATUS, REMOTES), commandsRun())
    }

    // Push

    @Test
    fun `request push opens the confirmation with the plan and runs nothing`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.RequestPush)
        runCurrent()

        assertEquals(PushPlan(remote = "origin", branch = "main", ahead = 2, setUpstream = false), presenter.state.value.confirmPush)
        assertNull(presenter.state.value.running)
        assertEquals(listOf(TOPLEVEL, STATUS, REMOTES), commandsRun())

        presenter.onIntent(RemoteIntent.DismissPush)
        assertNull(presenter.state.value.confirmPush)
        runCurrent()
        assertEquals(listOf(TOPLEVEL, STATUS, REMOTES), commandsRun())
    }

    @Test
    fun `a branch without an upstream sets it, with the count unknown`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = "çalış", upstream = null, ab = null)
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.RequestPush)

        assertEquals(PushPlan(remote = "origin", branch = "çalış", ahead = 0, setUpstream = true), presenter.state.value.confirmPush)
    }

    @Test
    fun `a gone upstream is set again by the push, which recreates the remote branch`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = "özellik", upstream = "origin/özellik", ab = null)
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.RequestPush)

        assertEquals(PushPlan(remote = "origin", branch = "özellik", ahead = 0, setUpstream = true), presenter.state.value.confirmPush)
    }

    @Test
    fun `pushing to another remote than the upstream's keeps the upstream and the count unknown`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))

        presenter.onIntent(RemoteIntent.RequestPush)

        assertEquals(PushPlan(remote = "çatal", branch = "main", ahead = 0, setUpstream = false), presenter.state.value.confirmPush)
    }

    @Test
    fun `counts git did not make are not shown as known`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(ab = "+? -?")
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.RequestPush)

        assertEquals(0, presenter.state.value.confirmPush?.ahead)
    }

    @Test
    fun `selecting another remote drops the open confirmation`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.RequestPush)

        presenter.onIntent(RemoteIntent.SelectRemote("çatal"))

        assertNull(presenter.state.value.confirmPush)
    }

    @Test
    fun `push on a detached HEAD is refused`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = null, upstream = null, ab = null)
        val presenter = loaded()

        presenter.onIntent(RemoteIntent.RequestPush)

        assertNull(presenter.state.value.confirmPush)
        assertEquals(RemoteResult.Failed(RemoteOp.Push, GitFailure.Rejected), presenter.state.value.result)
    }

    @Test
    fun `confirm push pushes the branch without force and reads again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(push("origin", "main", setUpstream = false), exitWith(0, stderr = "To /srv/r.git\n   aaa..bbb  main -> main\n"))
        presenter.onIntent(RemoteIntent.RequestPush)

        statusBytes = statusOf(ab = "+0 -0")
        presenter.onIntent(RemoteIntent.ConfirmPush)
        assertNull(presenter.state.value.confirmPush)
        assertEquals(RemoteOp.Push, presenter.state.value.running)
        runCurrent()

        val state = presenter.state.value
        assertEquals(RemoteResult.Succeeded(RemoteOp.Push, upToDate = false), state.result)
        assertEquals(0, state.branch?.ahead)
        val pushes = commandsRun().filter { it.contains("'push'") }
        assertEquals(listOf(push("origin", "main", setUpstream = false)), pushes)
        assertNoForce(pushes.single())
    }

    @Test
    fun `a push with nothing to send is up to date`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(push("origin", "main", setUpstream = false), exitWith(0, stderr = "Everything up-to-date\n"))

        presenter.onIntent(RemoteIntent.RequestPush)
        presenter.onIntent(RemoteIntent.ConfirmPush)
        runCurrent()

        assertEquals(RemoteResult.Succeeded(RemoteOp.Push, upToDate = true), presenter.state.value.result)
    }

    @Test
    fun `a push that sets the upstream passes set-upstream`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = "çalış", upstream = null, ab = null)
        val presenter = loaded()
        exec.on(push("origin", "çalış", setUpstream = true), exitWith(0))

        presenter.onIntent(RemoteIntent.RequestPush)
        presenter.onIntent(RemoteIntent.ConfirmPush)
        runCurrent()

        assertEquals(RemoteResult.Succeeded(RemoteOp.Push, upToDate = false), presenter.state.value.result)
        assertTrue(commandsRun().contains(push("origin", "çalış", setUpstream = true)))
    }

    @Test
    fun `a refused push is NonFastForward`() = runTest(timeout = 10.seconds) {
        val refusals = listOf(
            "To /srv/r.git\n ! [rejected]        main -> main (non-fast-forward)\nerror: failed to push some refs to '/srv/r.git'\n",
            "To /srv/r.git\n ! [rejected]        main -> main (fetch first)\nerror: failed to push some refs to '/srv/r.git'\n",
        )
        val presenter = loaded()
        for (stderr in refusals) {
            exec.on(push("origin", "main", setUpstream = false), exitWith(1, stderr = stderr))
            presenter.onIntent(RemoteIntent.RequestPush)
            presenter.onIntent(RemoteIntent.ConfirmPush)
            runCurrent()
            assertEquals(RemoteResult.Failed(RemoteOp.Push, GitFailure.NonFastForward), presenter.state.value.result, stderr)
        }
    }

    @Test
    fun `a push a server hook declined is not NonFastForward`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(
            push("origin", "main", setUpstream = false),
            exitWith(1, stderr = "To /srv/r.git\n ! [remote rejected] main -> main (pre-receive hook declined)\nerror: failed to push some refs\n"),
        )

        presenter.onIntent(RemoteIntent.RequestPush)
        presenter.onIntent(RemoteIntent.ConfirmPush)
        runCurrent()

        assertEquals(RemoteResult.Failed(RemoteOp.Push, GitFailure.GitError(1)), presenter.state.value.result)
    }

    @Test
    fun `confirm push decides again from the newest read and asks again when the plan changed`() = runTest(timeout = 10.seconds) {
        statusBytes = statusOf(head = "çalış", upstream = null, ab = null)
        val presenter = loaded()
        presenter.onIntent(RemoteIntent.RequestPush)
        assertEquals(true, presenter.state.value.confirmPush?.setUpstream)

        // Meanwhile the upstream was set on the server; a refresh finds it.
        statusBytes = statusOf(head = "çalış", upstream = "origin/çalış", ab = "+1 -0")
        presenter.onIntent(RemoteIntent.Refresh)
        presenter.onIntent(RemoteIntent.ConfirmPush)
        runCurrent()

        assertTrue(commandsRun().none { it.contains("'push'") })
        assertEquals(PushPlan(remote = "origin", branch = "çalış", ahead = 1, setUpstream = false), presenter.state.value.confirmPush)
        assertNull(presenter.state.value.running)
    }

    @Test
    fun `cancel during a push reports Cancelled and reads the truth again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        exec.on(push("origin", "main", setUpstream = false)) { awaitCancellation() }
        presenter.onIntent(RemoteIntent.RequestPush)
        presenter.onIntent(RemoteIntent.ConfirmPush)
        runCurrent()
        // The push landed on the server before the cancel did.
        statusBytes = statusOf(ab = "+0 -0")

        presenter.onIntent(RemoteIntent.Cancel)
        runCurrent()

        assertEquals(RemoteResult.Cancelled(RemoteOp.Push), presenter.state.value.result)
        assertEquals(0, presenter.state.value.branch?.ahead)
    }

    @Test
    fun `leaving the screen cancels the running operation`() = runTest(timeout = 10.seconds) {
        scriptRepository()
        val screen = CoroutineScope(backgroundScope.coroutineContext + Job())
        val presenter = presenter(scope = screen)
        runCurrent()
        exec.on(fetch("origin")) { awaitCancellation() }
        presenter.onIntent(RemoteIntent.Fetch)
        runCurrent()

        screen.cancel()
        runCurrent()

        assertEquals(FakeProcessEnd.Killed, exec.executions.single { it.command == fetch("origin") }.end)
        assertEquals(1, commandsRun().count { it == STATUS })
    }

    // Navigation

    @Test
    fun `close sends the close event`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.events.test {
            presenter.onIntent(RemoteIntent.Close)
            assertEquals(RemoteEvent.Close, awaitItem())
        }
    }

    // No force

    @Test
    fun `no command the logic can build forces`() {
        val source = java.io.File(
            "src/main/kotlin/io/github/thewisekd/phopencode/feature/git/logic/remote/DefaultRemotePresenter.kt",
        ).readText()
        for (word in listOf("--force", "force-with-lease", "\"-f\"", "\"+", "'pull'", "\"pull\"", "reset", "clean")) {
            assertFalse(word in source.lowercase(), word)
        }
    }

    private fun assertNoForce(command: String) {
        val words = Regex("'([^']*)'").findAll(command).map { it.groupValues[1] }.toList()
        assertTrue(words.none { it == "-f" || it.startsWith("--force") || it.startsWith("+") }, command)
        assertTrue(words.contains("refs/heads/main:refs/heads/main"), command)
    }
}
