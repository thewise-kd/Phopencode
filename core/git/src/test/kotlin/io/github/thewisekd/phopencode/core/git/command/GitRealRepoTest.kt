package io.github.thewisekd.phopencode.core.git.command

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.quote.ShellQuote
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * Real git, real `/bin/sh`, temporary repositories. Each hostile name has to come through as one
 * literal path; the control tests show that the same names do harm without quoting or `--`, so the
 * passing tests mean something.
 */
class GitRealRepoTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: GitSandbox

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
        sandbox = GitSandbox(tmp)
    }

    private fun run(command: GitCommand): GitResult = runBlocking { sandbox.runner.run(command) }

    /** The paths in `git status --porcelain=v2 -z` records of [kind] (`?`, `1`), read by git directly. */
    private fun statusPaths(repo: Path, kind: Char): Set<String> =
        sandbox.gitText(repo, "status", "--porcelain=v2", "-z", "--untracked-files=all")
            .split('\u0000').filter { it.startsWith("$kind ") }
            .map { if (kind == '1') it.split(' ', limit = 9)[8] else it.substring(2) }
            .toSet()

    private fun stagedPaths(repo: Path): Set<String> =
        sandbox.gitText(repo, "diff", "--cached", "--name-only", "-z").split('\u0000').filter { it.isNotEmpty() }.toSet()

    @Test
    fun `git is installed`() {
        assertTrue(sandbox.gitText(tmp, "--version").startsWith("git version"))
    }

    @Test
    fun `hostile file names are staged, committed and listed as exactly themselves`() {
        val repo = sandbox.newRepo()
        hostileNames.forEach { repo.resolve(it).writeText("content of a file\n") }
        repo.resolve("other.txt").writeText("must stay unstaged\n")

        val status = run(GitCommands.status(repo.absolutePathString()))
        assertTrue(status.succeeded, status.stderrText())
        val untracked = String(status.stdout, Charsets.UTF_8).split('\u0000').filter { it.startsWith("? ") }.map { it.substring(2) }
        assertEquals((hostileNames + "other.txt").toSet(), untracked.toSet())

        val add = run(GitCommands.add(repo.absolutePathString(), hostileNames))
        assertTrue(add.succeeded, add.stderrText())
        assertEquals(hostileNames.toSet(), stagedPaths(repo))

        val commit = run(GitCommands.commit(repo.absolutePathString(), "first \$(touch pwned)\n\nbody `touch pwned3` çğ\n"))
        assertTrue(commit.succeeded, commit.stderrText())
        assertEquals(
            "first \$(touch pwned)\n\nbody `touch pwned3` çğ\n",
            sandbox.gitText(repo, "log", "-1", "--format=%B").trimEnd('\n') + "\n",
        )
        assertEquals(setOf("other.txt"), statusPaths(repo, '?'))
        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `a repository directory with a hostile name works`() {
        val dirName = "\$(touch pwned) -rf\nit's çğ"
        val repo = sandbox.newRepo(dirName)
        repo.resolve("f.txt").writeText("x\n")
        assertTrue(run(GitCommands.add(repo.absolutePathString(), listOf("f.txt"))).succeeded)
        val top = run(GitCommands.revParseToplevel(repo.absolutePathString()))
        assertTrue(top.succeeded, top.stderrText())
        assertEquals(repo.toRealPath().absolutePathString(), String(top.stdout, Charsets.UTF_8).removeSuffix("\n"))
        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `control - unquoted, the same name runs a command`() {
        val repo = sandbox.newRepo()
        val name = "\$(touch pwned).txt"
        // What a careless builder would send: the name pasted in without quoting.
        runBlocking {
            sandbox.exec.run(ExecRequest("git -C ${ShellQuote.quote(repo.absolutePathString())} add -- $name"))
        }
        assertTrue(sandbox.cwd.resolve("pwned").exists(), "the hazard did not show; the test proves nothing")
    }

    @Test
    fun `the separator keeps a file called --all from staging everything`() {
        val repo = sandbox.newRepo()
        repo.resolve("--all").writeText("x\n")
        repo.resolve("other.txt").writeText("y\n")
        assertTrue(run(GitCommands.add(repo.absolutePathString(), listOf("--all"))).succeeded)
        assertEquals(setOf("--all"), stagedPaths(repo))
    }

    @Test
    fun `control - without the separator, --all stages everything`() {
        val repo = sandbox.newRepo()
        repo.resolve("--all").writeText("x\n")
        repo.resolve("other.txt").writeText("y\n")
        sandbox.git(repo, "add", "--all")
        assertEquals(setOf("--all", "other.txt"), stagedPaths(repo))
    }

    @Test
    fun `control - without the separator, -rf is read as options`() {
        val repo = sandbox.newRepo()
        repo.resolve("-rf").writeText("x\n")
        val bare = sandbox.gitResult(repo, "add", "-rf")
        assertNotEquals(0, bare.exitCode)
        assertTrue(run(GitCommands.add(repo.absolutePathString(), listOf("-rf"))).succeeded)
        assertEquals(setOf("-rf"), stagedPaths(repo))
    }

    @Test
    fun `pathspecs are literal - a file called star-dot-txt matches only itself`() {
        val repo = sandbox.newRepo()
        repo.resolve("*.txt").writeText("x\n")
        repo.resolve("a.txt").writeText("y\n")
        repo.resolve(":(top)magic.txt").writeText("z\n")
        assertTrue(run(GitCommands.add(repo.absolutePathString(), listOf("*.txt"))).succeeded)
        assertEquals(setOf("*.txt"), stagedPaths(repo))
        assertTrue(run(GitCommands.add(repo.absolutePathString(), listOf(":(top)magic.txt"))).succeeded)
        assertEquals(setOf("*.txt", ":(top)magic.txt"), stagedPaths(repo))
    }

    @Test
    fun `unstage and discard touch only the named files`() {
        val repo = sandbox.newRepo()
        val dir = repo.absolutePathString()
        sandbox.commitFile(repo, "-rf", "original\n")
        sandbox.commitFile(repo, "keep.txt", "original\n")
        repo.resolve("-rf").writeText("changed\n")
        repo.resolve("keep.txt").writeText("changed\n")
        sandbox.git(repo, "add", "--", "-rf", "keep.txt")

        assertTrue(run(GitCommands.unstage(dir, listOf("-rf"))).succeeded)
        assertEquals(setOf("keep.txt"), stagedPaths(repo))

        assertEquals(WorktreeDiscard.Discarded, runBlocking { sandbox.runner.discardWorktree(dir, listOf("-rf")) })
        assertEquals("original\n", repo.resolve("-rf").readText())
        assertEquals("changed\n", repo.resolve("keep.txt").readText())
    }

    @Test
    fun `control - checkout with a folder pathspec discards every change in it`() {
        val repo = sandbox.newRepo()
        repo.resolve("src").createDirectories()
        sandbox.commitFile(repo, "src/a.txt", "original\n")
        repo.resolve("src/a.txt").writeText("precious edit\n")
        sandbox.git(repo, "checkout", "-q", "--", "src")
        assertEquals("original\n", repo.resolve("src/a.txt").readText())
    }

    @Test
    fun `unstage works before the first commit`() {
        val repo = sandbox.newRepo()
        repo.resolve("a.txt").writeText("x\n")
        sandbox.git(repo, "add", "--", "a.txt")
        val result = run(GitCommands.unstage(repo.absolutePathString(), listOf("a.txt")))
        assertTrue(result.succeeded, result.stderrText())
        assertEquals(emptySet(), stagedPaths(repo))
    }

    @Test
    fun `status, diff, log and show read names with newlines raw`() {
        val repo = sandbox.newRepo()
        val dir = repo.absolutePathString()
        val name = "new\nline çğ.txt"
        sandbox.commitFile(repo, name, "one\n", message = "first")
        repo.resolve(name).writeText("two\n")

        val status = String(run(GitCommands.status(dir)).stdout, Charsets.UTF_8)
        assertTrue(status.contains("# branch.head main\u0000"), status)
        assertTrue(status.split('\u0000').any { it.startsWith("1 .M") && it.endsWith(" $name") }, status)

        val numstat = run(GitCommands.diff(dir, staged = false, output = DiffOutput.NumStat, paths = listOf(name)))
        assertEquals("1\t1\t$name\u0000", String(numstat.stdout, Charsets.UTF_8))
        val patch = String(run(GitCommands.diff(dir, staged = false, paths = listOf(name))).stdout, Charsets.UTF_8)
        assertTrue(patch.contains("-one\n+two\n"), patch)

        val log = run(GitCommands.log(dir, format = "%s", maxCount = 10, paths = listOf(name)))
        assertEquals("first", String(log.stdout, Charsets.UTF_8).trimEnd('\u0000', '\n'))

        val show = run(GitCommands.show(dir, "HEAD", format = "%s", output = ShowOutput.NameStatus))
        val records = String(show.stdout, Charsets.UTF_8).split('\u0000').map { it.trim('\n') }.filter { it.isNotEmpty() }
        assertEquals(listOf("first", "A", name), records)
    }

    @Test
    fun `a revision that names a file is still read as a revision`() {
        val repo = sandbox.newRepo()
        sandbox.commitFile(repo, "main", "file named like the branch\n", message = "m1")
        sandbox.commitFile(repo, "other.txt", "x\n", message = "m2")
        val log = run(GitCommands.log(repo.absolutePathString(), format = "%s", maxCount = 10, revision = "main"))
        assertTrue(log.succeeded, log.stderrText())
        assertEquals(listOf("m2", "m1"), String(log.stdout, Charsets.UTF_8).split('\u0000').map { it.trim() }.filter { it.isNotEmpty() })
    }

    @Test
    fun `branches with Turkish names are created, listed, switched and deleted`() {
        val repo = sandbox.newRepo()
        val dir = repo.absolutePathString()
        sandbox.commitFile(repo, "a.txt", "x\n")
        assertTrue(run(GitCommands.createBranch(dir, "özellik/çalış")).succeeded)
        val list = run(GitCommands.branchList(dir, format = "%(refname:short)"))
        assertEquals(setOf("main", "özellik/çalış"), String(list.stdout, Charsets.UTF_8).lines().filter { it.isNotEmpty() }.toSet())

        val switched = run(GitCommands.switchBranch(dir, "özellik/çalış"))
        assertTrue(switched.succeeded, switched.stderrText())
        assertEquals("özellik/çalış", sandbox.gitText(repo, "symbolic-ref", "--short", "HEAD").trim())
        sandbox.commitFile(repo, "b.txt", "y\n")
        assertTrue(run(GitCommands.switchBranch(dir, "main")).succeeded)

        val safeDelete = run(GitCommands.deleteBranch(dir, "özellik/çalış"))
        assertFalse(safeDelete.succeeded, "an unmerged branch was deleted without confirmation")
        assertTrue(safeDelete.stderrText().contains("not fully merged"), safeDelete.stderrText())
        assertTrue(run(GitCommands.deleteBranch(dir, "özellik/çalış", allowUnmerged = true)).succeeded)
        assertEquals("main\n", sandbox.gitText(repo, "branch", "--format=%(refname:short)"))
    }

    @Test
    fun `a commit hook failure is a result with its message`() {
        val repo = sandbox.newRepo()
        val hook = repo.resolve(".git/hooks").createDirectories().resolve("pre-commit")
        hook.writeText("#!/bin/sh\necho 'hook says no' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        repo.resolve("a.txt").writeText("x\n")
        sandbox.git(repo, "add", "--", "a.txt")
        val result = run(GitCommands.commit(repo.absolutePathString(), "msg"))
        assertEquals(1, result.exitCode)
        assertTrue(result.stderrText().contains("hook says no"))
    }

    @Test
    fun `messages are in English whatever the server locale`() {
        val result = run(GitCommands.status(tmp.resolve("nowhere").createDirectories().absolutePathString()))
        assertEquals(128, result.exitCode)
        assertTrue(result.stderrText().contains("not a git repository"), result.stderrText())
    }

    @Test
    fun `stdout keeps bytes that are not UTF-8`() {
        val repo = sandbox.newRepo()
        val bytes = byteArrayOf(0x66, 0xff.toByte(), 0xfe.toByte(), 0x0a)
        repo.resolve("bin.txt").toFile().writeBytes(bytes)
        sandbox.git(repo, "add", "--", "bin.txt")
        val show = run(GitCommands.show(repo.absolutePathString(), ":bin.txt", output = ShowOutput.None))
        assertTrue(show.succeeded, show.stderrText())
        assertContentEquals(bytes, show.stdout)
    }
}
