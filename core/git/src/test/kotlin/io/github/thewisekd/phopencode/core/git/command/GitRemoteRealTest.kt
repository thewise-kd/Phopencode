package io.github.thewisekd.phopencode.core.git.command

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/** Fetch, pull and push against a local bare repository, and a server that asks for a password. */
class GitRemoteRealTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: GitSandbox
    private lateinit var remote: Path
    private var server: HttpServer? = null

    @BeforeTest
    fun setUp() {
        sandbox = GitSandbox(tmp)
        remote = tmp.resolve("remote.git")
        sandbox.git(tmp, "init", "-q", "--bare", remote.absolutePathString())
        val seed = sandbox.newRepo("seed")
        sandbox.commitFile(seed, "a.txt", "1\n", message = "seed")
        sandbox.git(seed, "push", "-q", remote.absolutePathString(), "main")
    }

    @AfterTest
    fun tearDown() {
        server?.stop(0)
    }

    private fun clone(name: String): Path {
        sandbox.git(tmp, "clone", "-q", remote.absolutePathString(), tmp.resolve(name).absolutePathString())
        return tmp.resolve(name)
    }

    private fun run(command: GitCommand): GitResult = runBlocking { sandbox.runner.run(command) }

    /** A pull as the logic does it: fetch, then a fast-forward merge; the first failure wins. */
    private fun pull(repo: Path, remote: String? = null, branch: String? = null): GitResult {
        val fetch = run(GitCommands.fetch(repo.absolutePathString(), remote))
        if (!fetch.succeeded) return fetch
        val target = if (remote != null && branch != null) "refs/remotes/$remote/$branch" else "@{upstream}"
        return run(GitCommands.mergeFastForward(repo.absolutePathString(), target))
    }

    private fun head(repo: Path, ref: String = "HEAD"): String = sandbox.gitText(repo, "rev-parse", ref).trim()

    @Test
    fun `push sends a new commit and can set the upstream`() {
        val a = clone("a")
        sandbox.git(a, "switch", "-q", "-c", "çalış")
        sandbox.commitFile(a, "b.txt", "2\n", message = "two")
        val result = run(GitCommands.push(a.absolutePathString(), "origin", "çalış", setUpstream = true))
        assertTrue(result.succeeded, result.stderrText())
        assertEquals(head(a), head(remote, "refs/heads/çalış"))
        assertEquals("origin", sandbox.gitText(a, "config", "branch.çalış.remote").trim())
    }

    /** A clone where `*.log` is ignored locally and an ignored `cache.log` holds local data. */
    private fun cloneWithIgnoredFile(name: String): Path {
        val clone = clone(name)
        clone.resolve(".git/info/exclude").writeText("*.log\n")
        clone.resolve("cache.log").writeText("local ignored data\n")
        return clone
    }

    /** Pushes a commit that tracks `cache.log` to the remote's main. */
    private fun publishTrackedCacheLog() {
        val a = clone("publisher")
        a.resolve("cache.log").writeText("tracked upstream\n")
        sandbox.git(a, "add", "-f", "--", "cache.log")
        sandbox.git(a, "commit", "-q", "-m", "track cache.log")
        sandbox.git(a, "push", "-q", "origin", "main")
    }

    @Test
    fun `pull fails rather than overwrite an ignored file and still fast-forwards otherwise`() {
        val b = cloneWithIgnoredFile("b")
        val before = head(b)
        publishTrackedCacheLog()
        val refused = pull(b)
        assertNotEquals(0, refused.exitCode, refused.stderrText())
        assertEquals("local ignored data\n", b.resolve("cache.log").readText())
        assertEquals(before, head(b))

        b.resolve("cache.log").toFile().delete()
        val ff = pull(b)
        assertTrue(ff.succeeded, ff.stderrText())
        assertEquals("tracked upstream\n", b.resolve("cache.log").readText())
    }

    @Test
    fun `merge options of the branch cannot turn the fast-forward into a squash or a merge commit`() {
        val a = clone("a")
        val behind = clone("behind")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        sandbox.git(behind, "config", "branch.main.mergeOptions", "--squash --no-ff --no-commit --edit")
        val result = pull(behind)
        assertTrue(result.succeeded, result.stderrText())
        assertEquals(head(a), head(behind))
        assertEquals("", sandbox.gitText(behind, "diff", "--cached", "--name-only"))
        assertEquals("", sandbox.gitText(behind, "status", "--porcelain"))
    }

    @Test
    fun `control - with mergeOptions squash a plain fast-forward merge only stages`() {
        val a = clone("a")
        val behind = clone("behind")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        sandbox.git(behind, "config", "branch.main.mergeOptions", "--squash")
        val before = head(behind)
        sandbox.git(behind, "fetch", "-q")
        sandbox.git(behind, "merge", "-q", "--ff-only", "@{upstream}")
        assertEquals(before, head(behind))
        assertEquals("a.txt\n", sandbox.gitText(behind, "diff", "--cached", "--name-only"))
    }

    @Test
    fun `git pull itself rejects no-overwrite-ignore, which is why pull is fetch then merge`() {
        val b = clone("b")
        val rejected = sandbox.gitResult(b, "pull", "--ff-only", "--no-overwrite-ignore")
        assertNotEquals(0, rejected.exitCode)
        assertTrue(String(rejected.stderr).contains("unknown option"), String(rejected.stderr))
    }

    @Test
    fun `control - a plain fast-forward pull overwrites the ignored file`() {
        val b = cloneWithIgnoredFile("b")
        publishTrackedCacheLog()
        sandbox.git(b, "pull", "-q", "--ff-only")
        assertEquals("tracked upstream\n", b.resolve("cache.log").readText())
    }

    @Test
    fun `switch fails rather than overwrite an ignored file`() {
        val b = cloneWithIgnoredFile("b")
        sandbox.git(b, "branch", "other")
        sandbox.git(b, "switch", "-q", "other")
        b.resolve("cache.log").toFile().delete()
        b.resolve("cache.log").writeText("tracked on other\n")
        sandbox.git(b, "add", "-f", "--", "cache.log")
        sandbox.git(b, "commit", "-q", "-m", "track cache.log")
        sandbox.git(b, "switch", "-q", "main")
        b.resolve("cache.log").writeText("local ignored data\n")

        val refused = run(GitCommands.switchBranch(b.absolutePathString(), "other"))
        assertNotEquals(0, refused.exitCode, refused.stderrText())
        assertEquals("local ignored data\n", b.resolve("cache.log").readText())
        assertEquals("main", sandbox.gitText(b, "symbolic-ref", "--short", "HEAD").trim())

        sandbox.git(b, "switch", "-q", "other")
        assertEquals("tracked on other\n", b.resolve("cache.log").readText(), "control: a plain switch overwrites")
    }

    @Test
    fun `push does not publish annotated tags even with push followTags`() {
        val a = clone("a")
        sandbox.git(a, "config", "push.followTags", "true")
        sandbox.commitFile(a, "b.txt", "2\n", message = "two")
        sandbox.git(a, "tag", "-a", "-m", "release", "v1.0")
        val result = run(GitCommands.push(a.absolutePathString(), "origin", "main"))
        assertTrue(result.succeeded, result.stderrText())
        assertEquals(head(a), head(remote, "refs/heads/main"))
        assertEquals("", sandbox.gitText(remote, "tag", "--list"))
    }

    @Test
    fun `control - with push followTags a plain push publishes the tag`() {
        val a = clone("a")
        sandbox.git(a, "config", "push.followTags", "true")
        sandbox.commitFile(a, "b.txt", "2\n", message = "two")
        sandbox.git(a, "tag", "-a", "-m", "release", "v1.0")
        sandbox.git(a, "push", "-q", "origin", "refs/heads/main:refs/heads/main")
        assertEquals("v1.0\n", sandbox.gitText(remote, "tag", "--list"))
    }

    @Test
    fun `remoteList names the configured remotes`() {
        val a = clone("a")
        sandbox.git(a, "remote", "add", "çatal", remote.absolutePathString())
        val result = run(GitCommands.remoteList(a.absolutePathString()))
        assertEquals(setOf("origin", "çatal"), String(result.stdout, Charsets.UTF_8).lines().filter { it.isNotEmpty() }.toSet())
    }

    @Test
    fun `a repository next to the work tree named like a remote is neither fetched from nor pushed to`() {
        val a = clone("a")
        // A bare repository beside the clone, named like a remote that is not configured.
        val fork = tmp.resolve("fork")
        sandbox.git(tmp, "clone", "-q", "--bare", remote.absolutePathString(), fork.absolutePathString())
        sandbox.commitFile(a, "b.txt", "2\n", message = "two")
        val forkHead = head(fork, "refs/heads/main")
        assertTrue(GitNames.isValidRemoteName("fork"))

        assertNotEquals(0, run(GitCommands.fetch(a.absolutePathString(), "fork")).exitCode)
        assertNotEquals(0, pull(a, "fork", "main").exitCode)
        assertNotEquals(0, run(GitCommands.push(a.absolutePathString(), "fork", "main")).exitCode)
        assertEquals(forkHead, head(fork, "refs/heads/main"))
        assertFalse(GitNames.isValidRemoteName("../fork"))
    }

    @Test
    fun `fetch refuses an unconfigured name even when a repository by that name is inside the work tree`() {
        val a = clone("a")
        sandbox.git(a, "clone", "-q", "--bare", remote.absolutePathString(), a.resolve("inner").absolutePathString())
        val result = run(GitCommands.fetch(a.absolutePathString(), "inner"))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderrText().contains("no such remote"), result.stderrText())
    }

    @Test
    fun `fetch of a named remote with multiple still updates it`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.commitFile(a, "a.txt", "new\n", message = "new")
        sandbox.git(a, "push", "-q", "origin", "main")
        val result = run(GitCommands.fetch(b.absolutePathString(), "origin"))
        assertTrue(result.succeeded, result.stderrText())
        assertEquals(head(a), head(b, "refs/remotes/origin/main"))
    }

    @Test
    fun `a push that would need force is rejected and leaves the remote alone`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        val remoteHead = head(remote, "refs/heads/main")
        sandbox.commitFile(b, "a.txt", "from b\n", message = "b")

        val result = run(GitCommands.push(b.absolutePathString(), "origin", "main"))
        assertNotEquals(0, result.exitCode)
        assertTrue(result.stderrText().contains("rejected"), result.stderrText())
        assertEquals(remoteHead, head(remote, "refs/heads/main"))
    }

    @Test
    fun `pull only fast-forwards`() {
        val a = clone("a")
        val behind = clone("behind")
        val diverged = clone("diverged")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        sandbox.commitFile(diverged, "c.txt", "local\n", message = "local")
        val divergedHead = head(diverged)

        val ff = pull(behind)
        assertTrue(ff.succeeded, ff.stderrText())
        assertEquals(head(a), head(behind))

        val refused = pull(diverged, "origin", "main")
        assertNotEquals(0, refused.exitCode)
        assertEquals(divergedHead, head(diverged), "pull merged or rebased")
    }

    @Test
    fun `pull ignores rebase, merge ff and autostash settings and still only fast-forwards`() {
        val a = clone("a")
        val diverged = clone("diverged")
        sandbox.git(diverged, "config", "pull.rebase", "true")
        sandbox.git(diverged, "config", "merge.ff", "false")
        sandbox.git(diverged, "config", "merge.autoStash", "true")
        sandbox.commitFile(a, "a.txt", "from a\n", message = "a")
        sandbox.git(a, "push", "-q", "origin", "main")
        sandbox.commitFile(diverged, "c.txt", "local\n", message = "local")
        val divergedHead = head(diverged)
        val refused = pull(diverged)
        assertNotEquals(0, refused.exitCode)
        assertEquals(divergedHead, head(diverged), "pull rebased")
    }

    @Test
    fun `fetch updates remote-tracking branches and prunes`() {
        val a = clone("a")
        val b = clone("b")
        sandbox.git(a, "push", "-q", "origin", "main:gone")
        sandbox.commitFile(a, "a.txt", "new\n", message = "new")
        sandbox.git(a, "push", "-q", "origin", "main")
        assertTrue(run(GitCommands.fetch(b.absolutePathString())).succeeded)
        assertEquals(head(a), head(b, "refs/remotes/origin/main"))
        assertEquals(0, sandbox.gitResult(b, "rev-parse", "--verify", "-q", "refs/remotes/origin/gone").exitCode)

        sandbox.git(a, "push", "-q", "origin", ":gone")
        assertTrue(run(GitCommands.fetch(b.absolutePathString(), "origin", prune = true)).succeeded)
        assertNotEquals(0, sandbox.gitResult(b, "rev-parse", "--verify", "-q", "refs/remotes/origin/gone").exitCode)
    }

    @Test
    fun `a server that asks for credentials gets an error back instead of a waiting prompt`() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/") { exchange ->
            exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"test\"")
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        http.start()
        server = http
        val repo = sandbox.newRepo("cred")
        sandbox.git(repo, "remote", "add", "origin", "http://127.0.0.1:${http.address.port}/repo.git")

        lateinit var result: GitResult
        val elapsed = measureTime { result = run(GitCommands.fetch(repo.absolutePathString(), timeout = 30.seconds)) }
        assertNotEquals(0, result.exitCode)
        // Older git: askpass fails, then "terminal prompts disabled"; git 2.46+: credential.interactive=never.
        val stderr = result.stderrText()
        assertTrue("could not read Username" in stderr || "unable to get password" in stderr, stderr)
        assertTrue(elapsed < 20.seconds, "fetch waited $elapsed")
        assertFalse(result.stderrText().contains("Password for"))
    }

    @Test
    fun `pull with nothing new succeeds on a dirty work tree`() {
        val a = clone("a")
        a.resolve("a.txt").writeText("dirty\n")
        val pull = pull(a)
        assertTrue(pull.succeeded, pull.stderrText())
    }
}
