package io.github.thewisekd.phopencode.core.git.command

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * Discarding with real git: only tracked files with an unstaged change are restored. Untracked and
 * ignored data, including a folder that took a tracked file's place, survives every refusal.
 */
class GitDiscardTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var sandbox: GitSandbox
    private lateinit var repo: Path

    @BeforeTest
    fun setUp() {
        sandbox = GitSandbox(tmp)
        repo = sandbox.newRepo()
        repo.resolve("src").createDirectories()
        sandbox.commitFile(repo, ".gitignore", "*.log\n")
        sandbox.commitFile(repo, "src/a.txt", "original a\n")
        sandbox.commitFile(repo, "b.txt", "original b\n")
        sandbox.commitFile(repo, "-rf", "original rf\n")
        sandbox.commitFile(repo, "new\nline.txt", "original nl\n")
    }

    private fun discard(vararg paths: String, dir: Path = repo): WorktreeDiscard =
        runBlocking { sandbox.runner.discardWorktree(dir.absolutePathString(), paths.toList()) }

    /** Replaces the tracked file `b.txt` with a folder holding [name]. */
    private fun folderInPlaceOfFile(name: String): Path {
        repo.resolve("b.txt").deleteExisting()
        val folder = repo.resolve("b.txt").createDirectories()
        folder.resolve(name).writeText("precious\n")
        return folder
    }

    @Test
    fun `modified and deleted files are restored, hostile names included`() {
        repo.resolve("src/a.txt").writeText("edit\n")
        repo.resolve("-rf").writeText("edit\n")
        repo.resolve("new\nline.txt").deleteExisting()
        assertEquals(WorktreeDiscard.Discarded, discard("src/a.txt", "-rf", "new\nline.txt"))
        assertEquals("original a\n", repo.resolve("src/a.txt").readText())
        assertEquals("original rf\n", repo.resolve("-rf").readText())
        assertEquals("original nl\n", repo.resolve("new\nline.txt").readText())
        assertEquals("", sandbox.gitText(repo, "status", "--porcelain"))
    }

    @Test
    fun `a file whose type changed to a symlink is restored`() {
        repo.resolve("b.txt").deleteExisting()
        Files.createSymbolicLink(repo.resolve("b.txt"), Path.of("src/a.txt"))
        assertEquals(WorktreeDiscard.Discarded, discard("b.txt"))
        assertFalse(repo.resolve("b.txt").isSymbolicLink())
        assertEquals("original b\n", repo.resolve("b.txt").readText())
    }

    @Test
    fun `a tracked file replaced by a folder with an untracked file is refused and the folder stays`() {
        val folder = folderInPlaceOfFile("notes.txt")
        assertEquals(WorktreeDiscard.Refused, discard("b.txt"))
        assertEquals("precious\n", folder.resolve("notes.txt").readText())
    }

    @Test
    fun `a tracked file replaced by a folder with an ignored file is refused and the folder stays`() {
        val folder = folderInPlaceOfFile("debug.log")
        assertEquals(WorktreeDiscard.Refused, discard("b.txt"))
        assertEquals("precious\n", folder.resolve("debug.log").readText())
    }

    @Test
    fun `a tracked file replaced by a nested repository is refused`() {
        repo.resolve("b.txt").deleteExisting()
        val nested = sandbox.newRepo("repo/b.txt")
        nested.resolve("work.txt").writeText("precious\n")
        // Git itself reports this as a plain deletion and lists the nested repository nowhere.
        assertTrue(sandbox.gitText(repo, "ls-files", "-o").isEmpty())
        assertEquals(WorktreeDiscard.Refused, discard("b.txt"))
        assertTrue(nested.resolve("work.txt").exists())
        assertTrue(nested.resolve(".git").exists())
    }

    @Test
    fun `a tracked file replaced by an empty folder is refused too`() {
        repo.resolve("b.txt").deleteExisting()
        repo.resolve("b.txt").createDirectories()
        assertEquals(WorktreeDiscard.Refused, discard("b.txt"))
        assertTrue(Files.isDirectory(repo.resolve("b.txt")))
    }

    /** Removes the tracked folder `src` with its file, so `src/a.txt` is reported deleted. */
    private fun removeSrc() {
        repo.resolve("src/a.txt").deleteExisting()
        repo.resolve("src").deleteExisting()
    }

    @Test
    fun `a folder replaced by an untracked file is refused and the file stays`() {
        removeSrc()
        repo.resolve("src").writeText("precious\n")
        assertEquals(WorktreeDiscard.Refused, discard("src/a.txt"))
        assertEquals("precious\n", repo.resolve("src").readText())
    }

    @Test
    fun `a folder replaced by an ignored file is refused and the file stays`() {
        sandbox.commitFile(repo, ".gitignore", "*.log\nsrc\n")
        removeSrc()
        repo.resolve("src").writeText("precious\n")
        assertEquals("! src", sandbox.gitText(repo, "status", "--porcelain=v2", "--ignored=matching", "--", "src").trim().lines().last())
        assertEquals(WorktreeDiscard.Refused, discard("src/a.txt"))
        assertEquals("precious\n", repo.resolve("src").readText())
    }

    @Test
    fun `a folder replaced by a symlink to a folder outside is refused and both stay`() {
        val outside = tmp.resolve("outside").createDirectories()
        outside.resolve("keep.txt").writeText("keep\n")
        removeSrc()
        Files.createSymbolicLink(repo.resolve("src"), outside)
        assertEquals(WorktreeDiscard.Refused, discard("src/a.txt"))
        assertTrue(repo.resolve("src").isSymbolicLink())
        assertEquals("keep\n", outside.resolve("keep.txt").readText())
        assertFalse(outside.resolve("a.txt").exists())
    }

    @Test
    fun `a folder replaced by a symlink to another folder of the work tree is refused`() {
        repo.resolve("other").createDirectories()
        sandbox.commitFile(repo, "other/z.txt", "z\n")
        removeSrc()
        Files.createSymbolicLink(repo.resolve("src"), Path.of("other"))
        assertEquals(WorktreeDiscard.Refused, discard("src/a.txt"))
        assertFalse(repo.resolve("other/a.txt").exists())
        assertTrue(repo.resolve("src").isSymbolicLink())
    }

    @Test
    fun `a deeper folder replaced by an untracked file is refused`() {
        repo.resolve("a/b").createDirectories()
        sandbox.commitFile(repo, "a/b/c.txt", "c\n")
        repo.resolve("a/b/c.txt").deleteExisting()
        repo.resolve("a/b").deleteExisting()
        repo.resolve("a/b").writeText("precious\n")
        assertEquals(WorktreeDiscard.Refused, discard("a/b/c.txt"))
        assertEquals("precious\n", repo.resolve("a/b").readText())
    }

    @Test
    fun `a deeper folder replaced by a nested repository is refused`() {
        repo.resolve("a/b").createDirectories()
        sandbox.commitFile(repo, "a/b/c.txt", "c\n")
        repo.resolve("a/b/c.txt").deleteExisting()
        repo.resolve("a/b").deleteExisting()
        val nested = sandbox.newRepo("repo/a/b")
        nested.resolve("work.txt").writeText("precious\n")
        assertEquals(WorktreeDiscard.Refused, discard("a/b/c.txt"))
        assertEquals("precious\n", nested.resolve("work.txt").readText())
        assertFalse(nested.resolve("c.txt").exists())
    }

    @Test
    fun `a deleted file deep in deleted folders is restored`() {
        repo.resolve("a/b").createDirectories()
        sandbox.commitFile(repo, "a/b/c.txt", "c\n")
        repo.resolve("a/b/c.txt").deleteExisting()
        repo.resolve("a/b").deleteExisting()
        repo.resolve("a").deleteExisting()
        assertEquals(WorktreeDiscard.Discarded, discard("a/b/c.txt"))
        assertEquals("c\n", repo.resolve("a/b/c.txt").readText())
    }

    @Test
    fun `a modified file next to untracked files in its folder is restored`() {
        repo.resolve("src/a.txt").writeText("edit\n")
        repo.resolve("src/notes.txt").writeText("mine\n")
        assertEquals(WorktreeDiscard.Discarded, discard("src/a.txt"))
        assertEquals("original a\n", repo.resolve("src/a.txt").readText())
        assertEquals("mine\n", repo.resolve("src/notes.txt").readText())
    }

    @Test
    fun `ancestors are the folders above a path`() {
        assertEquals(listOf("a", "a/b"), DiscardCheck.ancestors("a/b/c.txt"))
        assertEquals(emptyList(), DiscardCheck.ancestors("top.txt"))
    }

    @Test
    fun `a deleted file in a deleted folder is restored`() {
        repo.resolve("src/a.txt").deleteExisting()
        repo.resolve("src").deleteExisting()
        assertEquals(WorktreeDiscard.Discarded, discard("src/a.txt"))
        assertEquals("original a\n", repo.resolve("src/a.txt").readText())
    }

    @Test
    fun `a folder of tracked files is refused`() {
        repo.resolve("src/a.txt").writeText("edit\n")
        assertEquals(WorktreeDiscard.Refused, discard("src"))
        assertEquals("edit\n", repo.resolve("src/a.txt").readText())
    }

    @Test
    fun `untracked, ignored, unchanged, staged-only and missing paths are refused`() {
        repo.resolve("u.txt").writeText("u\n")
        repo.resolve("i.log").writeText("i\n")
        repo.resolve("src/a.txt").writeText("staged\n")
        sandbox.git(repo, "add", "--", "src/a.txt")
        listOf("u.txt", "i.log", "b.txt", "src/a.txt", "missing.txt").forEach { path ->
            assertEquals(WorktreeDiscard.Refused, discard(path), path)
        }
        assertEquals("u\n", repo.resolve("u.txt").readText())
        assertEquals("i\n", repo.resolve("i.log").readText())
        assertEquals("staged\n", repo.resolve("src/a.txt").readText())
    }

    @Test
    fun `one bad path refuses the whole call`() {
        repo.resolve("-rf").writeText("edit\n")
        folderInPlaceOfFile("notes.txt")
        assertEquals(WorktreeDiscard.Refused, discard("-rf", "b.txt"))
        assertEquals("edit\n", repo.resolve("-rf").readText())
        assertTrue(repo.resolve("b.txt/notes.txt").exists())
    }

    @Test
    fun `from a subfolder nothing matches and the call is refused`() {
        repo.resolve("src/a.txt").writeText("edit\n")
        assertEquals(WorktreeDiscard.Refused, discard("a.txt", dir = repo.resolve("src")))
        assertEquals("edit\n", repo.resolve("src/a.txt").readText())
    }

    @Test
    fun `outside a repository the call fails and changes nothing`() {
        val plain = tmp.resolve("plain").createDirectories()
        plain.resolve("f.txt").writeText("f\n")
        assertIs<WorktreeDiscard.Failed>(discard("f.txt", dir = plain))
        assertEquals("f\n", plain.resolve("f.txt").readText())
    }

    @Test
    fun `control - checkout-index -f alone replaces the folder and its untracked file`() {
        val folder = folderInPlaceOfFile("notes.txt")
        val direct = runBlocking { sandbox.runner.run(GitCommands.discardWorktree(repo.absolutePathString(), listOf("b.txt"))) }
        assertTrue(direct.succeeded, direct.stderrText())
        assertFalse(folder.resolve("notes.txt").exists(), "the hazard did not show; the refusal tests prove nothing")
    }

    @Test
    fun `the check reads porcelain records`() {
        fun out(vararg records: String) = records.joinToString("") { "$it\u0000" }.toByteArray()
        val modified = "1 .M N... 100644 100644 100644 ${"a".repeat(40)} ${"a".repeat(40)} a b.txt"
        assertTrue(DiscardCheck.allows(listOf("a b.txt"), out(modified)))
        assertFalse(DiscardCheck.allows(listOf("a b.txt"), out(modified, "? a b.txt/x")))
        assertFalse(DiscardCheck.allows(listOf("a b.txt"), out(modified, "! a b.txt/")))
        assertTrue(DiscardCheck.allows(listOf("a b.txt"), out(modified, "? a b.txtx")))
        val submodule = "1 .M SC.. 160000 160000 160000 ${"a".repeat(40)} ${"a".repeat(40)} a b.txt"
        assertFalse(DiscardCheck.allows(listOf("a b.txt"), out(submodule)))
        val toFolder = "1 .T N... 100644 100644 040000 ${"a".repeat(40)} ${"a".repeat(40)} a b.txt"
        assertFalse(DiscardCheck.allows(listOf("a b.txt"), out(toFolder)))
        val stagedOnly = "1 M. N... 100644 100644 100644 ${"a".repeat(40)} ${"b".repeat(40)} a b.txt"
        assertFalse(DiscardCheck.allows(listOf("a b.txt"), out(stagedOnly)))
        val unmerged = "u UU N... 100644 100644 100644 100644 ${"a".repeat(40)} ${"b".repeat(40)} ${"c".repeat(40)} a b.txt"
        assertFalse(DiscardCheck.allows(listOf("a b.txt"), out(unmerged)))
    }
}
