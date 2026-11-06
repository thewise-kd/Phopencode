package io.github.thewisekd.phopencode.feature.git.logic.status

import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitBranchStatus
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.StatusState
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * The presenter against real git through a local `/bin/sh`, in temporary repositories: every
 * acceptance case of P9.4 on git's real output, and staging and unstaging rows with hostile names.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class StatusRealGitTest {
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
        "a b.txt",
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

    private fun presenter(directory: Path, exec: LocalShellExec = sandbox.exec) =
        DefaultStatusPresenter(directory.absolutePathString(), ExecGitConnection(exec), scope)

    private fun DefaultStatusPresenter.awaitState(predicate: (StatusState) -> Boolean): StatusState = runBlocking {
        withTimeout(15.seconds) { state.first(predicate) }
    }

    private fun DefaultStatusPresenter.awaitLoaded(): StatusState = awaitState { !it.loading && !it.refreshing && it.pending.isEmpty() }

    /** Sends [intent] and waits until it has run and the status was read again. */
    private fun DefaultStatusPresenter.act(intent: StatusIntent): StatusState {
        onIntent(intent)
        return awaitLoaded()
    }

    private fun rows(vararg pairs: Pair<String, GitChangeKind>) = pairs.map { (path, kind) -> GitFileChange(path, kind) }

    @Test
    fun `staged, unstaged and untracked files are separated, also from a subfolder`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "edited.txt", "one\n")
        sandbox.commitFile(repo, "both.txt", "one\n")
        sandbox.commitFile(repo, "sub/deleted.txt", "one\n")
        repo.resolve("edited.txt").writeText("two\n")
        repo.resolve("both.txt").writeText("two\n")
        sandbox.git(repo, "add", "--", "both.txt")
        repo.resolve("both.txt").writeText("three\n")
        repo.resolve("added.txt").writeText("new\n")
        sandbox.git(repo, "add", "--", "added.txt")
        sandbox.git(repo, "rm", "-q", "--", "sub/deleted.txt")
        repo.resolve("sub").createDirectories()
        repo.resolve("sub/untracked.txt").writeText("u\n")
        repo.resolve(".gitignore").writeText("*.log\n")
        repo.resolve("ignored.log").writeText("i\n")

        val state = presenter(repo.resolve("sub")).awaitLoaded()

        assertNull(state.error)
        assertEquals(rows("added.txt" to GitChangeKind.Added, "both.txt" to GitChangeKind.Modified, "sub/deleted.txt" to GitChangeKind.Deleted), state.staged)
        assertEquals(rows("both.txt" to GitChangeKind.Modified, "edited.txt" to GitChangeKind.Modified), state.unstaged)
        assertEquals(rows(".gitignore" to GitChangeKind.Untracked, "sub/untracked.txt" to GitChangeKind.Untracked), state.untracked)
        assertEquals(emptyList(), state.conflicted)
        assertFalse(state.isClean)
    }

    @Test
    fun `a clean repository on a named branch`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "a\n")
        sandbox.git(repo, "switch", "-q", "-c", "özellik/çalış")
        val head = sandbox.gitText(repo, "rev-parse", "HEAD").trim()

        val state = presenter(repo).awaitLoaded()

        assertTrue(state.isClean)
        assertEquals(GitBranchStatus(head = "özellik/çalış", commit = head), state.branch)
    }

    @Test
    fun `a detached HEAD has no branch name`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "a\n")
        sandbox.commitFile(repo, "a.txt", "b\n")
        sandbox.git(repo, "switch", "-q", "--detach", "HEAD~1")
        val head = sandbox.gitText(repo, "rev-parse", "HEAD").trim()

        val branch = presenter(repo).awaitLoaded().branch!!

        assertTrue(branch.isDetached)
        assertEquals(head, branch.commit)
    }

    @Test
    fun `a repository without commits stages and unstages its first files`() {
        val repo = sandbox.newRepo()
        repo.resolve("first.txt").writeText("1\n")
        val presenter = presenter(repo)

        val loaded = presenter.awaitLoaded()
        assertEquals(GitBranchStatus(head = "main", commit = null), loaded.branch)
        assertEquals(rows("first.txt" to GitChangeKind.Untracked), loaded.untracked)

        val staged = presenter.act(StatusIntent.Stage(GitFileChange("first.txt", GitChangeKind.Untracked)))
        assertNull(staged.actionError)
        assertEquals(rows("first.txt" to GitChangeKind.Added), staged.staged)
        assertEquals(setOf("first.txt"), sandbox.stagedPaths(repo))

        val unstaged = presenter.act(StatusIntent.Unstage(GitFileChange("first.txt", GitChangeKind.Added)))
        assertNull(unstaged.actionError)
        assertEquals(rows("first.txt" to GitChangeKind.Untracked), unstaged.untracked)
        assertEquals(emptySet(), sandbox.stagedPaths(repo))
    }

    @Test
    fun `ahead and behind are counted against a remote, and a pruned upstream shows as gone`() {
        val origin = tmp.resolve("origin.git")
        sandbox.git(tmp, "init", "-q", "--bare", origin.absolutePathString())
        val seed = sandbox.newRepo("seed")
        sandbox.commitFile(seed, "a.txt", "a\n")
        sandbox.git(seed, "push", "-q", origin.absolutePathString(), "main")
        sandbox.git(tmp, "clone", "-q", origin.absolutePathString(), tmp.resolve("work").absolutePathString())
        val work = tmp.resolve("work")
        sandbox.commitFile(seed, "b.txt", "b\n")
        sandbox.commitFile(seed, "c.txt", "c\n")
        sandbox.git(seed, "push", "-q", origin.absolutePathString(), "main")
        sandbox.commitFile(work, "d.txt", "d\n")
        sandbox.git(work, "fetch", "-q")

        val branch = presenter(work).awaitLoaded().branch!!
        assertEquals("main", branch.head)
        assertEquals("origin/main", branch.upstream)
        assertEquals(1, branch.ahead)
        assertEquals(2, branch.behind)

        sandbox.git(work, "switch", "-q", "-c", "topic")
        sandbox.git(work, "push", "-q", "-u", "origin", "topic")
        assertEquals(GitBranchStatus(head = "topic", commit = branch.commit, upstream = "origin/topic"), presenter(work).awaitLoaded().branch)
        sandbox.git(work, "push", "-q", "origin", "--delete", "topic")
        sandbox.git(work, "fetch", "-q", "--prune")

        val gone = presenter(work).awaitLoaded().branch!!
        assertEquals(
            GitBranchStatus(head = "topic", commit = branch.commit, upstream = "origin/topic", upstreamGone = true),
            gone,
        )
        assertTrue(gone.aheadBehindKnown)
    }

    @Test
    fun `a merge conflict is its own group and staging it marks it resolved`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "c.txt", "base\n")
        sandbox.git(repo, "switch", "-q", "-c", "other")
        sandbox.commitFile(repo, "c.txt", "theirs\n")
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.commitFile(repo, "c.txt", "ours\n")
        assertTrue(sandbox.gitExit(repo, "merge", "-q", "--no-edit", "other") != 0)
        val presenter = presenter(repo)

        val conflicted = presenter.awaitLoaded()
        assertEquals(rows("c.txt" to GitChangeKind.Unmerged), conflicted.conflicted)
        assertEquals(emptyList(), conflicted.staged)
        assertEquals(emptyList(), conflicted.unstaged)

        // Stage all leaves the conflict alone.
        presenter.onIntent(StatusIntent.StageAll)
        assertEquals(rows("c.txt" to GitChangeKind.Unmerged), presenter.awaitLoaded().conflicted)

        repo.resolve("c.txt").writeText("resolved\n")
        val resolved = presenter.act(StatusIntent.Stage(GitFileChange("c.txt", GitChangeKind.Unmerged)))
        assertNull(resolved.actionError)
        assertEquals(emptyList(), resolved.conflicted)
        assertEquals(rows("c.txt" to GitChangeKind.Modified), resolved.staged)
    }

    @Test
    fun `rows with hostile names are staged and unstaged as exactly themselves`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "base.txt", "base\n")
        hostileNames.forEach { repo.resolve(it).writeText("content\n") }
        repo.resolve("other.txt").writeText("stays untracked\n")
        val presenter = presenter(repo)

        val loaded = presenter.awaitLoaded()
        assertEquals((hostileNames + "other.txt").toSet(), loaded.untracked.map { it.path }.toSet())

        for (name in hostileNames) {
            val state = presenter.act(StatusIntent.Stage(GitFileChange(name, GitChangeKind.Untracked)))
            assertNull(state.actionError, name)
            assertTrue(GitFileChange(name, GitChangeKind.Added) in state.staged, name)
        }
        assertEquals(hostileNames.toSet(), sandbox.stagedPaths(repo))
        assertEquals(setOf("other.txt"), sandbox.untrackedPaths(repo))

        for (name in hostileNames) {
            val state = presenter.act(StatusIntent.Unstage(GitFileChange(name, GitChangeKind.Added)))
            assertNull(state.actionError, name)
            assertTrue(GitFileChange(name, GitChangeKind.Untracked) in state.untracked, name)
        }
        assertEquals(emptySet(), sandbox.stagedPaths(repo))
        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `stage all and unstage all move every row, also past one command's worth of paths`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "tracked.txt", "1\n")
        repo.resolve("tracked.txt").writeText("2\n")
        val folder = repo.resolve("a-folder-with-a-rather-long-name-to-fill-the-command-line").createDirectories()
        val many = (1..800).map { "file-number-$it-with-some-padding-to-make-the-name-long-enough.txt" }
        many.forEach { folder.resolve(it).writeText(it) }
        hostileNames.forEach { repo.resolve(it).writeText("h\n") }
        val presenter = presenter(repo)
        presenter.awaitLoaded()

        val staged = presenter.act(StatusIntent.StageAll)
        assertNull(staged.actionError)
        assertEquals(0, staged.unstaged.size + staged.untracked.size)
        assertEquals(many.size + hostileNames.size + 1, staged.staged.size)
        assertEquals(emptySet(), sandbox.untrackedPaths(repo))

        val unstaged = presenter.act(StatusIntent.UnstageAll)
        assertNull(unstaged.actionError)
        assertEquals(emptyList(), unstaged.staged)
        assertEquals(rows("tracked.txt" to GitChangeKind.Modified), unstaged.unstaged)
        assertEquals(many.size + hostileNames.size, unstaged.untracked.size)
        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `unstaging a rename restores both of its paths`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "old name.txt", "same content for rename detection\n")
        sandbox.git(repo, "mv", "--", "old name.txt", "-new name.txt")
        val presenter = presenter(repo)

        val loaded = presenter.awaitLoaded()
        val rename = GitFileChange("-new name.txt", GitChangeKind.Renamed, originalPath = "old name.txt")
        assertEquals(listOf(rename), loaded.staged)

        val state = presenter.act(StatusIntent.Unstage(rename))
        assertNull(state.actionError)
        assertEquals(emptyList(), state.staged)
        assertEquals(rows("old name.txt" to GitChangeKind.Deleted), state.unstaged)
        assertEquals(rows("-new name.txt" to GitChangeKind.Untracked), state.untracked)
    }

    @Test
    fun `a file name that is not UTF-8 is shown but refused, and stage all stages the rest`() {
        val repo = sandbox.newRepo()
        // The name is made by the shell from bytes; Java cannot name it portably.
        val made = sandbox.run(repo, listOf("/bin/sh", "-c", "printf x > \"\$(printf 'bad\\377.txt')\""))
        assertEquals(0, made.exitCode)
        repo.resolve("good.txt").writeText("g\n")
        val presenter = presenter(repo)

        val loaded = presenter.awaitLoaded()
        val bad = loaded.untracked.single { '�' in it.path }

        presenter.onIntent(StatusIntent.Stage(bad))
        assertEquals(GitFailure.Rejected, presenter.awaitState { it.actionError != null }.actionError)

        presenter.onIntent(StatusIntent.DismissError)
        val state = presenter.act(StatusIntent.StageAll)
        assertEquals(GitFailure.Rejected, state.actionError)
        assertEquals(rows("good.txt" to GitChangeKind.Added), state.staged)
        assertEquals(listOf(bad), state.untracked)
    }

    @Test
    fun `a folder outside any repository is not a repository`() {
        val plain = tmp.resolve("plain").createDirectories()

        val state = presenter(plain).awaitLoaded()

        assertEquals(GitFailure.NotRepository, state.error)
    }

    @Test
    fun `a folder that does not exist is not found`() {
        val state = presenter(tmp.resolve("missing")).awaitLoaded()

        assertEquals(GitFailure.NotFound, state.error)
    }

    @Test
    fun `git missing from the path is reported as missing`() {
        val repo = sandbox.newRepo()
        val noGit = LocalShellExec(sandbox.cwd, sandbox.environment + ("PATH" to tmp.resolve("empty-bin").absolutePathString()))

        val state = presenter(repo, noGit).awaitLoaded()

        assertEquals(GitFailure.GitMissing, state.error)
    }

    @Test
    fun `a refresh sees changes made meanwhile`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "a\n")
        val presenter = presenter(repo)
        assertTrue(presenter.awaitLoaded().isClean)

        repo.resolve("a.txt").writeText("changed\n")
        presenter.onIntent(StatusIntent.Refresh)

        val state = presenter.awaitState { !it.refreshing && it.unstaged.isNotEmpty() }
        assertEquals(rows("a.txt" to GitChangeKind.Modified), state.unstaged)
    }
}
