package io.github.thewisekd.phopencode.core.git.command

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/**
 * Real git for the first-parent diff of a merge, the diff of an untracked file and explicit branch
 * tracking.
 */
@Timeout(30)
class GitCoreGapsRealTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: GitSandbox
    private lateinit var repo: Path

    /** The repository's real path, as `rev-parse --show-toplevel` prints it. */
    private val root: String get() = repo.toRealPath().absolutePathString()

    @BeforeTest
    fun setUp() {
        sandbox = GitSandbox(tmp)
        repo = sandbox.newRepo()
    }

    private fun run(command: GitCommand): GitResult = runBlocking { sandbox.runner.run(command) }

    private fun text(result: GitResult): String {
        assertTrue(result.succeeded, result.stderrText())
        return String(result.stdout, Charsets.UTF_8)
    }

    /** main changes `a.txt`; the side branch adds `added.txt` and deletes `gone.txt`; then main merges it. */
    private fun mergeCommit(): String {
        sandbox.commitFile(repo, "a.txt", "a\n", "root")
        sandbox.commitFile(repo, "gone.txt", "gone\n", "gone")
        sandbox.git(repo, "switch", "-q", "-c", "side")
        sandbox.commitFile(repo, "added.txt", "one\ntwo\n", "add")
        sandbox.git(repo, "rm", "-q", "--", "gone.txt")
        sandbox.git(repo, "commit", "-q", "-m", "delete")
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.commitFile(repo, "a.txt", "a\nb\n", "main")
        sandbox.git(repo, "merge", "-q", "--no-edit", "side")
        return sandbox.gitText(repo, "rev-parse", "HEAD").trim()
    }

    @Test
    fun `git knows the first-parent option`() {
        // --diff-merges=first-parent appeared in git 2.31.
        val (major, minor) = sandbox.gitText(tmp, "--version").removePrefix("git version ").split('.').map { it.takeWhile(Char::isDigit).toInt() }
        assertTrue(major > 2 || (major == 2 && minor >= 31))
    }

    @Test
    fun `a merge lists its added and deleted files against the first parent`() {
        val merge = mergeCommit()
        val nameStatus = text(run(GitCommands.show(root, merge, output = ShowOutput.NameStatus, firstParent = true)))
        assertEquals("A\u0000added.txt\u0000D\u0000gone.txt\u0000", nameStatus)
        val numStat = text(run(GitCommands.show(root, merge, output = ShowOutput.NumStat, firstParent = true)))
        assertEquals("2\t0\tadded.txt\u00000\t1\tgone.txt\u0000", numStat)
    }

    @Test
    fun `control - without the option a merge's name-status shows no plain list`() {
        val merge = mergeCommit()
        val nameStatus = text(run(GitCommands.show(root, merge, output = ShowOutput.NameStatus)))
        assertFalse("A\u0000added.txt\u0000" in nameStatus)
    }

    @Test
    fun `a merge's patch is a plain diff against the first parent`() {
        val merge = mergeCommit()
        val patch = text(run(GitCommands.show(root, merge, paths = listOf("added.txt"), firstParent = true)))
        assertTrue(patch.startsWith("diff --git a/added.txt b/added.txt\nnew file mode 100644\n"), patch)
        assertTrue(patch.endsWith("@@ -0,0 +1,2 @@\n+one\n+two\n"), patch)
        val ordinary = sandbox.gitText(repo, "rev-parse", "HEAD^1")
        assertEquals(
            text(run(GitCommands.show(root, ordinary.trim(), output = ShowOutput.NameStatus))),
            text(run(GitCommands.show(root, ordinary.trim(), output = ShowOutput.NameStatus, firstParent = true))),
        )
    }

    private fun untracked(path: String, output: DiffOutput = DiffOutput.Patch, dir: String = root, limit: Long = 1L shl 20): UntrackedDiff =
        runBlocking { sandbox.runner.diffUntracked(dir, path, output = output, maxOutputBytes = limit) }

    private fun untrackedText(path: String, output: DiffOutput = DiffOutput.Patch): String =
        String(assertIs<UntrackedDiff.Diff>(untracked(path, output)).output, Charsets.UTF_8)

    @Test
    fun `an untracked file with a hostile name shows every line as added`() {
        sandbox.commitFile(repo, "a.txt", "a\n")
        val names = listOf("\$(touch pwned).txt", "-rf", "--all", "new\nline.txt", "çğı/a b.txt", "*.txt")
        repo.resolve("çğı").createDirectories()
        names.forEach { repo.resolve(it).writeText("one\ntwo\n") }
        names.forEach { name ->
            val patch = untrackedText(name)
            assertTrue(patch.endsWith("@@ -0,0 +1,2 @@\n+one\n+two\n"), patch)
            // Git C-quotes a name with a newline in patch headers whatever core.quotePath says.
            if ('\n' !in name) {
                val header = if (' ' in name) "+++ b/$name\t\n" else "+++ b/$name\n"
                assertTrue(patch.startsWith("diff --git a/$name b/$name\nnew file mode 100644\n"), patch)
                assertTrue(patch.endsWith("--- /dev/null\n$header@@ -0,0 +1,2 @@\n+one\n+two\n"), patch)
            }
            assertEquals("2\t0\t\u0000/dev/null\u0000$name\u0000", untrackedText(name, DiffOutput.NumStat))
        }
        assertEquals(emptyList(), sandbox.pwnedFiles())
        // Nothing was staged.
        assertEquals("", sandbox.gitText(repo, "diff", "--cached", "--name-only"))
    }

    @Test
    fun `an untracked binary, empty or symlink file keeps its own form`() {
        repo.resolve("bin").writeBytes(byteArrayOf(0, 1, 2, 0))
        repo.resolve("empty").writeText("")
        Files.createSymbolicLink(repo.resolve("link"), tmp.resolve("outside.txt"))
        tmp.resolve("outside.txt").writeText("secret\n")
        assertTrue("Binary files /dev/null and b/bin differ" in untrackedText("bin"))
        val empty = untrackedText("empty")
        assertTrue(empty.startsWith("diff --git a/empty b/empty\nnew file mode 100644\n"), empty)
        assertFalse("@@" in empty)
        // A symlink shows where it points, never what it points at.
        val link = untrackedText("link")
        assertTrue("new file mode 120000" in link, link)
        assertTrue("+${tmp.resolve("outside.txt").absolutePathString()}\n" in link, link)
        assertFalse("secret" in link)
    }

    @Test
    fun `a file under a symlinked folder that leaves the work tree is refused`() {
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("secret.txt").writeText("secret\n")
        Files.createSymbolicLink(repo.resolve("out"), outside)
        assertEquals(UntrackedDiff.Refused, untracked("out/secret.txt"))
        // Also when the outside folder is itself a repository with the same layout.
        val other = sandbox.newRepo("other")
        other.resolve("out").createDirectories().resolve("secret.txt").writeText("secret\n")
        Files.createSymbolicLink(repo.resolve("in"), other.resolve("out"))
        repo.resolve("x").createDirectories()
        Files.createSymbolicLink(repo.resolve("x/y"), other.resolve("out"))
        assertEquals(UntrackedDiff.Refused, untracked("in/secret.txt"))
        assertEquals(UntrackedDiff.Refused, untracked("x/y/secret.txt"))
    }

    @Test
    fun `control - git diff --no-index itself follows a symlinked folder`() {
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("secret.txt").writeText("secret\n")
        Files.createSymbolicLink(repo.resolve("out"), outside)
        val result = run(GitCommands.diffUntracked(root, "out/secret.txt"))
        assertTrue("+secret" in String(result.stdout, Charsets.UTF_8))
    }

    @Test
    fun `tracked, ignored, missing and folder paths are refused`() {
        sandbox.commitFile(repo, ".gitignore", "*.log\n")
        sandbox.commitFile(repo, "tracked.txt", "t\n")
        repo.resolve("tracked.txt").writeText("changed\n")
        repo.resolve("x.log").writeText("ignored\n")
        repo.resolve("dir").createDirectories().resolve("f.txt").writeText("f\n")
        val nested = sandbox.newRepo("nested-parent/sub")
        sandbox.commitFile(nested, "n.txt", "n\n")
        listOf("tracked.txt", "x.log", "missing.txt", "dir", "nested-parent/sub", "nested-parent/sub/n.txt").forEach { path ->
            assertEquals(UntrackedDiff.Refused, untracked(path), path)
        }
        assertIs<UntrackedDiff.Diff>(untracked("dir/f.txt"))
    }

    @Test
    fun `from a subfolder instead of the root the path is refused`() {
        repo.resolve("sub").createDirectories().resolve("f.txt").writeText("f\n")
        repo.resolve("sub/deeper").createDirectories().resolve("g.txt").writeText("g\n")
        val sub = repo.resolve("sub").toRealPath().absolutePathString()
        assertEquals(UntrackedDiff.Refused, untracked("f.txt", dir = sub))
        assertEquals(UntrackedDiff.Refused, untracked("deeper/g.txt", dir = sub))
        assertIs<UntrackedDiff.Diff>(untracked("sub/deeper/g.txt"))
    }

    @Test
    fun `an untracked diff past the limit is OutputTooLarge`() {
        repo.resolve("big.txt").writeText("line\n".repeat(10_000))
        assertFailsWith<GitException.OutputTooLarge> { untracked("big.txt", limit = 1_000) }
        assertIs<UntrackedDiff.Diff>(untracked("big.txt", output = DiffOutput.NumStat, limit = 1_000))
    }

    /** A remote `origin` whose `main` is known locally, without any network. */
    private fun remoteBranch() {
        sandbox.commitFile(repo, "a.txt", "a\n")
        sandbox.git(repo, "remote", "add", "origin", tmp.resolve("nowhere").absolutePathString())
        sandbox.git(repo, "update-ref", "refs/remotes/origin/feature", "HEAD")
        sandbox.git(repo, "config", "branch.autoSetupMerge", "false")
    }

    private fun upstreamOf(branch: String): String =
        sandbox.gitText(repo, "for-each-ref", "--format=%(upstream)", "refs/heads/$branch").trim()

    @Test
    fun `an explicit track sets the upstream even with branch autoSetupMerge false`() {
        remoteBranch()
        assertTrue(run(GitCommands.createBranch(root, "feature", "refs/remotes/origin/feature", track = BranchTracking.Track)).succeeded)
        assertEquals("refs/remotes/origin/feature", upstreamOf("feature"))
    }

    @Test
    fun `control - by default branch autoSetupMerge false leaves the branch untracked`() {
        remoteBranch()
        assertTrue(run(GitCommands.createBranch(root, "feature", "refs/remotes/origin/feature")).succeeded)
        assertEquals("", upstreamOf("feature"))
    }

    @Test
    fun `no-track leaves the branch untracked even with branch autoSetupMerge always`() {
        remoteBranch()
        sandbox.git(repo, "config", "branch.autoSetupMerge", "always")
        assertTrue(run(GitCommands.createBranch(root, "feature", "refs/remotes/origin/feature", track = BranchTracking.NoTrack)).succeeded)
        assertEquals("", upstreamOf("feature"))
    }
}
