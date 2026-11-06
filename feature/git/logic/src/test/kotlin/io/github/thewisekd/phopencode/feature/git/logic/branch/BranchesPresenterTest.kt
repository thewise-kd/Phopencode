package io.github.thewisekd.phopencode.feature.git.logic.branch

import io.github.thewisekd.phopencode.core.ssh.api.ExecEvent
import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.ExecResult
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchActionError
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchDialog
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranch
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val DEFAULT_BRANCHES = branchLine("refs/heads/main", hash(1), current = true, upstream = "refs/remotes/origin/main", track = "ahead 2, behind 1") +
    branchLine("refs/heads/özellik", hash(3)) +
    branchLine("refs/heads/eski", hash(4), upstream = "refs/remotes/origin/eski", track = "gone") +
    branchLine("refs/remotes/origin/HEAD", hash(1), symref = "refs/remotes/origin/main") +
    branchLine("refs/remotes/origin/main", hash(1)) +
    branchLine("refs/remotes/origin/yeni", hash(5))

@OptIn(ExperimentalCoroutinesApi::class)
class BranchesPresenterTest {
    private val exec = FakeRemoteExec()

    /** What the repository prints next; tests change it to play a repository that changed. */
    private var branches: String = DEFAULT_BRANCHES
    private var status: ByteArray = statusOutput()

    init {
        exec.on(BRANCH_LIST) {
            stdout(branches)
            exit(0)
        }
        exec.on(STATUS) {
            stdout(status)
            exit(0)
        }
    }

    private fun TestScope.presenter(git: GitConnection = ExecGitConnection(exec)): DefaultBranchesPresenter =
        DefaultBranchesPresenter(DIR, git, backgroundScope)

    private fun TestScope.loaded(git: GitConnection = ExecGitConnection(exec)): DefaultBranchesPresenter =
        presenter(git).also { runCurrent() }

    private fun TestScope.eventsOf(presenter: DefaultBranchesPresenter): List<BranchEvent> {
        val events = mutableListOf<BranchEvent>()
        backgroundScope.launch { presenter.events.collect { events += it } }
        return events
    }

    private fun DefaultBranchesPresenter.local(name: String): GitBranch = state.value.local.single { it.name == name }

    private fun DefaultBranchesPresenter.remote(name: String): GitBranch = state.value.remote.single { it.name == name }

    private fun actions(): List<String> = exec.commands().filter { it != BRANCH_LIST && it != STATUS }

    // Listing

    @Test
    fun `the first state is loading and nothing runs before the scope does`() = runTest(timeout = 10.seconds) {
        val presenter = presenter()

        assertTrue(presenter.state.value.loading)
        assertEquals(DIR, presenter.state.value.directory)
        assertTrue(exec.commands().isEmpty())
    }

    @Test
    fun `local and remote branches are listed apart with the current one and tracking`() = runTest(timeout = 10.seconds) {
        val state = loaded().state.value

        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals(listOf("main", "özellik", "eski"), state.local.map { it.name })
        assertEquals(listOf("origin/main", "origin/yeni"), state.remote.map { it.name })
        assertTrue(state.remote.all { it.isRemote })
        assertTrue(state.local.none { it.isRemote })
        assertEquals("main", state.current?.name)
        val main = state.local.first()
        assertEquals(GitBranch("main", isCurrent = true, upstream = "origin/main", ahead = 2, behind = 1, commit = hash(1)), main)
        assertNull(state.local.single { it.name == "eski" }.upstream, "a gone upstream shows as none")
        assertFalse(state.dirty)
    }

    @Test
    fun `a detached HEAD has no current branch`() = runTest(timeout = 10.seconds) {
        branches = "(HEAD detached at 0000001)\u0000*\u0000${hash(1)}\u0000\u0000\u0000\n" +
            branchLine("refs/heads/main", hash(1))
        status = statusOutput(head = null)

        val state = loaded().state.value

        assertNull(state.error)
        assertEquals(listOf("main"), state.local.map { it.name })
        assertNull(state.current)
    }

    @Test
    fun `a branch without commits yet shows as the current one`() = runTest(timeout = 10.seconds) {
        branches = ""
        status = statusOutput(head = "main", oid = null)

        val state = loaded().state.value

        assertEquals(listOf(GitBranch("main", isCurrent = true)), state.local)
        assertEquals("main", state.current?.name)
    }

    @Test
    fun `changes to tracked files make the tree dirty`() = runTest(timeout = 10.seconds) {
        status = statusOutput(changes = listOf("dosya.txt"))

        assertTrue(loaded().state.value.dirty)
    }

