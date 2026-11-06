package io.github.thewisekd.phopencode.feature.git.logic.commit

import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitError
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitMessageProblem
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitState
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.status.RepoSandbox
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The presenter against real git through a local `/bin/sh`, in temporary repositories: committing,
 * hooks, staging, and the checked discard, with hostile file names and Turkish messages.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CommitRealGitTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: RepoSandbox
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val hostileNames = listOf(
        "\$(touch pwned).txt",
        "`touch pwned2`.txt",
        "-rf",
        "--all",
        "new\nline.txt",
        "it's \"q\".txt",
        "çğıöşü İI.txt",
        "*.txt",
        ":(top)magic.txt",
        "semi;colon&amp|pipe.txt",
    )

    @BeforeTest
    fun setUp() {
        sandbox = RepoSandbox(tmp)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun presenter(directory: Path) =
        DefaultCommitPresenter(directory.absolutePathString(), ExecGitConnection(sandbox.exec), scope)

    private fun DefaultCommitPresenter.awaitState(predicate: (CommitState) -> Boolean): CommitState = runBlocking {
        withTimeout(15.seconds) { state.first(predicate) }
    }

    private fun DefaultCommitPresenter.awaitIdle(): CommitState = awaitState { !it.loading && it.busy == null }

    /** Sends [intent] and waits until it has run and the files were read again. */
    private fun DefaultCommitPresenter.act(intent: CommitIntent): CommitState {
        onIntent(intent)
        return awaitIdle()
    }

    private fun DefaultCommitPresenter.nextEvent(): CommitEvent = runBlocking { withTimeout(15.seconds) { events.first() } }

    private fun head(repo: Path): String = sandbox.gitText(repo, "rev-parse", "HEAD").trim()

    private fun commitCommandsRun(): Int = sandbox.exec.commands.count { "'commit'" in it }

    private fun writeHook(repo: Path, name: String, script: String) {
        val hook = repo.resolve(".git/hooks/$name")
        hook.parent.createDirectories()
        hook.writeText(script)
        hook.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun repoWithEdit(): Path {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "one\n")
        repo.resolve("a.txt").writeText("two\n")
        return repo
    }

    @Test
    fun `stage, then commit with a Turkish message, from a subfolder`() {
        val repo = repoWithEdit()
        repo.resolve("sub").createDirectories()
        val presenter = presenter(repo.resolve("sub"))
        val loaded = presenter.awaitIdle()
        assertEquals(listOf(GitFileChange("a.txt", GitChangeKind.Modified)), loaded.unstaged)

        val staged = presenter.act(CommitIntent.Stage(loaded.unstaged.single()))
        assertEquals(listOf(GitFileChange("a.txt", GitChangeKind.Modified)), staged.staged)
        assertEquals(emptyList(), staged.unstaged)

        val message = "Düzeltme: ğüşıöç İĞÜŞÖÇ\n\nAyrıntı satırı \$(touch pwned) `id`"
        presenter.onIntent(CommitIntent.EditMessage(message))
        assertTrue(presenter.state.value.canCommit)
        presenter.onIntent(CommitIntent.Commit)

        val event = presenter.nextEvent()
        val done = presenter.awaitIdle()
        assertEquals(CommitEvent.Committed(head(repo)), event)
        assertNull(done.error)
        assertEquals("", done.message)
        assertEquals(emptyList(), done.staged)
        // The raw commit object ends with the message exactly as sent, plus the newline git adds.
        assertTrue(sandbox.gitText(repo, "cat-file", "commit", "HEAD").endsWith("\n\n" + message + "\n"))
        assertTrue(sandbox.exec.commands.none { "pwned" in it || "Düzeltme" in it })
        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `unstaging keeps the work tree`() {
        val repo = repoWithEdit()
        sandbox.git(repo, "add", "--", "a.txt")
        val presenter = presenter(repo)
        val loaded = presenter.awaitIdle()

        val after = presenter.act(CommitIntent.Unstage(loaded.staged.single()))

        assertEquals(emptyList(), after.staged)
        assertEquals(listOf(GitFileChange("a.txt", GitChangeKind.Modified)), after.unstaged)
        assertEquals("two\n", repo.resolve("a.txt").readText())
    }

    @Test
    fun `a blank message is refused without running git commit`() {
        val repo = repoWithEdit()
        sandbox.git(repo, "add", "--", "a.txt")
        val before = head(repo)
        val presenter = presenter(repo)
        presenter.awaitIdle()

        presenter.onIntent(CommitIntent.EditMessage(" \n\t"))
        presenter.onIntent(CommitIntent.Commit)

        assertEquals(CommitMessageProblem.Blank, presenter.state.value.messageProblem)
        assertNull(presenter.state.value.busy)
        assertEquals(0, commitCommandsRun())
        assertEquals(before, head(repo))
    }

    @Test
    fun `a commit with nothing staged is refused`() {
        val repo = repoWithEdit()
        val before = head(repo)
        val presenter = presenter(repo)
        presenter.awaitIdle()

        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        presenter.onIntent(CommitIntent.Commit)

        assertEquals(CommitError.NothingStaged, presenter.state.value.error)
        assertEquals(0, commitCommandsRun())
        assertEquals(before, head(repo))
    }

    @Test
    fun `a commit after someone else unstaged everything is NothingStaged`() {
        val repo = repoWithEdit()
        sandbox.git(repo, "add", "--", "a.txt")
        val before = head(repo)
        val presenter = presenter(repo)
        presenter.awaitIdle()
        // Another git client unstages behind the screen's back; restore --staged keeps the work tree.
        sandbox.git(repo, "restore", "--staged", "--", "a.txt")

        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        val after = presenter.act(CommitIntent.Commit)

        assertEquals(CommitError.NothingStaged, after.error)
        assertEquals(before, head(repo))
        assertEquals(emptyList(), after.staged)
    }

    @Test
    fun `a failing pre-commit hook shows its output and commits nothing`() {
        val repo = repoWithEdit()
        sandbox.git(repo, "add", "--", "a.txt")
        writeHook(repo, "pre-commit", "#!/bin/sh\necho 'lint: trailing space in a.txt'\necho 'hata: düzelt' >&2\nexit 1\n")
        val before = head(repo)
        val presenter = presenter(repo)
        presenter.awaitIdle()

        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        val after = presenter.act(CommitIntent.Commit)

        val error = assertIs<CommitError.HookRejected>(after.error)
        assertTrue("lint: trailing space in a.txt" in error.output)
        assertTrue("hata: düzelt" in error.output)
        assertEquals("Fix", after.message)
        assertEquals(before, head(repo))
        assertEquals(listOf(GitFileChange("a.txt", GitChangeKind.Modified)), after.staged)
    }

    @Test
    fun `a commit-msg hook can refuse the message`() {
        val repo = repoWithEdit()
        sandbox.git(repo, "add", "--", "a.txt")
        writeHook(repo, "commit-msg", "#!/bin/sh\ngrep -q '^feat: ' \"\$1\" || { echo 'message must start with feat:'; exit 1; }\n")
        val before = head(repo)
        val presenter = presenter(repo)
        presenter.awaitIdle()

        presenter.onIntent(CommitIntent.EditMessage("Fix"))
        val refused = presenter.act(CommitIntent.Commit)

        assertEquals(CommitError.HookRejected("message must start with feat:"), refused.error)
        assertEquals(before, head(repo))

        presenter.onIntent(CommitIntent.DismissError)
        presenter.onIntent(CommitIntent.EditMessage("feat: add a"))
        presenter.onIntent(CommitIntent.Commit)
        val event = presenter.nextEvent()
        presenter.awaitIdle()

        assertNotEquals(before, head(repo))
        assertEquals(CommitEvent.Committed(head(repo)), event)
    }

    @Test
    fun `the first commit of a repository`() {
        val repo = sandbox.newRepo()
        repo.resolve("first.txt").writeText("x\n")
        val presenter = presenter(repo)
        val loaded = presenter.awaitIdle()
        assertEquals(listOf(GitFileChange("first.txt", GitChangeKind.Untracked)), loaded.unstaged)

        presenter.act(CommitIntent.StageAll)
        presenter.onIntent(CommitIntent.EditMessage("İlk"))
        presenter.onIntent(CommitIntent.Commit)

        val committed = presenter.nextEvent()
        assertEquals(CommitEvent.Committed(head(repo)), committed)
    }

    @Test
    fun `discard needs the confirmation and restores a modified file`() {
        val repo = repoWithEdit()
        val presenter = presenter(repo)
        val row = presenter.awaitIdle().unstaged.single()

        presenter.onIntent(CommitIntent.RequestDiscard(row))
        assertEquals(row, presenter.state.value.confirmDiscard)
        presenter.onIntent(CommitIntent.CancelDiscard)
        assertEquals("two\n", repo.resolve("a.txt").readText())

        presenter.onIntent(CommitIntent.RequestDiscard(row))
        val after = presenter.act(CommitIntent.ConfirmDiscard)

        assertNull(after.error)
        assertEquals("one\n", repo.resolve("a.txt").readText())
        assertEquals(emptyList(), after.unstaged)
    }

    @Test
    fun `discard brings back a deleted file and keeps the staged part`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "one\n")
        sandbox.commitFile(repo, "gone.txt", "g\n")
        repo.resolve("a.txt").writeText("staged\n")
        sandbox.git(repo, "add", "--", "a.txt")
        repo.resolve("a.txt").writeText("unstaged\n")
        repo.resolve("gone.txt").toFile().delete()
        val presenter = presenter(repo)
        val loaded = presenter.awaitIdle()

        for (row in loaded.unstaged) {
            presenter.onIntent(CommitIntent.RequestDiscard(row))
            assertNull(presenter.act(CommitIntent.ConfirmDiscard).error)
        }

        assertEquals("staged\n", repo.resolve("a.txt").readText())
        assertEquals("g\n", repo.resolve("gone.txt").readText())
        assertEquals(listOf(GitFileChange("a.txt", GitChangeKind.Modified)), presenter.state.value.staged)
    }

    @Test
    fun `discard is refused for an untracked file`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "one\n")
        repo.resolve("new.txt").writeText("keep me\n")
        val presenter = presenter(repo)
        val row = presenter.awaitIdle().unstaged.single()
        assertEquals(GitChangeKind.Untracked, row.kind)

        presenter.onIntent(CommitIntent.RequestDiscard(row))
        presenter.onIntent(CommitIntent.ConfirmDiscard)
        val after = presenter.awaitIdle()

        assertEquals(CommitError.Failed(CommitOp.Discard, GitFailure.Rejected), after.error)
        assertNull(after.confirmDiscard)
        assertEquals("keep me\n", repo.resolve("new.txt").readText())
        assertTrue(sandbox.exec.commands.none { "checkout-index" in it })
    }

    @Test
    fun `discard is refused for an unmerged file`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "c.txt", "base\n")
        sandbox.git(repo, "switch", "-q", "-c", "other")
        sandbox.commitFile(repo, "c.txt", "other\n")
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.commitFile(repo, "c.txt", "main\n")
        assertNotEquals(0, sandbox.gitExit(repo, "merge", "-q", "other"))
        val conflicted = repo.resolve("c.txt").readText()
        val presenter = presenter(repo)
        val row = presenter.awaitIdle().unstaged.single()
        assertEquals(GitFileChange("c.txt", GitChangeKind.Unmerged), row)

        presenter.onIntent(CommitIntent.RequestDiscard(row))
        presenter.onIntent(CommitIntent.ConfirmDiscard)
        val after = presenter.awaitIdle()

        assertEquals(CommitError.Failed(CommitOp.Discard, GitFailure.Rejected), after.error)
        assertEquals(conflicted, repo.resolve("c.txt").readText())
        assertTrue(sandbox.exec.commands.none { "checkout-index" in it })
    }

    @Test
    fun `a commit with unmerged files is a conflict`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "c.txt", "base\n")
        sandbox.git(repo, "switch", "-q", "-c", "other")
        sandbox.commitFile(repo, "c.txt", "other\n")
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.commitFile(repo, "c.txt", "main\n")
        assertNotEquals(0, sandbox.gitExit(repo, "merge", "-q", "other"))
        repo.resolve("d.txt").writeText("d\n")
        sandbox.git(repo, "add", "--", "d.txt")
        val presenter = presenter(repo)
        presenter.awaitIdle()

        presenter.onIntent(CommitIntent.EditMessage("Merge"))
        val after = presenter.act(CommitIntent.Commit)

        assertEquals(CommitError.Failed(CommitOp.Commit, GitFailure.Conflict), after.error)
    }

    @Test
    fun `hostile file names are staged, committed, unstaged and discarded as themselves`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "base.txt", "base\n")
        for (name in hostileNames) repo.resolve(name).writeText("new\n")
        val presenter = presenter(repo)
        val loaded = presenter.awaitIdle()
        assertEquals(hostileNames.toSet(), loaded.unstaged.map { it.path }.toSet())

        val staged = presenter.act(CommitIntent.StageAll)
        assertNull(staged.error)
        assertEquals(hostileNames.toSet(), sandbox.stagedPaths(repo))

        val unstaged = presenter.act(CommitIntent.UnstageAll)
        assertNull(unstaged.error)
        assertEquals(emptySet(), sandbox.stagedPaths(repo))

        presenter.act(CommitIntent.StageAll)
        presenter.onIntent(CommitIntent.EditMessage("Garip adlar"))
        presenter.onIntent(CommitIntent.Commit)
        val committed = presenter.nextEvent()
        assertEquals(CommitEvent.Committed(head(repo)), committed)
        presenter.awaitIdle()
        val tracked = sandbox.gitList(repo, "ls-files", "-z").map { String(it, Charsets.UTF_8) }.toSet()
        assertEquals(hostileNames.toSet() + "base.txt", tracked)

        for (name in hostileNames) repo.resolve(name).writeText("changed\n")
        presenter.onIntent(CommitIntent.Refresh)
        val changed = presenter.awaitState { it.busy == null && it.unstaged.size == hostileNames.size }
        for (row in changed.unstaged) {
            presenter.onIntent(CommitIntent.RequestDiscard(row))
            assertNull(presenter.act(CommitIntent.ConfirmDiscard).error)
        }
        for (name in hostileNames) assertEquals("new\n", repo.resolve(name).readText())
        assertEquals(emptyList(), sandbox.pwnedFiles())
        assertFalse(sandbox.cwd.resolve("pwned").exists())
    }
}
