package io.github.thewisekd.phopencode.feature.git.logic.remote

import com.sun.net.httpserver.HttpServer
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitRemote
import io.github.thewisekd.phopencode.feature.git.logic.contract.PushPlan
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteIntent
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteOp
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteResult
import io.github.thewisekd.phopencode.feature.git.logic.contract.RemoteState
import io.github.thewisekd.phopencode.feature.git.logic.status.RepoSandbox
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
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
 * The presenter against real git through a cancellable local `/bin/sh`, with local bare remotes
 * and local HTTP servers only: fetch, fast-forward pull, push, refusals, credentials and cancel.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RemoteRealGitTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: RepoSandbox
    private lateinit var exec: CancellableShellExec
    private lateinit var remote: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: HttpServer? = null
    private val release = CountDownLatch(1)

    @BeforeTest
    fun setUp() {
        sandbox = RepoSandbox(tmp)
        exec = CancellableShellExec(sandbox.cwd, sandbox.environment)
        remote = tmp.resolve("remote.git")
        sandbox.git(tmp, "init", "-q", "--bare", remote.absolutePathString())
        val seed = sandbox.newRepo("seed")
        sandbox.commitFile(seed, "a.txt", "1\n", message = "seed")
        sandbox.git(seed, "push", "-q", remote.absolutePathString(), "main")
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        release.countDown()
        server?.stop(0)
    }

    private fun clone(name: String): Path {
        val target = tmp.resolve(name)
        sandbox.git(tmp, "clone", "-q", remote.absolutePathString(), target.absolutePathString())
        return target
    }

    private fun head(repo: Path, ref: String = "HEAD"): String = sandbox.gitText(repo, "rev-parse", ref).trim()

    private fun presenter(directory: Path) = DefaultRemotePresenter(directory.absolutePathString(), ExecGitConnection(exec), scope)

    private fun DefaultRemotePresenter.awaitState(predicate: (RemoteState) -> Boolean): RemoteState = runBlocking {
        withTimeout(10.seconds) { state.first(predicate) }
    }

    private fun DefaultRemotePresenter.awaitIdle(): RemoteState = awaitState { !it.loading && it.running == null }

    private fun DefaultRemotePresenter.act(intent: RemoteIntent): RemoteState {
        onIntent(intent)
        return awaitIdle()
    }

    private fun assertNoForceRan() {
        val words = exec.commands.flatMap { line -> Regex("'([^']*)'").findAll(line).map { it.groupValues[1] }.toList() }
        assertTrue(words.none { it == "-f" || it.startsWith("--force") || it.startsWith("+") || it == "pull" })
    }

    @Test
    fun `fetch brings the remote's new commits and then is up to date`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.commitFile(a, "a.txt", "2\n", message = "two")
        sandbox.git(a, "push", "-q", "origin", "main")
        val presenter = presenter(b)
        val loaded = presenter.awaitIdle()
        assertEquals(listOf(GitRemote("origin")), loaded.remotes)
        assertEquals(0, loaded.branch?.behind)

        val fetched = presenter.act(RemoteIntent.Fetch)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = false), fetched.result)
        assertEquals(1, fetched.branch?.behind)
        assertEquals(head(a), head(b, "refs/remotes/origin/main"))

        val again = presenter.act(RemoteIntent.Fetch)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = true), again.result)
    }

    @Test
    fun `pull fast-forwards and keeps an unrelated local edit`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.commitFile(a, "b.txt", "new\n", message = "two")
        sandbox.git(a, "push", "-q", "origin", "main")
        b.resolve("a.txt").writeText("local edit\n")
        val presenter = presenter(b)
        presenter.awaitIdle()

        val pulled = presenter.act(RemoteIntent.Pull)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = false), pulled.result)
        assertEquals(head(a), head(b))
        assertEquals(head(a), pulled.branch?.commit)
        assertEquals("local edit\n", b.resolve("a.txt").readText())

        assertEquals(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = true), presenter.act(RemoteIntent.Pull).result)
        assertNoForceRan()
    }

    @Test
    fun `pull on diverged branches is Diverged and changes nothing`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        sandbox.commitFile(b, "c.txt", "from b\n", message = "b")
        b.resolve("a.txt").writeText("uncommitted\n")
        val before = head(b)
        val presenter = presenter(b)
        presenter.awaitIdle()

        val refused = presenter.act(RemoteIntent.Pull)
        assertEquals(RemoteResult.Failed(RemoteOp.Pull, GitFailure.Diverged), refused.result)
        assertEquals(before, head(b))
        assertEquals("from b\n", b.resolve("c.txt").readText())
        assertEquals("uncommitted\n", b.resolve("a.txt").readText())
        assertEquals(1, refused.branch?.ahead)
        assertEquals(1, refused.branch?.behind)
    }

    @Test
    fun `push sends the branch after the confirmation`() {
        val a = clone("a")
        sandbox.commitFile(a, "b.txt", "2\n", message = "two")
        val presenter = presenter(a)
        presenter.awaitIdle()

        presenter.onIntent(RemoteIntent.RequestPush)
        assertEquals(PushPlan(remote = "origin", branch = "main", ahead = 1, setUpstream = false), presenter.state.value.confirmPush)
        assertNotEquals(head(a), head(remote, "refs/heads/main"))

        val pushed = presenter.act(RemoteIntent.ConfirmPush)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Push, upToDate = false), pushed.result)
        assertEquals(head(a), head(remote, "refs/heads/main"))
        assertEquals(0, pushed.branch?.ahead)
        assertNoForceRan()
    }

    @Test
    fun `a push that is not a fast-forward is NonFastForward and leaves the remote alone`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        val remoteHead = head(remote, "refs/heads/main")
        sandbox.commitFile(b, "a.txt", "from b\n", message = "b")
        val presenter = presenter(b)
        presenter.awaitIdle()

        presenter.onIntent(RemoteIntent.RequestPush)
        val refused = presenter.act(RemoteIntent.ConfirmPush)
        assertEquals(RemoteResult.Failed(RemoteOp.Push, GitFailure.NonFastForward), refused.result)
        assertEquals(remoteHead, head(remote, "refs/heads/main"))
        assertNoForceRan()
    }

    @Test
    fun `a push to a gone upstream recreates the remote branch and sets the upstream again`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.git(a, "switch", "-q", "-c", "özellik")
        sandbox.commitFile(a, "f.txt", "1\n", message = "f")
        sandbox.git(a, "push", "-q", "-u", "origin", "özellik")
        sandbox.git(b, "push", "-q", "origin", ":refs/heads/özellik")
        sandbox.git(a, "fetch", "-q", "--prune")
        val presenter = presenter(a)
        val loaded = presenter.awaitIdle()
        assertEquals(true, loaded.branch?.upstreamGone)

        presenter.onIntent(RemoteIntent.RequestPush)
        assertEquals(PushPlan(remote = "origin", branch = "özellik", ahead = 0, setUpstream = true), presenter.state.value.confirmPush)
        val pushed = presenter.act(RemoteIntent.ConfirmPush)

        assertEquals(RemoteResult.Succeeded(RemoteOp.Push, upToDate = false), pushed.result)
        assertEquals(head(a), head(remote, "refs/heads/özellik"))
        assertEquals(false, pushed.branch?.upstreamGone)
        assertEquals("origin/özellik", pushed.branch?.upstream)
        assertEquals("origin", sandbox.gitText(a, "config", "branch.özellik.remote").trim())
    }

    @Test
    fun `Turkish branch names push with the upstream set, then pull`() {
        val a = clone("a")
        sandbox.git(a, "switch", "-q", "-c", "çalış/ğüşıöç")
        sandbox.commitFile(a, "t.txt", "1\n", message = "t")
        val presenter = presenter(a)
        presenter.awaitIdle()

        presenter.onIntent(RemoteIntent.RequestPush)
        assertEquals(true, presenter.state.value.confirmPush?.setUpstream)
        val pushed = presenter.act(RemoteIntent.ConfirmPush)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Push, upToDate = false), pushed.result)
        assertEquals("origin/çalış/ğüşıöç", pushed.branch?.upstream)

        val other = clone("other")
        sandbox.git(other, "switch", "-q", "çalış/ğüşıöç")
        sandbox.commitFile(other, "t.txt", "2\n", message = "t2")
        sandbox.git(other, "push", "-q", "origin", "çalış/ğüşıöç")
        val pulled = presenter.act(RemoteIntent.Pull)
        assertEquals(RemoteResult.Succeeded(RemoteOp.Pull, upToDate = false), pulled.result)
        assertEquals(head(other), head(a))
    }

    @Test
    fun `an unconfigured remote name is refused, even with a repository of that name in the work tree`() {
        val a = clone("a")
        val fork = a.resolve("fork")
        sandbox.git(tmp, "clone", "-q", "--bare", remote.absolutePathString(), fork.absolutePathString())
        val forkHead = head(fork, "refs/heads/main")
        sandbox.commitFile(a, "b.txt", "2\n", message = "two")
        val presenter = presenter(a)
        presenter.awaitIdle()

        presenter.onIntent(RemoteIntent.SelectRemote("fork"))
        assertEquals("origin", presenter.state.value.selected)
        presenter.act(RemoteIntent.Fetch)
        presenter.onIntent(RemoteIntent.RequestPush)
        assertEquals("origin", presenter.state.value.confirmPush?.remote)
        presenter.act(RemoteIntent.DismissPush)

        assertTrue(exec.commands.none { "'fork'" in it })
        assertEquals(forkHead, head(fork, "refs/heads/main"))
    }

    @Test
    fun `a server that asks for credentials is CredentialRequired at once`() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange ->
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"test\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        http.start()
        server = http
        val repo = sandbox.newRepo("cred")
        sandbox.commitFile(repo, "a.txt", "1\n")
        sandbox.git(repo, "remote", "add", "origin", "http://127.0.0.1:${http.address.port}/repo.git")
        val presenter = presenter(repo)
        presenter.awaitIdle()

        val started = TimeSource.Monotonic.markNow()
        val fetched = presenter.act(RemoteIntent.Fetch)
        assertEquals(RemoteResult.Failed(RemoteOp.Fetch, GitFailure.CredentialRequired), fetched.result)
        presenter.onIntent(RemoteIntent.RequestPush)
        val pushed = presenter.act(RemoteIntent.ConfirmPush)
        assertEquals(RemoteResult.Failed(RemoteOp.Push, GitFailure.CredentialRequired), pushed.result)
        assertTrue(started.elapsedNow() < 8.seconds, "took ${started.elapsedNow()}")
    }

    @Test
    fun `cancelling a long fetch is Cancelled at once and ends only the fetch`() {
        val asked = CountDownLatch(1)
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange ->
            asked.countDown()
            // Never answers while the test runs: the fetch waits until it is cancelled.
            release.await(25, TimeUnit.SECONDS)
            runCatching { exchange.sendResponseHeaders(500, -1) }
            exchange.close()
        }
        http.executor = java.util.concurrent.Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        http.start()
        server = http
        val repo = clone("slow")
        sandbox.git(repo, "remote", "add", "yavaş", "http://127.0.0.1:${http.address.port}/repo.git")
        val presenter = presenter(repo)
        presenter.awaitIdle()
        presenter.onIntent(RemoteIntent.SelectRemote("yavaş"))

        presenter.onIntent(RemoteIntent.Fetch)
        assertTrue(asked.await(10, TimeUnit.SECONDS), "the fetch never reached the server")
        assertEquals(RemoteOp.Fetch, presenter.state.value.running)
        val started = TimeSource.Monotonic.markNow()
        val cancelled = presenter.act(RemoteIntent.Cancel)

        assertEquals(RemoteResult.Cancelled(RemoteOp.Fetch), cancelled.result)
        assertTrue(started.elapsedNow() < 5.seconds, "cancel took ${started.elapsedNow()}")
        assertEquals(1, exec.ended)
        assertNull(cancelled.error)
        assertTrue(cancelled.canStart)
        // The repository still works afterwards.
        presenter.onIntent(RemoteIntent.SelectRemote("origin"))
        assertEquals(RemoteResult.Succeeded(RemoteOp.Fetch, upToDate = true), presenter.act(RemoteIntent.Fetch).result)
        assertFalse(exec.commands.any { "kill" in it || "sudo" in it })
    }
}