    @Test
    fun `a folder outside a repository is an error that retry clears`() = runTest(timeout = 10.seconds) {
        exec.fails(BRANCH_LIST, "fatal: not a git repository (or any of the parent directories): .git\n", exit = 128)
        val presenter = loaded()
        assertEquals(GitFailure.NotRepository, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)

        exec.on(BRANCH_LIST) {
            stdout(branches)
            exit(0)
        }
        presenter.onIntent(BranchIntent.Retry)
        assertTrue(presenter.state.value.loading)
        runCurrent()

        assertNull(presenter.state.value.error)
        assertEquals(3, presenter.state.value.local.size)
    }

    @Test
    fun `a lost connection while listing is an error`() = runTest(timeout = 10.seconds) {
        exec.failNext(SshException.Disconnected())

        assertEquals(GitFailure.NotConnected, loaded().state.value.error)
    }

    @Test
    fun `missing git is reported`() = runTest(timeout = 10.seconds) {
        exec.fails(BRANCH_LIST, "sh: git: not found\n", exit = 127)

        assertEquals(GitFailure.GitMissing, loaded().state.value.error)
    }

    @Test
    fun `malformed output is unknown`() = runTest(timeout = 10.seconds) {
        branches = "garbage\n"

        assertEquals(GitFailure.Unknown, loaded().state.value.error)
    }

