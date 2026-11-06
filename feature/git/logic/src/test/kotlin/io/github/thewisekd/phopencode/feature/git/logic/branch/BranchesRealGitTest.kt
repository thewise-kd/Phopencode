package io.github.thewisekd.phopencode.feature.git.logic.branch

import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchActionError
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchDialog
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchNameProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.BranchesState
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranch
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.log.LogSandbox
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Real git, real `/bin/sh`, temporary repositories, through the presenter. */
@Timeout(30)
class BranchesRealGitTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: LogSandbox
    private lateinit var repo: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        sandbox = LogSandbox(tmp)
        repo = sandbox.newRepo()
        sandbox.commitFile(repo, "dosya.txt", "ilk\n", "ilk")
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun bounded(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(10.seconds) { block() } }

    private fun presenter(directory: Path = repo) = DefaultBranchesPresenter(directory.absolutePathString(), sandbox.git, scope)

    private suspend fun DefaultBranchesPresenter.awaitIdle(): BranchesState = state.first { !it.loading && it.busy == null }

    private fun DefaultBranchesPresenter.events(): Channel<BranchEvent> {
        val channel = Channel<BranchEvent>(Channel.UNLIMITED)
        scope.launch { events.collect { channel.send(it) } }
        return channel
    }

    private fun BranchesState.localNamed(name: String): GitBranch = local.single { it.name == name }

    private fun currentBranch(): String = sandbox.git(repo, "branch", "--show-current").trim()

    private fun localBranches(): List<String> =
        sandbox.git(repo, "for-each-ref", "--format=%(refname:short)", "refs/heads").lines().filter { it.isNotEmpty() }

    /** Clones [repo] through a bare copy, so that the clone has remote branches. */
    private fun cloneWithRemote(): Path {
        sandbox.git(repo, "branch", "uzak-dal")
        val bare = tmp.resolve("uzak.git")
        sandbox.git(tmp, "clone", "-q", "--bare", repo.absolutePathString(), bare.absolutePathString())
        val clone = tmp.resolve("klon")
        sandbox.git(tmp, "clone", "-q", bare.absolutePathString(), clone.absolutePathString())
        return clone
    }

    @Test
    fun `local and remote branches are listed with the current one`() = bounded {
        val clone = cloneWithRemote()

        val state = presenter(clone).awaitIdle()

        assertNull(state.error)
        assertEquals(listOf("main"), state.local.map { it.name })
        assertEquals(listOf("origin/main", "origin/uzak-dal"), state.remote.map { it.name })
        assertEquals("main", state.current?.name)
        assertEquals("origin/main", state.current?.upstream)
        assertFalse(state.dirty)
    }

    @Test
    fun `switching to a remote branch creates a tracking local branch`() = bounded {
        val clone = cloneWithRemote()
        val presenter = presenter(clone)
        val events = presenter.events()
        val state = presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Switch(state.remote.single { it.name == "origin/uzak-dal" }))

        assertEquals(BranchEvent.Switched, events.receive())
        val after = presenter.state.first { s -> !s.loading && s.busy == null && s.current?.name == "uzak-dal" }
        assertEquals("origin/uzak-dal", after.current?.upstream)
        assertEquals("uzak-dal", sandbox.git(clone, "branch", "--show-current").trim())
    }

    @Test
    fun `a tag with the remote branch's name does not stand in for it`() = bounded {
        val clone = cloneWithRemote()
        // A tag `uzak-dal` on another commit than origin/uzak-dal: git's guess on the bare name would
        // take the tag and refuse to switch.
        sandbox.commitFile(clone, "etiket.txt", "etiket\n", "etiket")
        sandbox.git(clone, "tag", "uzak-dal")
        val remoteTip = sandbox.git(clone, "rev-parse", "refs/remotes/origin/uzak-dal").trim()
        val presenter = presenter(clone)
        val events = presenter.events()
        val state = presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Switch(state.remote.single { it.name == "origin/uzak-dal" }))

        assertEquals(BranchEvent.Switched, events.receive())
        val after = presenter.state.first { s -> !s.loading && s.busy == null && s.current?.name == "uzak-dal" }
        assertNull(after.actionError)
        assertEquals("origin/uzak-dal", after.current?.upstream)
        assertEquals("uzak-dal", sandbox.git(clone, "branch", "--show-current").trim())
        assertEquals(remoteTip, sandbox.git(clone, "rev-parse", "HEAD").trim())
        assertEquals("refs/remotes/origin/uzak-dal", sandbox.git(clone, "rev-parse", "--symbolic-full-name", "uzak-dal@{upstream}").trim())
    }

    @Test
    fun `search filters the lists`() = bounded {
        sandbox.git(repo, "branch", "özellik/arama")
        sandbox.git(repo, "branch", "başka")
        val presenter = presenter()
        presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Search("ARA"))

        assertEquals(listOf("özellik/arama"), presenter.state.value.shownLocal.map { it.name })
    }

    @Test
    fun `create with a valid Turkish name and switch to it`() = bounded {
        val presenter = presenter()
        val events = presenter.events()
        presenter.awaitIdle()

        presenter.onIntent(BranchIntent.StartCreate())
        presenter.onIntent(BranchIntent.EditName("özellik/çğışöü"))
        assertNull(assertIs<BranchDialog.Create>(presenter.state.value.dialog).problem)
        presenter.onIntent(BranchIntent.SubmitCreate)

        assertEquals(BranchEvent.Switched, events.receive())
        val state = presenter.state.first { !it.loading && it.busy == null && it.current?.name == "özellik/çğışöü" }
        assertNull(state.dialog)
        assertNull(state.actionError)
        assertEquals("özellik/çğışöü", currentBranch())
    }

    @Test
    fun `invalid names are refused before git runs`() = bounded {
        val presenter = presenter()
        presenter.awaitIdle()
        val before = sandbox.exec.commands.size

        presenter.onIntent(BranchIntent.StartCreate())
        for (name in listOf("-D", "--force", "a..b", "iki kelime", " baştaboşluk", "main")) {
            presenter.onIntent(BranchIntent.EditName(name))
            val dialog = assertIs<BranchDialog.Create>(presenter.state.value.dialog)
            val expected = if (name == "main") BranchNameProblem.Taken else BranchNameProblem.Invalid
            assertEquals(expected, dialog.problem, "problem for a name")
            presenter.onIntent(BranchIntent.SubmitCreate)
        }

        assertEquals(before, sandbox.exec.commands.size)
        assertEquals(listOf("main"), localBranches())
    }

    @Test
    fun `switch on a clean tree`() = bounded {
        sandbox.git(repo, "branch", "diğer")
        val presenter = presenter()
        val events = presenter.events()
        val state = presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Switch(state.localNamed("diğer")))

        assertEquals(BranchEvent.Switched, events.receive())
        presenter.state.first { it.busy == null && it.current?.name == "diğer" }
        assertEquals("diğer", currentBranch())
    }

    @Test
    fun `a dirty tree warns and a refused switch leaves the files intact`() = bounded {
        sandbox.git(repo, "switch", "-q", "-c", "diğer")
        sandbox.commitFile(repo, "dosya.txt", "diğer dalda\n", "diğer")
        sandbox.git(repo, "switch", "-q", "main")
        val file = repo.resolve("dosya.txt")
        file.writeText("kaydedilmemiş\n")
        val presenter = presenter()
        val state = presenter.awaitIdle()
        assertTrue(state.dirty)
        val target = state.localNamed("diğer")

        presenter.onIntent(BranchIntent.Switch(target))
        assertEquals(BranchDialog.ConfirmSwitchDirty(target), presenter.state.value.dialog)
        presenter.onIntent(BranchIntent.Confirm)

        val after = presenter.state.first { it.actionError != null }
        assertEquals(BranchActionError(BranchOp.Switch, GitFailure.WouldOverwrite), after.actionError)
        assertEquals("kaydedilmemiş\n", file.readText())
        assertEquals("main", currentBranch())
        assertTrue(sandbox.exec.commands.none { "--force" in it || "--discard-changes" in it || "-f" in it.split(' ') })
    }

    @Test
    fun `a dirty tree that does not conflict switches and keeps the change`() = bounded {
        sandbox.git(repo, "branch", "diğer")
        val file = repo.resolve("dosya.txt")
        file.writeText("kaydedilmemiş\n")
        val presenter = presenter()
        val events = presenter.events()
        val state = presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Switch(state.localNamed("diğer")))
        presenter.onIntent(BranchIntent.Confirm)

        assertEquals(BranchEvent.Switched, events.receive())
        assertEquals("kaydedilmemiş\n", file.readText())
        assertEquals("diğer", currentBranch())
    }

    @Test
    fun `delete a merged branch`() = bounded {
        sandbox.git(repo, "branch", "birleşmiş")
        val presenter = presenter()
        val state = presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Delete(state.localNamed("birleşmiş")))
        presenter.onIntent(BranchIntent.Confirm)

        val after = presenter.state.first { s -> s.busy == null && !s.loading && s.local.none { it.name == "birleşmiş" } }
        assertNull(after.actionError)
        assertNull(after.dialog)
        assertEquals(listOf("main"), localBranches())
    }

    @Test
    fun `an unmerged delete needs confirmation then succeeds`() = bounded {
        sandbox.git(repo, "switch", "-q", "-c", "birleşmemiş")
        sandbox.commitFile(repo, "yeni.txt", "yeni\n", "yeni")
        sandbox.git(repo, "switch", "-q", "main")
        val presenter = presenter()
        val state = presenter.awaitIdle()
        val branch = state.localNamed("birleşmemiş")

        presenter.onIntent(BranchIntent.Delete(branch))
        presenter.onIntent(BranchIntent.Confirm)

        val asked = presenter.state.first { it.dialog is BranchDialog.ConfirmDeleteUnmerged }
        assertEquals(BranchDialog.ConfirmDeleteUnmerged(branch), asked.dialog)
        assertTrue("birleşmemiş" in localBranches())

        presenter.onIntent(BranchIntent.Confirm)

        presenter.state.first { s -> s.busy == null && s.local.none { it.name == "birleşmemiş" } }
        assertEquals(listOf("main"), localBranches())
    }

    @Test
    fun `the current branch cannot be deleted`() = bounded {
        val presenter = presenter()
        val state = presenter.awaitIdle()

        presenter.onIntent(BranchIntent.Delete(state.localNamed("main")))

        assertEquals(BranchActionError(BranchOp.Delete, GitFailure.Rejected), presenter.state.value.actionError)
        assertEquals(listOf("main"), localBranches())
    }

    @Test
    fun `a detached HEAD lists branches without a current one and can switch back`() = bounded {
        sandbox.git(repo, "switch", "-q", "--detach")
        val presenter = presenter()
        val events = presenter.events()
        val state = presenter.awaitIdle()

        assertNull(state.error)
        assertEquals(listOf("main"), state.local.map { it.name })
        assertNull(state.current)

        presenter.onIntent(BranchIntent.Switch(state.localNamed("main")))

        assertEquals(BranchEvent.Switched, events.receive())
        assertEquals("main", currentBranch())
    }

    @Test
    fun `a folder outside any repository is an error`() = bounded {
        val plain = tmp.resolve("düz klasör").toFile().apply { mkdirs() }.toPath()

        val state = presenter(plain).awaitIdle()

        assertEquals(GitFailure.NotRepository, state.error)
    }
}
