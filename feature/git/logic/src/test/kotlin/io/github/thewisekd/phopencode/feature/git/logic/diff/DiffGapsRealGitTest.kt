package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLine
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLineKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

/** Merge commits against their first parent and untracked files with content, on real git. */
@Timeout(30)
class DiffGapsRealGitTest {
    @TempDir
    lateinit var temp: Path

    private val sandbox by lazy { DiffSandbox(temp) }

    private suspend fun TestScope.diff(directory: Path, target: DiffTarget, maxBytes: Long = DefaultDiffPresenter.DEFAULT_MAX_BYTES): GitFileDiff {
        val presenter = DefaultDiffPresenter(directory.absolutePathString(), target, ExecGitConnection(sandbox.exec), backgroundScope, maxBytes)
        val state = presenter.state.first { !it.loading }
        assertNull(state.error, "error ${state.error}")
        return assertNotNull(state.diff)
    }

    private fun GitFileDiff.lines(): List<DiffLine> = (content as DiffContent.Text).hunks.flatMap { it.lines }

    @Test
    fun `a merge's deleted and modified files show against the first parent`() = runTest(timeout = 10.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "gone.txt", "gone\n")
        sandbox.write(repo, "both.txt", "a\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "switch", "-q", "-c", "side")
        sandbox.git(repo, "rm", "-q", "--", "gone.txt")
        sandbox.write(repo, "both.txt", "a\nside\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "switch", "-q", "main")
        sandbox.write(repo, "main.txt", "main\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "merge", "-q", "--no-edit", "side")
        val merge = sandbox.git(repo, "rev-parse", "HEAD").trim()

        val gone = diff(repo, DiffTarget.InCommit(merge, "gone.txt"))
        assertEquals(GitChangeKind.Deleted, gone.kind)
        assertEquals(listOf(DiffLine(DiffLineKind.Deleted, "gone", 1, null)), gone.lines())
        val both = diff(repo, DiffTarget.InCommit(merge, "both.txt"))
        assertEquals(GitChangeKind.Modified, both.kind)
        assertEquals(1 to 0, both.additions to both.deletions)
        val tooLarge = diff(repo, DiffTarget.InCommit(merge, "both.txt"), maxBytes = 10)
        assertEquals(DiffContent.TooLarge(10), tooLarge.content)
        assertEquals(1 to 0, tooLarge.additions to tooLarge.deletions)
    }

    @Test
    fun `untracked files with hostile names show their lines`() = runTest(timeout = 10.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "kept.txt", "a\n")
        sandbox.commitAll(repo)
        val names = listOf("-rf", "\$(touch pwned-subst).txt", "a b\nc.txt", "çalışma ağacı/dosya.txt", "*.txt")
        names.forEach { sandbox.write(repo, it, "one\ntwo\n") }

        for (name in names) {
            val diff = diff(repo, DiffTarget.Working(name))
            assertEquals(name, diff.path)
            assertEquals(GitChangeKind.Untracked, diff.kind)
            assertEquals(listOf(DiffLine(DiffLineKind.Added, "one", null, 1), DiffLine(DiffLineKind.Added, "two", null, 2)), diff.lines())
        }
        assertEquals(emptyList(), sandbox.pwnedFiles())
        assertEquals("", sandbox.git(repo, "diff", "--cached", "--name-only"))
    }

    @Test
    fun `untracked binary, too large and empty files`() = runTest(timeout = 10.seconds) {
        val repo = sandbox.newRepo()
        repo.resolve("bin").writeBytes(byteArrayOf(0, 1, 2, 0))
        repo.resolve("big.txt").writeText("line\n".repeat(5_000))
        repo.resolve("empty").writeText("")

        assertEquals(GitFileDiff("bin", null, GitChangeKind.Untracked, DiffContent.Binary, 0, 0), diff(repo, DiffTarget.Working("bin")))
        assertEquals(
            GitFileDiff("big.txt", null, GitChangeKind.Untracked, DiffContent.TooLarge(1_000), 5_000, 0),
            diff(repo, DiffTarget.Working("big.txt"), maxBytes = 1_000),
        )
        assertEquals(GitFileDiff("empty", null, GitChangeKind.Untracked, DiffContent.Unchanged, 0, 0), diff(repo, DiffTarget.Working("empty")))
    }

    @Test
    fun `an untracked symlink shows where it points and a file beyond a symlinked folder is never read`() = runTest(timeout = 10.seconds) {
        val repo = sandbox.newRepo()
        val outside = temp.resolve("outside").createDirectories()
        outside.resolve("secret.txt").writeText("secret\n")
        Files.createSymbolicLink(repo.resolve("link"), outside.resolve("secret.txt"))
        Files.createSymbolicLink(repo.resolve("out"), outside)

        val link = diff(repo, DiffTarget.Working("link"))
        assertEquals(GitChangeKind.Untracked, link.kind)
        assertEquals(listOf(outside.resolve("secret.txt").absolutePathString()), link.lines().filter { it.kind == DiffLineKind.Added }.map { it.text })
        assertEquals(DiffContent.Unchanged, diff(repo, DiffTarget.Working("out/secret.txt")).content)
    }

    @Test
    fun `an untracked file from a subfolder`() = runTest(timeout = 10.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "sub/deep/new.txt", "x\n")

        val diff = diff(repo.resolve("sub"), DiffTarget.Working("sub/deep/new.txt"))

        assertEquals(GitChangeKind.Untracked, diff.kind)
        assertEquals(listOf(DiffLine(DiffLineKind.Added, "x", null, 1)), diff.lines())
    }
}
