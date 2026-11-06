package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailState
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.io.TempDir

/** Real git, real `/bin/sh`, temporary repositories, through the presenters. */
class LogRealGitTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: LogSandbox
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @BeforeTest
    fun setUp() {
        sandbox = LogSandbox(tmp)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun <S> StateFlow<S>.awaitState(condition: (S) -> Boolean): S = withTimeout(60.seconds) { first(condition) }

    private suspend fun StateFlow<LogState>.awaitLoaded(): LogState = awaitState { !it.loading && !it.loadingMore }

    private suspend fun StateFlow<CommitDetailState>.awaitDetail(): CommitDetailState = awaitState { !it.loading }

    private fun log(repo: Path, pageSize: Int = 5) = DefaultLogPresenter(repo.absolutePathString(), sandbox.git, scope, pageSize)

    private fun detail(repo: Path, hash: String) = DefaultCommitDetailPresenter(repo.absolutePathString(), hash, sandbox.git, scope)

    private suspend fun DefaultLogPresenter.loadAll(): LogState {
        var state = state.awaitLoaded()
        while (!state.endReached) {
            assertNull(state.error)
            assertNull(state.moreError)
            val size = state.commits.size
            onIntent(LogIntent.LoadMore)
            state = this.state.awaitState { !it.loadingMore && (it.commits.size > size || it.endReached || it.moreError != null) }
        }
        return state
    }

    @Test
    fun `pages walk the whole history in order`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        repeat(12) { sandbox.commitFile(repo, "dosya.txt", "satır $it\n", "commit $it") }
        val presenter = log(repo)

        val first = presenter.state.awaitLoaded()
        assertEquals(5, first.commits.size)
        assertFalse(first.endReached)

        presenter.loadAll()
        // The branch list loads on its own; wait for it before reading branches and refs.
        val all = presenter.state.awaitState { it.endReached && it.branches.isNotEmpty() }
        assertEquals(sandbox.revList(repo), all.commits.map { it.hash })
        assertEquals("commit 11", all.commits.first().subject)
        assertEquals("commit 0", all.commits.last().subject)
        assertTrue(all.commits.last().parents.isEmpty())
        assertEquals(listOf("main"), all.commits.first().refs)
        assertEquals(listOf("main"), all.branches)
    }

    @Test
    fun `commits added after the first page shift nothing`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        repeat(8) { sandbox.commitFile(repo, "a.txt", "$it\n", "eski $it") }
        val before = sandbox.revList(repo)
        val presenter = log(repo, pageSize = 3)
        presenter.state.awaitLoaded()

        repeat(4) { sandbox.commitFile(repo, "a.txt", "new $it\n", "yeni $it") }
        val all = presenter.loadAll()

        assertEquals(before, all.commits.map { it.hash })
    }

    @Test
    fun `filtering to a branch shows its commits and starts over`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        repeat(3) { sandbox.commitFile(repo, "a.txt", "$it\n", "ana $it") }
        sandbox.git(repo, "switch", "-q", "-c", "özellik/çalışma")
        repeat(4) { sandbox.commitFile(repo, "b.txt", "$it\n", "özellik $it") }
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.git(repo, "tag", "main", "HEAD~2")
        repo.resolve("özellik").toFile().writeText("not a branch\n")
        val presenter = log(repo, pageSize = 2)

        presenter.loadAll()
        val main = presenter.state.awaitState { it.endReached && it.branches.isNotEmpty() }
        assertEquals(sandbox.revList(repo, "refs/heads/main"), main.commits.map { it.hash })
        assertEquals(listOf("main", "özellik/çalışma"), main.branches)

        presenter.onIntent(LogIntent.FilterBranch("özellik/çalışma"))
        assertTrue(presenter.state.value.loading)
        val feature = presenter.loadAll()
        assertEquals("özellik/çalışma", feature.branch)
        assertEquals(sandbox.revList(repo, "refs/heads/özellik/çalışma"), feature.commits.map { it.hash })
        assertEquals("özellik 3", feature.commits.first().subject)

        // The tag `main` names an older commit: the filter still means the branch.
        presenter.onIntent(LogIntent.FilterBranch("main"))
        val back = presenter.loadAll()
        assertNull(back.error)
        assertEquals(sandbox.revList(repo, "refs/heads/main"), back.commits.map { it.hash })
        assertEquals(3, back.commits.size)

        presenter.onIntent(LogIntent.FilterBranch("yok"))
        assertEquals(GitFailure.NotFound, presenter.state.awaitLoaded().error)
    }

    @Test
    fun `a detached head still lists branches and history`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        repeat(3) { sandbox.commitFile(repo, "a.txt", "$it\n", "c $it") }
        sandbox.git(repo, "switch", "-q", "--detach", "HEAD~1")
        val presenter = log(repo)

        val state = presenter.state.awaitState { !it.loading && it.branches.isNotEmpty() }

        assertNull(state.error)
        assertEquals(listOf("main"), state.branches)
        assertEquals(sandbox.revList(repo), state.commits.map { it.hash })
    }

    @Test
    fun `an empty repository is empty`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()

        val state = log(repo).state.awaitLoaded()

        assertTrue(state.isEmpty)
        assertNull(state.error)
        assertEquals(emptyList(), state.branches)
    }

    @Test
    fun `a folder outside any repository is not a repository`() = runBlocking<Unit> {
        val folder = tmp.resolve("düz klasör").createDirectories()

        assertEquals(GitFailure.NotRepository, log(folder).state.awaitLoaded().error)
        assertEquals(GitFailure.NotRepository, log(tmp.resolve("yok")).state.awaitLoaded().error)
    }

    @Test
    fun `a normal commit shows its message, people and files`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "bir\niki\nüç\n", "ilk")
        sandbox.commitFile(repo, "sil.txt", "x\n", "ikinci")
        repo.resolve("a.txt").toFile().writeText("bir\nİKİ\nüç\ndört\n")
        repo.resolve("yeni.txt").toFile().writeText("yeni\n")
        sandbox.git(repo, "rm", "-q", "--", "sil.txt")
        sandbox.git(repo, "add", "--", "a.txt", "yeni.txt")
        sandbox.git(
            repo, "-c", "user.name=Çağrı Öztürk", "-c", "user.email=cagri@example.com", "commit", "-q",
            "--author=Gülşen Işık <gulsen@example.com>", "-m", "Düzeltme: çökme\n\nAyrıntı ğüşiöç\n\nİkinci paragraf",
        )
        val hash = sandbox.head(repo)

        val state = detail(repo, hash).state.awaitDetail()

        assertNull(state.error)
        val detail = assertNotNull(state.detail)
        assertEquals("Düzeltme: çökme", detail.summary.subject)
        assertEquals("Ayrıntı ğüşiöç\n\nİkinci paragraf", detail.body)
        assertEquals("Gülşen Işık", detail.summary.authorName)
        assertEquals("gulsen@example.com", detail.summary.authorEmail)
        assertEquals("Çağrı Öztürk", detail.committerName)
        assertEquals("cagri@example.com", detail.committerEmail)
        assertEquals(listOf(sandbox.revList(repo)[1]), detail.summary.parents)
        assertEquals(
            setOf(
                CommitFileChange("a.txt", GitChangeKind.Modified, additions = 2, deletions = 1),
                CommitFileChange("sil.txt", GitChangeKind.Deleted, additions = 0, deletions = 1),
                CommitFileChange("yeni.txt", GitChangeKind.Added, additions = 1, deletions = 0),
            ),
            detail.files.toSet(),
        )
        assertTrue(detail.committedAt >= detail.summary.authoredAt)
    }

    @Test
    fun `a root commit lists every file as added`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        repo.resolve("b.txt").toFile().writeText("b\n")
        sandbox.git(repo, "add", "--", "b.txt")
        sandbox.commitFile(repo, "a.txt", "a\nb\n", "kök")
        val root = sandbox.head(repo)
        sandbox.commitFile(repo, "a.txt", "c\n", "sonra")

        val detail = assertNotNull(detail(repo, root).state.awaitDetail().detail)

        assertEquals(emptyList(), detail.summary.parents)
        assertEquals(
            listOf(CommitFileChange("a.txt", GitChangeKind.Added, additions = 2, deletions = 0), CommitFileChange("b.txt", GitChangeKind.Added, additions = 1, deletions = 0)),
            detail.files,
        )
    }

    @Test
    fun `a merge commit lists what it brought against the first parent`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "a\n", "kök")
        sandbox.git(repo, "switch", "-q", "-c", "yan")
        sandbox.commitFile(repo, "b.txt", "b\nb\n", "yan dal")
        sandbox.commitBytes(repo, "resim.bin", byteArrayOf(0, 1, 2, 0, 3), "ikili")
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.commitFile(repo, "a.txt", "a\nc\n", "ana dal")
        val mainTip = sandbox.head(repo)
        val sideTip = sandbox.git(repo, "rev-parse", "yan").trim()
        sandbox.git(repo, "merge", "-q", "--no-edit", "yan")
        val merge = sandbox.head(repo)

        val detail = assertNotNull(detail(repo, merge).state.awaitDetail().detail)

        assertTrue(detail.summary.isMerge)
        assertEquals(listOf(mainTip, sideTip), detail.summary.parents)
        assertEquals(
            listOf(CommitFileChange("b.txt", GitChangeKind.Added, additions = 2, deletions = 0), CommitFileChange("resim.bin", GitChangeKind.Added)),
            detail.files,
        )
        assertTrue(detail.files[1].isBinary)

        val history = log(repo).loadAll()
        assertTrue(history.commits.first().isMerge)
        assertEquals(sandbox.revList(repo), history.commits.map { it.hash })
    }

    @Test
    fun `renames and binary files carry their details`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "eski ad.txt", (1..20).joinToString("\n") { "satır $it" } + "\n", "kök")
        sandbox.commitBytes(repo, "logo.png", byteArrayOf(0x89.toByte(), 0x50, 0, 0, 1, 2), "logo")
        sandbox.git(repo, "mv", "--", "eski ad.txt", "yeni ad.txt")
        repo.resolve("logo.png").toFile().writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0, 0, 9, 9, 9))
        sandbox.git(repo, "add", "--", "logo.png")
        val hash = sandbox.commit(repo, "taşı")

        val detail = assertNotNull(detail(repo, hash).state.awaitDetail().detail)

        assertEquals(
            setOf(
                CommitFileChange("yeni ad.txt", GitChangeKind.Renamed, oldPath = "eski ad.txt", additions = 0, deletions = 0),
                CommitFileChange("logo.png", GitChangeKind.Modified),
            ),
            detail.files.toSet(),
        )
        assertTrue(detail.files.single { it.path == "logo.png" }.isBinary)
    }

    @Test
    fun `hostile file, folder and branch names stay literal`() = runBlocking<Unit> {
        val repo = sandbox.newRepo("depo 'çalışma' \$(touch pwned) `touch pwned2`")
        val names = listOf("\$(touch pwned).txt", "-rf", "--all", "yeni\nsatır.txt", "sekme\tad.txt", "it's \"q\".txt", "*.txt", ":(top)x.txt")
        names.forEach { repo.resolve(it).toFile().writeText("x\n") }
        sandbox.git(repo, "--literal-pathspecs", "add", "--", *names.toTypedArray())
        val hash = sandbox.commit(repo, "\$(touch pwned3) `touch pwned4`; echo boom")
        sandbox.git(repo, "branch", "-q", "--", "dal/\$(touch_pwned5)")

        val state = detail(repo, hash).state.awaitDetail()
        val detail = assertNotNull(state.detail, state.error.toString())
        assertEquals(names.toSet(), detail.files.map { it.path }.toSet())
        assertTrue(detail.files.all { it.kind == GitChangeKind.Added && it.additions == 1 })
        assertEquals("\$(touch pwned3) `touch pwned4`; echo boom", detail.summary.subject)

        val presenter = log(repo)
        assertEquals(listOf(hash), presenter.state.awaitState { !it.loading && it.branches.size == 2 }.commits.map { it.hash })
        presenter.onIntent(LogIntent.FilterBranch("dal/\$(touch_pwned5)"))
        assertEquals(listOf(hash), presenter.state.awaitLoaded().commits.map { it.hash })

        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `an unknown hash or a non-commit object is not found`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "a.txt", "a\n", "kök")
        val blob = sandbox.git(repo, "rev-parse", "HEAD:a.txt").trim()

        assertEquals(GitFailure.NotFound, detail(repo, "0123456789abcdef0123456789abcdef01234567").state.awaitDetail().error)
        assertEquals(GitFailure.NotFound, detail(repo, blob).state.awaitDetail().error)
    }

    @Test
    fun `the hash of an annotated tag is not found`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        val commit = sandbox.commitFile(repo, "a.txt", "a\n", "kök")
        sandbox.git(repo, "tag", "-a", "-m", "sürüm", "v1")
        val tag = sandbox.git(repo, "rev-parse", "refs/tags/v1").trim()
        assertEquals("tag", sandbox.git(repo, "cat-file", "-t", tag).trim())

        assertEquals(GitFailure.NotFound, detail(repo, tag).state.awaitDetail().error)
        assertNotNull(detail(repo, commit).state.awaitDetail().detail)
    }

    @Test
    fun `a local branch named like a remote branch leaves both filterable`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        repeat(2) { sandbox.commitFile(repo, "a.txt", "$it\n", "ana $it") }
        val remoteTip = sandbox.head(repo)
        sandbox.git(repo, "update-ref", "refs/remotes/origin/main", remoteTip)
        sandbox.git(repo, "switch", "-q", "-c", "origin/main")
        repeat(3) { sandbox.commitFile(repo, "b.txt", "$it\n", "yerel $it") }
        val localTip = sandbox.head(repo)
        sandbox.git(repo, "switch", "-q", "main")
        val presenter = log(repo, pageSize = 2)

        val state = presenter.state.awaitState { !it.loading && it.branches.size == 3 }
        assertEquals(listOf("main", "refs/heads/origin/main", "refs/remotes/origin/main"), state.branches)
        assertEquals(listOf("main", "refs/remotes/origin/main"), state.commits.first().refs)

        presenter.onIntent(LogIntent.FilterBranch("refs/heads/origin/main"))
        val local = presenter.loadAll()
        assertEquals(sandbox.revList(repo, "refs/heads/origin/main"), local.commits.map { it.hash })
        assertEquals(localTip, local.commits.first().hash)
        assertEquals(listOf("refs/heads/origin/main"), local.commits.first().refs)

        presenter.onIntent(LogIntent.FilterBranch("refs/remotes/origin/main"))
        val remote = presenter.loadAll()
        assertEquals(sandbox.revList(repo, "refs/remotes/origin/main"), remote.commits.map { it.hash })
        assertEquals(remoteTip, remote.commits.first().hash)
    }

    @Test
    fun `a file of a loaded commit opens its diff`() = runBlocking<Unit> {
        val repo = sandbox.newRepo()
        val hash = sandbox.commitFile(repo, "çiçek.txt", "a\n", "kök")
        val presenter = detail(repo, hash)
        presenter.state.awaitDetail()

        presenter.onIntent(CommitDetailIntent.OpenFile("çiçek.txt"))

        assertEquals(
            io.github.thewisekd.phopencode.feature.git.logic.contract.CommitDetailEvent.OpenFileDiff("çiçek.txt"),
            withTimeout(10.seconds) { presenter.events.first() },
        )
    }

    @Test
    fun `the local shell honours stdin and timeouts`() = runBlocking<Unit> {
        val result = sandbox.exec.run(ExecRequest("cat", "girdi ç".toByteArray()))
        assertEquals("girdi ç", result.stdout.decodeToString())
        assertEquals(0, result.exitCode)
        assertEquals(3, sandbox.exec.run(ExecRequest("echo hata >&2; exit 3")).exitCode)

        // Far larger than a pipe buffer: `cat` writes while stdin is still being fed.
        val large = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val echoed = sandbox.exec.run(ExecRequest("cat", large, timeout = 30.seconds))
        assertEquals(0, echoed.exitCode)
        assertTrue(large.contentEquals(echoed.stdout))

        val pidFile = tmp.resolve("pid")
        val started = System.nanoTime()
        assertFailsWith<SshException.Timeout> {
            sandbox.exec.run(ExecRequest("echo $$ > '${pidFile.absolutePathString()}'; exec sleep 30", timeout = 300.milliseconds))
        }
        assertTrue(System.nanoTime() - started < 20.seconds.inWholeNanoseconds)
        val pid = pidFile.toFile().readText().trim().toLong()
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the timed-out child still runs")
        assertTrue(Thread.getAllStackTraces().keys.none { it.name.startsWith("local-exec-") && it.isAlive })
        assertFailsWith<SshException.OutputLimitExceeded> { sandbox.exec.run(ExecRequest("echo 0123456789"), maxOutputBytes = 4) }
    }
}
