package io.github.thewisekd.phopencode.feature.git.logic.log

import app.cash.turbine.test
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.core.testing.ssh.FakeRemoteExec
import io.github.thewisekd.phopencode.core.git.parse.GitBranch
import io.github.thewisekd.phopencode.core.ssh.api.RemoteExec
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogEvent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.LogState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private const val PAGE = 3

@OptIn(ExperimentalCoroutinesApi::class)
class LogPresenterTest {
    private val exec = FakeRemoteExec()
    private val git = ExecGitConnection(exec)

    private fun head(skip: Int = 0) = LogCommands.page(DIR, null, skip, PAGE + 1).commandLine

    private fun from(revision: String, skip: Int) = LogCommands.page(DIR, revision, skip, PAGE + 1).commandLine

    private val branches = LogCommands.branches(DIR).commandLine

    private fun TestScope.presenter(): DefaultLogPresenter = DefaultLogPresenter(DIR, git, backgroundScope, pageSize = PAGE)

    private fun hashes(state: LogState) = state.commits.map { it.hash }

    @Test
    fun `the first page holds a page of commits and knows more exist`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val presenter = presenter()

        runCurrent()

        val state = presenter.state.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals((20 downTo 18).map(::hash), hashes(state))
        assertFalse(state.endReached)
        assertTrue(state.canLoadMore)
        val first = state.commits.first()
        assertEquals("commit ${hash(20)}", first.subject)
        assertEquals("Ayşe Yılmaz", first.authorName)
        assertEquals("ayse@example.com", first.authorEmail)
        assertEquals(Instant.fromEpochSeconds(1_700_000_000), first.authoredAt)
        assertEquals(listOf(hash(19)), first.parents)
    }

    @Test
    fun `later pages continue from the first page's tip without duplicates or gaps`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from(hash(20), 3), records(17 downTo 14))
        exec.onOutput(from(hash(20), 6), records(14 downTo 13))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.LoadMore)
        assertTrue(presenter.state.value.loadingMore)
        runCurrent()
        assertEquals((20 downTo 15).map(::hash), hashes(presenter.state.value))
        assertFalse(presenter.state.value.endReached)

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()
        val state = presenter.state.value
        assertEquals((20 downTo 13).map(::hash), hashes(state))
        assertTrue(state.endReached)
        assertFalse(state.loadingMore)
        assertFalse(state.canLoadMore)

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()
        assertEquals(listOf(head(), from(hash(20), 3), from(hash(20), 6)), exec.commands().filter { it != branches })
    }

    @Test
    fun `a page exactly full with nothing after it ends the list`() = runTest {
        exec.onOutput(head(), records(3 downTo 1))
        val presenter = presenter()

        runCurrent()

        assertEquals(3, presenter.state.value.commits.size)
        assertTrue(presenter.state.value.endReached)
    }

    @Test
    fun `a commit seen already is not added twice`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from(hash(20), 3), record(hash(18)) + records(17 downTo 16))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()

        assertEquals(listOf(20, 19, 18, 17, 16).map(::hash), hashes(presenter.state.value))
    }

    @Test
    fun `load more while a page loads is ignored`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val release = CompletableDeferred<Unit>()
        exec.on(from(hash(20), 3)) {
            release.await()
            stdout(records(17 downTo 15))
            exit(0)
        }
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()
        presenter.onIntent(LogIntent.LoadMore)
        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()
        release.complete(Unit)
        runCurrent()

        assertEquals(1, exec.commands().count { it == from(hash(20), 3) })
        assertEquals((20 downTo 15).map(::hash), hashes(presenter.state.value))
    }

    @Test
    fun `load more before the first page is ignored`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val presenter = presenter()

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()

        assertEquals(listOf(head()), exec.commands().filter { it != branches })
    }

    @Test
    fun `a failed next page keeps the commits and load more retries it`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from(hash(20), 3), "", exit = 128, stderr = "fatal: something broke\n")
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()
        var state = presenter.state.value
        assertEquals(GitFailure.GitError(128), state.moreError)
        assertNull(state.error)
        assertEquals(3, state.commits.size)
        assertFalse(state.loadingMore)
        assertTrue(state.canLoadMore)

        exec.onOutput(from(hash(20), 3), records(17 downTo 16))
        presenter.onIntent(LogIntent.LoadMore)
        assertNull(presenter.state.value.moreError)
        runCurrent()
        state = presenter.state.value
        assertNull(state.moreError)
        assertEquals((20 downTo 16).map(::hash), hashes(state))
        assertTrue(state.endReached)
    }

    @Test
    fun `outside a repository the first page fails and retry reads again`() = runTest {
        exec.onOutput(head(), "", exit = 128, stderr = "fatal: not a git repository (or any of the parent directories): .git\n")
        val presenter = presenter()
        runCurrent()

        assertEquals(GitFailure.NotRepository, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
        assertFalse(presenter.state.value.canLoadMore)
        assertFalse(presenter.state.value.isEmpty)

        exec.onOutput(head(), records(2 downTo 1))
        presenter.onIntent(LogIntent.Retry)
        assertTrue(presenter.state.value.loading)
        assertNull(presenter.state.value.error)
        runCurrent()

        assertEquals(listOf(hash(2), hash(1)), hashes(presenter.state.value))
        assertNull(presenter.state.value.error)
    }

    @Test
    fun `retry without an error does nothing`() = runTest {
        exec.onOutput(head(), records(2 downTo 1))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.Retry)
        runCurrent()

        assertEquals(1, exec.commands().count { it == head() })
    }

    @Test
    fun `a repository without commits is empty, not an error`() = runTest {
        exec.onOutput(head(), "", exit = 128, stderr = "fatal: your current branch 'main' does not have any commits yet\n")
        val presenter = presenter()

        runCurrent()

        val state = presenter.state.value
        assertTrue(state.isEmpty)
        assertNull(state.error)
        assertTrue(state.endReached)
        assertFalse(state.canLoadMore)
    }

    @Test
    fun `failures map to git failures`() = runTest {
        val cases: List<Pair<(FakeRemoteExec) -> Unit, GitFailure>> = listOf(
            { it: FakeRemoteExec -> it.onOutput(head(), "", exit = 127, stderr = "env: 'git': No such file or directory\n") } to GitFailure.GitMissing,
            { it: FakeRemoteExec -> it.onOutput(head(), "", exit = 128, stderr = "fatal: cannot change to '/x': No such file or directory\n") } to
                GitFailure.NotRepository,
            { it: FakeRemoteExec -> it.onOutput(head(), "not a log record") } to GitFailure.Unknown,
            { it: FakeRemoteExec -> it.failNext(SshException.Timeout()) } to GitFailure.TimedOut,
            { it: FakeRemoteExec -> it.failNext(SshException.OutputLimitExceeded(10)) } to GitFailure.OutputTooLarge,
            { it: FakeRemoteExec -> it.failNext(SshException.Disconnected()) } to GitFailure.NotConnected,
        )
        for ((script, failure) in cases) {
            val exec = FakeRemoteExec().also(script)
            val presenter = DefaultLogPresenter(DIR, ExecGitConnection(exec), backgroundScope, pageSize = PAGE)
            runCurrent()
            assertEquals(failure, presenter.state.value.error)
        }
    }

    @Test
    fun `a relative directory is rejected before anything runs`() = runTest {
        val presenter = DefaultLogPresenter("relative/dir", git, backgroundScope, pageSize = PAGE)

        runCurrent()

        assertEquals(GitFailure.Rejected, presenter.state.value.error)
        assertEquals(emptyList(), exec.commands())
    }

    @Test
    fun `branches are offered without symbolic refs or a detached head`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(
            branches,
            "(HEAD detached at 0123abc)\u0000*\u0000${hash(20)}\u0000\u0000\u0000\n" +
                branchLine("refs/heads/main", hash(20)) +
                branchLine("refs/heads/özellik/çiçek", hash(19)) +
                branchLine("refs/remotes/origin/HEAD", hash(20), symref = "refs/remotes/origin/main") +
                branchLine("refs/remotes/origin/main", hash(20)),
        )
        val presenter = presenter()

        runCurrent()

        val state = presenter.state.value
        assertEquals(listOf("main", "özellik/çiçek", "origin/main"), state.branches)
        assertEquals(listOf("main", "origin/main"), state.commits[0].refs)
        assertEquals(listOf("özellik/çiçek"), state.commits[1].refs)
        assertEquals(emptyList(), state.commits[2].refs)
    }

    @Test
    fun `branches sharing a short name are offered by full ref name`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(
            branches,
            branchLine("refs/heads/main", hash(20)) +
                branchLine("refs/heads/origin/main", hash(19)) +
                branchLine("refs/heads/refs/remotes/origin/main", hash(18)) +
                branchLine("refs/remotes/origin/main", hash(20)) +
                branchLine("refs/remotes/upstream/dal", hash(18)),
        )
        exec.onOutput(from("refs/heads/origin/main", 0), records(19 downTo 19))
        exec.onOutput(from("refs/remotes/origin/main", 0), records(20 downTo 20))
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(
            listOf(
                "main", "refs/heads/origin/main", "refs/heads/refs/remotes/origin/main", "refs/remotes/origin/main", "upstream/dal",
            ),
            state.branches,
        )
        assertEquals(listOf("main", "refs/remotes/origin/main"), state.commits[0].refs)
        assertEquals(listOf("refs/heads/origin/main"), state.commits[1].refs)

        presenter.onIntent(LogIntent.FilterBranch("refs/heads/origin/main"))
        runCurrent()
        assertEquals(listOf(hash(19)), hashes(presenter.state.value))
        presenter.onIntent(LogIntent.FilterBranch("refs/remotes/origin/main"))
        runCurrent()
        assertEquals(listOf(hash(20)), hashes(presenter.state.value))
    }

    @Test
    fun `the filter keeps its ref when labels change and clears when the ref is gone`() = runTest {
        val local = "refs/heads/origin/main"
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(branches, branchLine(local, hash(9)))
        exec.onOutput(from(local, 0), records(9 downTo 8))
        val presenter = presenter()
        runCurrent()
        assertEquals(listOf("origin/main"), presenter.state.value.branches)

        presenter.onIntent(LogIntent.FilterBranch("origin/main"))
        runCurrent()
        assertEquals(listOf(hash(9), hash(8)), hashes(presenter.state.value))

        // A remote branch of the same short name appears: both are now offered by full ref name.
        exec.onOutput(branches, branchLine(local, hash(9)) + branchLine("refs/remotes/origin/main", hash(20)))
        presenter.onIntent(LogIntent.Refresh)
        runCurrent()
        var state = presenter.state.value
        assertEquals(listOf(local, "refs/remotes/origin/main"), state.branches)
        assertEquals(local, state.branch)
        assertEquals(listOf(hash(9), hash(8)), hashes(state))

        // Refreshing again still reads the local branch, never the remote one.
        presenter.onIntent(LogIntent.Refresh)
        runCurrent()
        state = presenter.state.value
        assertEquals(local, state.branch)
        assertEquals(listOf(hash(9), hash(8)), hashes(state))
        assertEquals(0, exec.commands().count { it == from("refs/remotes/origin/main", 0) })

        // The same filter by its new label does not reload.
        val reads = exec.commands().count { it == from(local, 0) }
        presenter.onIntent(LogIntent.FilterBranch(local))
        runCurrent()
        assertEquals(reads, exec.commands().count { it == from(local, 0) })

        // The local branch is deleted: the filter is cleared, and HEAD is shown.
        exec.onOutput(from(local, 0), "", exit = 128, stderr = "fatal: bad revision '$local'\n")
        exec.onOutput(branches, branchLine("refs/remotes/origin/main", hash(20)))
        presenter.onIntent(LogIntent.Refresh)
        runCurrent()
        state = presenter.state.value
        assertNull(state.branch)
        assertNull(state.error)
        assertEquals(listOf("origin/main"), state.branches)
        assertEquals((20 downTo 18).map(::hash), hashes(state))
    }

    @Test
    fun `labels stay unique when a full ref name equals another short name`() {
        fun branch(refName: String) = io.github.thewisekd.phopencode.core.git.parse.BranchParser
            .parse(branchLine(refName, hash(1)).toByteArray()).single()
        val labels = branchLabels(
            listOf(branch("refs/heads/a"), branch("refs/remotes/a"), branch("refs/heads/refs/heads/a"), branch("refs/heads/b")),
        )

        assertEquals(
            mapOf(
                "refs/heads/a" to "refs/heads/a",
                "refs/remotes/a" to "refs/remotes/a",
                "refs/heads/refs/heads/a" to "refs/heads/refs/heads/a",
                "refs/heads/b" to "b",
            ),
            labels,
        )
        assertEquals(labels.size, labels.values.toSet().size)
    }

    @Test
    fun `a branch list read by an older load never replaces a newer one`() = runBlocking<Unit> {
        // The first branch read has its output already and then stalls on its thread, where
        // cancelling cannot reach it, as a slow reply that lands after the job was cancelled.
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val stalling = object : GitConnection {
            override suspend fun <T> withExec(block: suspend (RemoteExec) -> T): T {
                val result = block(exec)
                if ((result as? List<*>)?.firstOrNull() is GitBranch && calls.incrementAndGet() == 1) {
                    reached.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                return result
            }
        }
        var branchReads = 0
        exec.onOutput(head(), records(20 downTo 17))
        exec.on(branches) {
            val name = if (synchronized(this@LogPresenterTest) { ++branchReads } == 1) "eski" else "yeni"
            stdout(branchLine("refs/heads/$name", hash(20)))
            exit(0)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val presenter = DefaultLogPresenter(DIR, stalling, scope, pageSize = PAGE)
            assertTrue(reached.await(10, TimeUnit.SECONDS))

            presenter.onIntent(LogIntent.Refresh)
            withTimeout(10.seconds) { presenter.state.first { it.branches == listOf("yeni") && !it.loading } }

            release.countDown()
            Thread.sleep(300)
            assertEquals(listOf("yeni"), presenter.state.value.branches)
            assertEquals(listOf("yeni"), presenter.state.value.commits.first().refs)
        } finally {
            release.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `a failed branch list leaves the history usable`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(branches, "", exit = 128, stderr = "fatal: oops\n")
        val presenter = presenter()

        runCurrent()

        assertEquals(emptyList(), presenter.state.value.branches)
        assertNull(presenter.state.value.error)
        assertEquals(3, presenter.state.value.commits.size)
    }

    @Test
    fun `filtering to a branch starts over at its first page`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from(hash(20), 3), records(17 downTo 15))
        exec.onOutput(branches, branchLine("refs/heads/main", hash(20), current = true) + branchLine("refs/heads/özellik", hash(9)))
        exec.onOutput(from("refs/heads/özellik", 0), records(9 downTo 6))
        exec.onOutput(from(hash(9), 3), records(6 downTo 5))
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch("özellik"))
        val loading = presenter.state.value
        assertTrue(loading.loading)
        assertEquals("özellik", loading.branch)
        assertEquals(emptyList(), loading.commits)
        assertFalse(loading.endReached)
        runCurrent()
        assertEquals((9 downTo 7).map(::hash), hashes(presenter.state.value))
        assertEquals(listOf("main", "özellik"), presenter.state.value.branches)

        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()
        assertEquals((9 downTo 5).map(::hash), hashes(presenter.state.value))
        assertTrue(presenter.state.value.endReached)
    }

    @Test
    fun `a remote branch filters by its full ref name`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(branches, branchLine("refs/remotes/origin/main", hash(5)))
        exec.onOutput(from("refs/remotes/origin/main", 0), records(5 downTo 4))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch("origin/main"))
        runCurrent()

        assertEquals(listOf(hash(5), hash(4)), hashes(presenter.state.value))
    }

    @Test
    fun `a branch not listed is passed by name`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from("yeni-dal", 0), "", exit = 128, stderr = "fatal: bad revision 'yeni-dal'\n")
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch("yeni-dal"))
        runCurrent()

        assertEquals(GitFailure.NotFound, presenter.state.value.error)
        assertEquals(emptyList(), presenter.state.value.commits)
    }

    @Test
    fun `an invalid branch name is rejected without running anything`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val presenter = presenter()
        runCurrent()
        val before = exec.commands().size

        for (name in listOf("--all", "-n1", "a..b", "HEAD", "x\ny", "")) {
            presenter.onIntent(LogIntent.FilterBranch(name))
            runCurrent()
            assertEquals(GitFailure.Rejected, presenter.state.value.error)
            assertFalse(presenter.state.value.loading)
        }
        assertEquals(before, exec.commands().size)
    }

    @Test
    fun `filtering back to null shows head again`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from("dal", 0), records(9 downTo 9))
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(LogIntent.FilterBranch("dal"))
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch(null))
        runCurrent()

        assertNull(presenter.state.value.branch)
        assertEquals((20 downTo 18).map(::hash), hashes(presenter.state.value))
    }

    @Test
    fun `the same filter again does not reload`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch(null))
        runCurrent()

        assertEquals(1, exec.commands().count { it == head() })
    }

    @Test
    fun `a filter while a page loads drops the old page`() = runTest {
        val release = CompletableDeferred<Unit>()
        exec.on(head()) {
            release.await()
            stdout(records(20 downTo 17))
            exit(0)
        }
        exec.onOutput(from("dal", 0), records(9 downTo 8))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch("dal"))
        release.complete(Unit)
        runCurrent()

        assertEquals("dal", presenter.state.value.branch)
        assertEquals(listOf(hash(9), hash(8)), hashes(presenter.state.value))
    }

    @Test
    fun `a filter while the next page loads drops that page`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val release = CompletableDeferred<Unit>()
        exec.on(from(hash(20), 3)) {
            release.await()
            stdout(records(17 downTo 15))
            exit(0)
        }
        exec.onOutput(from("dal", 0), records(9 downTo 8))
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()

        presenter.onIntent(LogIntent.FilterBranch("dal"))
        assertFalse(presenter.state.value.loadingMore)
        release.complete(Unit)
        runCurrent()

        assertEquals(listOf(hash(9), hash(8)), hashes(presenter.state.value))
        assertFalse(presenter.state.value.loadingMore)
    }

    @Test
    fun `refresh starts over and reads the branches again`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        exec.onOutput(from(hash(20), 3), records(17 downTo 15))
        exec.onOutput(branches, branchLine("refs/heads/main", hash(20)))
        val presenter = presenter()
        runCurrent()
        presenter.onIntent(LogIntent.LoadMore)
        runCurrent()

        exec.onOutput(head(), records(21 downTo 18))
        exec.onOutput(branches, branchLine("refs/heads/main", hash(21)) + branchLine("refs/heads/yeni", hash(21)))
        presenter.onIntent(LogIntent.Refresh)
        assertTrue(presenter.state.value.loading)
        runCurrent()

        assertEquals((21 downTo 19).map(::hash), hashes(presenter.state.value))
        assertFalse(presenter.state.value.endReached)
        assertEquals(listOf("main", "yeni"), presenter.state.value.branches)
    }

    @Test
    fun `opening a commit and closing are events`() = runTest {
        exec.onOutput(head(), records(20 downTo 17))
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(LogIntent.OpenCommit(hash(19)))
        presenter.onIntent(LogIntent.Close)

        presenter.events.test {
            assertEquals(LogEvent.OpenCommit(hash(19)), awaitItem())
            assertEquals(LogEvent.Close, awaitItem())
            expectNoEvents()
        }
    }
}