    @Test
    fun `a refresh replaces a slower read and the old result is dropped`() = runTest(timeout = 10.seconds) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        exec.on(BRANCH_LIST) {
            calls++
            if (calls == 1) {
                gate.await()
                stdout(branchLine("refs/heads/eski-okuma", hash(9), current = true))
            } else {
                stdout(branches)
            }
            exit(0)
        }
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(BranchIntent.Refresh)
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("main", "özellik", "eski"), presenter.state.value.local.map { it.name })
    }

    @Test
    fun `search filters both lists ignoring case and surrounding space`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Search("  MAI "))

        val state = presenter.state.value
        assertEquals("  MAI ", state.query)
        assertEquals(listOf("main"), state.shownLocal.map { it.name })
        assertEquals(listOf("origin/main"), state.shownRemote.map { it.name })
        presenter.onIntent(BranchIntent.Search(""))
        assertEquals(3, presenter.state.value.shownLocal.size)
    }

    // Creating

    @Test
    fun `the create dialog checks the name as it is typed`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(BranchIntent.StartCreate())
        assertEquals(BranchDialog.Create(), presenter.state.value.dialog)

        fun problemOf(name: String): BranchNameProblem? {
            presenter.onIntent(BranchIntent.EditName(name))
            val dialog = assertIs<BranchDialog.Create>(presenter.state.value.dialog)
            assertEquals(name, dialog.name)
            return dialog.problem
        }

        assertEquals(BranchNameProblem.Blank, problemOf(""))
        assertEquals(BranchNameProblem.Blank, problemOf("   "))
        assertEquals(BranchNameProblem.Invalid, problemOf("-x"))
        assertEquals(BranchNameProblem.Invalid, problemOf("a..b"))
        assertEquals(BranchNameProblem.Invalid, problemOf("iki kelime"))
        assertEquals(BranchNameProblem.Invalid, problemOf("HEAD"))
        assertEquals(BranchNameProblem.Taken, problemOf("main"))
        assertEquals(BranchNameProblem.Taken, problemOf("özellik"))
        assertNull(problemOf("çalışma/ğüşıöç"))
        assertTrue(assertIs<BranchDialog.Create>(presenter.state.value.dialog).canSubmit)
        assertNull(problemOf("origin/main"), "a remote branch name does not take a local name")
    }

    @Test
    fun `an invalid name is never submitted`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(BranchIntent.StartCreate())
        presenter.onIntent(BranchIntent.EditName("-D"))
        presenter.onIntent(BranchIntent.SubmitCreate)
        runCurrent()

        assertTrue(actions().isEmpty())
        assertIs<BranchDialog.Create>(presenter.state.value.dialog)
    }

    @Test
    fun `create then switch runs both and tells the graph`() = runTest(timeout = 10.seconds) {
        exec.on(create("yeni-dal")) {
            branches += branchLine("refs/heads/yeni-dal", hash(1))
            exit(0)
        }
        exec.succeeds(switch("yeni-dal"))
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.StartCreate())
        presenter.onIntent(BranchIntent.EditName("yeni-dal"))
        presenter.onIntent(BranchIntent.SubmitCreate)
        assertEquals(BranchOp.Create, presenter.state.value.busy)
        runCurrent()

        assertEquals(listOf(create("yeni-dal"), switch("yeni-dal")), actions())
        assertEquals(listOf<BranchEvent>(BranchEvent.Switched), events)
        val state = presenter.state.value
        assertNull(state.dialog)
        assertNull(state.busy)
        assertNull(state.actionError)
        assertTrue(state.local.any { it.name == "yeni-dal" })
    }

    @Test
    fun `create without switching only creates`() = runTest(timeout = 10.seconds) {
        exec.succeeds(create("yeni-dal"))
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.StartCreate())
        presenter.onIntent(BranchIntent.EditName("yeni-dal"))
        presenter.onIntent(BranchIntent.SetSwitchAfter(false))
        assertFalse(assertIs<BranchDialog.Create>(presenter.state.value.dialog).switchAfter)
        presenter.onIntent(BranchIntent.SubmitCreate)
        runCurrent()

        assertEquals(listOf(create("yeni-dal")), actions())
        assertTrue(events.isEmpty())
        assertNull(presenter.state.value.dialog)
    }

    @Test
    fun `a start point that names a listed branch is passed as its full ref`() = runTest(timeout = 10.seconds) {
        exec.succeeds(create("takip", "refs/remotes/origin/yeni"))
        val presenter = loaded()

        presenter.onIntent(BranchIntent.StartCreate(startPoint = "origin/yeni"))
        presenter.onIntent(BranchIntent.EditName("takip"))
        presenter.onIntent(BranchIntent.SetSwitchAfter(false))
        presenter.onIntent(BranchIntent.SubmitCreate)
        runCurrent()

        assertEquals(listOf(create("takip", "refs/remotes/origin/yeni")), actions())
    }

    @Test
    fun `a start point that could be an option is refused`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.StartCreate(startPoint = "--orphan"))

        assertNull(presenter.state.value.dialog)
        assertEquals(BranchActionError(BranchOp.Create, GitFailure.Rejected), presenter.state.value.actionError)
    }

    @Test
    fun `creating from another commit on a dirty tree asks before switching`() = runTest(timeout = 10.seconds) {
        status = statusOutput(changes = listOf("dosya.txt"))
        exec.on(create("takip", "refs/remotes/origin/yeni")) {
            branches += branchLine("refs/heads/takip", hash(5))
            exit(0)
        }
        val presenter = loaded()

        presenter.onIntent(BranchIntent.StartCreate(startPoint = "origin/yeni"))
        presenter.onIntent(BranchIntent.EditName("takip"))
        presenter.onIntent(BranchIntent.SubmitCreate)
        runCurrent()

        assertEquals(listOf(create("takip", "refs/remotes/origin/yeni")), actions())
        assertEquals(BranchDialog.ConfirmSwitchDirty(presenter.local("takip")), presenter.state.value.dialog)
    }

    @Test
    fun `a name git says exists is marked taken`() = runTest(timeout = 10.seconds) {
        exec.fails(create("yarış"), "fatal: a branch named 'yarış' already exists\n", exit = 128)
        val presenter = loaded()

        presenter.onIntent(BranchIntent.StartCreate())
        presenter.onIntent(BranchIntent.EditName("yarış"))
        presenter.onIntent(BranchIntent.SubmitCreate)
        runCurrent()

        val dialog = assertIs<BranchDialog.Create>(presenter.state.value.dialog)
        assertEquals(BranchNameProblem.Taken, dialog.problem)
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `a failed create keeps the dialog and reports the error`() = runTest(timeout = 10.seconds) {
        exec.fails(create("yeni"), "fatal: something else\n", exit = 128)
        val presenter = loaded()

        presenter.onIntent(BranchIntent.StartCreate())
        presenter.onIntent(BranchIntent.EditName("yeni"))
        presenter.onIntent(BranchIntent.SubmitCreate)
        runCurrent()

        assertIs<BranchDialog.Create>(presenter.state.value.dialog)
        assertEquals(BranchActionError(BranchOp.Create, GitFailure.GitError(128)), presenter.state.value.actionError)
        presenter.onIntent(BranchIntent.DismissError)
        assertNull(presenter.state.value.actionError)
    }

    // Switching

    @Test
    fun `switching on a clean tree runs at once and tells the graph`() = runTest(timeout = 10.seconds) {
        exec.succeeds(switch("özellik"))
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.Switch(presenter.local("özellik")))
        assertEquals(BranchOp.Switch, presenter.state.value.busy)
        runCurrent()

        assertEquals(listOf(switch("özellik")), actions())
        assertEquals(listOf<BranchEvent>(BranchEvent.Switched), events)
        assertNull(presenter.state.value.busy)
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `the current branch is not switched to again`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.local("main")))
        runCurrent()

        assertTrue(actions().isEmpty())
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `a branch not in the list is not switched to`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(GitBranch("--orphan")))
        runCurrent()

        assertTrue(actions().isEmpty())
    }

    @Test
    fun `a dirty tree warns first and a dismissed warning switches nothing`() = runTest(timeout = 10.seconds) {
        status = statusOutput(changes = listOf("dosya.txt"))
        val presenter = loaded()
        val branch = presenter.local("özellik")

        presenter.onIntent(BranchIntent.Switch(branch))
        runCurrent()
        assertEquals(BranchDialog.ConfirmSwitchDirty(branch), presenter.state.value.dialog)
        assertTrue(actions().isEmpty())

        presenter.onIntent(BranchIntent.DismissDialog)
        runCurrent()
        assertNull(presenter.state.value.dialog)
        assertTrue(actions().isEmpty())
    }

    @Test
    fun `a confirmed switch git refuses shows the refusal and never forces`() = runTest(timeout = 10.seconds) {
        status = statusOutput(changes = listOf("dosya.txt"))
        exec.fails(
            switch("özellik"),
            "error: Your local changes to the following files would be overwritten by checkout:\n\tdosya.txt\n" +
                "Please commit your changes or stash them before you switch branches.\nAborting\n",
        )
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.Switch(presenter.local("özellik")))
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()

        assertEquals(listOf(switch("özellik")), actions())
        assertTrue(events.isEmpty())
        val state = presenter.state.value
        assertNull(state.dialog)
        assertNull(state.busy)
        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.WouldOverwrite), state.actionError)
        assertTrue(exec.commands().none { "--force" in it || "--discard-changes" in it || " -f" in it })
    }

    @Test
    fun `untracked files in the way are a refusal too`() = runTest(timeout = 10.seconds) {
        exec.fails(
            switch("özellik"),
            "error: The following untracked working tree files would be overwritten by checkout:\n\tyeni.txt\n" +
                "Please move or remove them before you switch branches.\nAborting\n",
        )
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.local("özellik")))
        runCurrent()

        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.WouldOverwrite), presenter.state.value.actionError)
    }

    @Test
    fun `switching to a remote branch creates the local branch that tracks it`() = runTest(timeout = 10.seconds) {
        exec.succeeds(create("yeni", "refs/remotes/origin/yeni"))
        exec.succeeds(switch("yeni"))
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.Switch(presenter.remote("origin/yeni")))
        runCurrent()

        assertEquals(listOf(create("yeni", "refs/remotes/origin/yeni"), switch("yeni")), actions())
        assertEquals(listOf<BranchEvent>(BranchEvent.Switched), events)
    }

    @Test
    fun `a switch that fails after the tracking branch was created keeps the branch`() = runTest(timeout = 10.seconds) {
        exec.on(create("yeni", "refs/remotes/origin/yeni")) {
            branches += branchLine("refs/heads/yeni", hash(5), upstream = "refs/remotes/origin/yeni")
            exit(0)
        }
        exec.fails(switch("yeni"), "error: Your local changes to the following files would be overwritten by checkout:\n\tdosya.txt\n")
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.Switch(presenter.remote("origin/yeni")))
        runCurrent()

        assertEquals(listOf(create("yeni", "refs/remotes/origin/yeni"), switch("yeni")), actions())
        assertTrue(events.isEmpty())
        val state = presenter.state.value
        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.WouldOverwrite), state.actionError)
        assertNull(state.busy)
        assertEquals("origin/yeni", state.local.single { it.name == "yeni" }.upstream)
    }

    @Test
    fun `a failed create of the tracking branch does not switch`() = runTest(timeout = 10.seconds) {
        exec.fails(create("yeni", "refs/remotes/origin/yeni"), "fatal: something else\n", exit = 128)
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.remote("origin/yeni")))
        runCurrent()

        assertEquals(listOf(create("yeni", "refs/remotes/origin/yeni")), actions())
        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.GitError(128)), presenter.state.value.actionError)
    }

    @Test
    fun `a dirty tree warns before a remote tap too`() = runTest(timeout = 10.seconds) {
        status = statusOutput(changes = listOf("dosya.txt"))
        exec.succeeds(create("yeni", "refs/remotes/origin/yeni"))
        exec.succeeds(switch("yeni"))
        val presenter = loaded()
        val branch = presenter.remote("origin/yeni")

        presenter.onIntent(BranchIntent.Switch(branch))
        assertEquals(BranchDialog.ConfirmSwitchDirty(branch), presenter.state.value.dialog)
        runCurrent()
        assertTrue(actions().isEmpty())

        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()
        assertEquals(listOf(create("yeni", "refs/remotes/origin/yeni"), switch("yeni")), actions())
    }

    @Test
    fun `a remote branch whose local branch tracks it switches to that local branch`() = runTest(timeout = 10.seconds) {
        branches = branchLine("refs/heads/main", hash(1), upstream = "refs/remotes/origin/main") +
            branchLine("refs/heads/diğer", hash(2), current = true) +
            branchLine("refs/remotes/origin/main", hash(1))
        status = statusOutput(head = "diğer")
        exec.succeeds(switch("main"))
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.remote("origin/main")))
        runCurrent()

        assertEquals(listOf(switch("main")), actions())
    }

    @Test
    fun `a remote branch the current branch tracks needs no switch`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.remote("origin/main")))
        runCurrent()

        assertTrue(actions().isEmpty())
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `a remote branch whose name a local branch with another upstream holds is refused`() = runTest(timeout = 10.seconds) {
        branches += branchLine("refs/heads/yeni", hash(6))
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.remote("origin/yeni")))
        runCurrent()

        assertTrue(actions().isEmpty())
        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.Rejected), presenter.state.value.actionError)
    }

    @Test
    fun `a remote branch that another remote has too is created from the one tapped`() = runTest(timeout = 10.seconds) {
        branches += branchLine("refs/remotes/upstream/yeni", hash(7))
        exec.succeeds(create("yeni", "refs/remotes/upstream/yeni"))
        exec.succeeds(switch("yeni"))
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.remote("upstream/yeni")))
        runCurrent()

        assertEquals(listOf(create("yeni", "refs/remotes/upstream/yeni"), switch("yeni")), actions())
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `a branch gone since the list was read is not found`() = runTest(timeout = 10.seconds) {
        exec.fails(switch("özellik"), "fatal: invalid reference: özellik\n", exit = 128)
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.local("özellik")))
        runCurrent()

        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.NotFound), presenter.state.value.actionError)
    }

    @Test
    fun `nothing starts while another operation runs`() = runTest(timeout = 10.seconds) {
        val gate = CompletableDeferred<Unit>()
        exec.on(switch("özellik")) {
            gate.await()
            exit(0)
        }
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Switch(presenter.local("özellik")))
        runCurrent()
        presenter.onIntent(BranchIntent.Switch(presenter.local("eski")))
        presenter.onIntent(BranchIntent.Delete(presenter.local("eski")))
        presenter.onIntent(BranchIntent.StartCreate())
        runCurrent()
        assertEquals(listOf(switch("özellik")), actions())
        assertNull(presenter.state.value.dialog)

        gate.complete(Unit)
        runCurrent()
        assertNull(presenter.state.value.busy)
    }

    // Deleting

    @Test
    fun `deleting asks first then runs the safe delete`() = runTest(timeout = 10.seconds) {
        exec.on(delete("özellik")) {
            branches = branches.lines().filterNot { it.startsWith("refs/heads/özellik") }.joinToString("\n")
            exit(0)
        }
        val presenter = loaded()
        val branch = presenter.local("özellik")

        presenter.onIntent(BranchIntent.Delete(branch))
        assertEquals(BranchDialog.ConfirmDelete(branch), presenter.state.value.dialog)
        runCurrent()
        assertTrue(actions().isEmpty())

        presenter.onIntent(BranchIntent.Confirm)
        assertEquals(BranchOp.Delete, presenter.state.value.busy)
        runCurrent()

        assertEquals(listOf(delete("özellik")), actions())
        assertNull(presenter.state.value.dialog)
        assertNull(presenter.state.value.actionError)
        assertTrue(presenter.state.value.local.none { it.name == "özellik" })
    }

    @Test
    fun `an unmerged branch asks again before the forced delete`() = runTest(timeout = 10.seconds) {
        exec.fails(
            delete("özellik"),
            "error: the branch 'özellik' is not fully merged\nhint: If you are sure you want to delete it, run 'git branch -D özellik'\n",
        )
        exec.succeeds(delete("özellik", unmerged = true))
        val presenter = loaded()
        val branch = presenter.local("özellik")

        presenter.onIntent(BranchIntent.Delete(branch))
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()
        assertEquals(listOf(delete("özellik")), actions())
        assertEquals(BranchDialog.ConfirmDeleteUnmerged(branch), presenter.state.value.dialog)
        assertNull(presenter.state.value.actionError)

        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()

        assertEquals(listOf(delete("özellik"), delete("özellik", unmerged = true)), actions())
        assertNull(presenter.state.value.dialog)
        assertNull(presenter.state.value.actionError)
    }

    @Test
    fun `the older wording of an unmerged branch is recognised too`() = runTest(timeout = 10.seconds) {
        exec.fails(delete("özellik"), "error: The branch 'özellik' is not fully merged.\n")
        val presenter = loaded()
        val branch = presenter.local("özellik")

        presenter.onIntent(BranchIntent.Delete(branch))
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()

        assertEquals(BranchDialog.ConfirmDeleteUnmerged(branch), presenter.state.value.dialog)
    }

    @Test
    fun `a dismissed unmerged warning never forces the delete`() = runTest(timeout = 10.seconds) {
        exec.fails(delete("özellik"), "error: the branch 'özellik' is not fully merged\n")
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Delete(presenter.local("özellik")))
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()
        presenter.onIntent(BranchIntent.DismissDialog)
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()

        assertEquals(listOf(delete("özellik")), actions())
        assertNull(presenter.state.value.dialog)
    }

    @Test
    fun `the current branch is never deleted`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Delete(presenter.local("main")))
        runCurrent()

        assertNull(presenter.state.value.dialog)
        assertTrue(actions().isEmpty())
        assertEquals(BranchActionError(BranchOp.Delete, GitFailure.Rejected), presenter.state.value.actionError)
    }

    @Test
    fun `a remote branch is never deleted`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Delete(presenter.remote("origin/yeni")))
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()

        assertNull(presenter.state.value.dialog)
        assertTrue(actions().isEmpty())
    }

    @Test
    fun `a delete git refuses for another reason is an error`() = runTest(timeout = 10.seconds) {
        exec.fails(delete("özellik"), "error: branch 'özellik' not found\n")
        val presenter = loaded()

        presenter.onIntent(BranchIntent.Delete(presenter.local("özellik")))
        presenter.onIntent(BranchIntent.Confirm)
        runCurrent()

        assertEquals(BranchActionError(BranchOp.Delete, GitFailure.NotFound), presenter.state.value.actionError)
        assertNull(presenter.state.value.dialog)
    }

    // Failures, cancellation, navigation

    @Test
    fun `a lost connection during an action is reported`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()

        exec.failNext(SshException.Disconnected())
        presenter.onIntent(BranchIntent.Switch(presenter.local("özellik")))
        runCurrent()

        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.NotConnected), presenter.state.value.actionError)
        assertNull(presenter.state.value.busy)
    }

    @Test
    fun `cancellation stays a cancellation`() = runTest(timeout = 10.seconds) {
        val seen = CompletableDeferred<Throwable>()
        val git = object : GitConnection {
            override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T = try {
                block(HangingExec)
            } catch (error: Throwable) {
                seen.complete(error)
                throw error
            }
        }
        val presenter = presenter(git)
        runCurrent()

        backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.children.forEach { it.cancel() }
        runCurrent()

        assertIs<CancellationException>(seen.await())
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `close is sent to the graph`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        val events = eventsOf(presenter)

        presenter.onIntent(BranchIntent.Close)
        runCurrent()

        assertEquals(listOf<BranchEvent>(BranchEvent.Close), events)
    }

    @Test
    fun `state printing hides names and the folder`() = runTest(timeout = 10.seconds) {
        val presenter = loaded()
        presenter.onIntent(BranchIntent.Search("özel"))
        presenter.onIntent(BranchIntent.Delete(presenter.local("özellik")))

        val text = presenter.state.value.toString()

        listOf("özel", "main", "origin", "çalışma").forEach { assertFalse(it in text, text) }
    }
}

/** Never answers: a command that runs until cancelled. */
private object HangingExec : RemoteExec {
    override suspend fun run(request: ExecRequest, maxOutputBytes: Long): ExecResult = kotlinx.coroutines.awaitCancellation()

    override fun stream(request: ExecRequest): Flow<ExecEvent> = error("Not used")
}
