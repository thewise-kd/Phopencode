package io.github.thewisekd.phopencode.feature.git.logic.diff

import io.github.thewisekd.phopencode.core.ssh.api.ExecRequest
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffContent
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLine
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffLineKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffState
import io.github.thewisekd.phopencode.feature.git.logic.contract.DiffTarget
import io.github.thewisekd.phopencode.feature.git.logic.contract.ExecGitConnection
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFailure
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitFileDiff
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir

/** The presenter on real git in temporary repositories, through a local shell. */
class DiffRealGitTest {
    @TempDir
    lateinit var temp: Path

    private val sandbox by lazy { DiffSandbox(temp) }

    private suspend fun TestScope.load(directory: Path, target: DiffTarget, maxBytes: Long = DefaultDiffPresenter.DEFAULT_MAX_BYTES): DiffState {
        val presenter = DefaultDiffPresenter(
            directory = directory.absolutePathString(),
            target = target,
            git = ExecGitConnection(sandbox.exec),
            scope = backgroundScope,
            maxBytes = maxBytes,
        )
        return presenter.state.first { !it.loading }
    }

    private suspend fun TestScope.diff(directory: Path, target: DiffTarget, maxBytes: Long = DefaultDiffPresenter.DEFAULT_MAX_BYTES): GitFileDiff {
        val state = load(directory, target, maxBytes)
        assertNull(state.error, "error ${state.error}")
        return assertNotNull(state.diff)
    }

    private fun GitFileDiff.lines(): List<DiffLine> = (content as DiffContent.Text).hunks.flatMap { it.lines }

    private fun GitFileDiff.changed(): List<Pair<DiffLineKind, String>> =
        lines().filter { it.kind == DiffLineKind.Added || it.kind == DiffLineKind.Deleted }.map { it.kind to it.text }

    @Test
    fun `working and staged diffs of the same file differ`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "f.txt", "a\nb\nc\n")
        sandbox.commitAll(repo)
        sandbox.write(repo, "f.txt", "a\nB\nc\n")
        sandbox.git(repo, "add", "f.txt")
        sandbox.write(repo, "f.txt", "a\nB\nC\n")

        val staged = diff(repo, DiffTarget.Staged("f.txt"))
        val working = diff(repo, DiffTarget.Working("f.txt"))

        assertEquals(listOf(DiffLineKind.Deleted to "b", DiffLineKind.Added to "B"), staged.changed())
        assertEquals(listOf(DiffLineKind.Deleted to "c", DiffLineKind.Added to "C"), working.changed())
        assertEquals(GitChangeKind.Modified, working.kind)
        assertEquals(1 to 1, working.additions to working.deletions)
    }

    @Test
    fun `a subfolder runs in the root, as status prints paths`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "sub/deep/f.txt", "a\n")
        sandbox.commitAll(repo)
        sandbox.write(repo, "sub/deep/f.txt", "b\n")

        val diff = diff(repo.resolve("sub/deep"), DiffTarget.Working("sub/deep/f.txt"))

        assertEquals(listOf(DiffLineKind.Deleted to "a", DiffLineKind.Added to "b"), diff.changed())
    }

    @Test
    fun `a commit file diff and a root commit`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "f.txt", "one\ntwo\n")
        val first = sandbox.commitAll(repo)
        sandbox.write(repo, "f.txt", "one\nzwei\n")
        sandbox.write(repo, "other.txt", "x\n")
        val second = sandbox.commitAll(repo)

        val change = diff(repo, DiffTarget.InCommit(second, "f.txt"))
        assertEquals(listOf(DiffLineKind.Deleted to "two", DiffLineKind.Added to "zwei"), change.changed())

        val root = diff(repo, DiffTarget.InCommit(first, "f.txt"))
        assertEquals(GitChangeKind.Added, root.kind)
        assertEquals(listOf(DiffLine(DiffLineKind.Added, "one", null, 1), DiffLine(DiffLineKind.Added, "two", null, 2)), root.lines())

        val untouched = diff(repo, DiffTarget.InCommit(first, "other.txt"))
        assertEquals(DiffContent.Unchanged, untouched.content)

        val short = diff(repo, DiffTarget.InCommit(second.take(10), "f.txt"))
        assertEquals(change, short)
    }

    @Test
    fun `a merge commit, a missing commit and a blob are refused`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "f.txt", "a\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "checkout", "-q", "-b", "side")
        sandbox.write(repo, "g.txt", "side\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "checkout", "-q", "main")
        sandbox.write(repo, "f.txt", "b\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "merge", "-q", "--no-edit", "side")
        val merge = sandbox.git(repo, "rev-parse", "HEAD").trim()
        val blob = sandbox.git(repo, "rev-parse", "HEAD:f.txt").trim()

        assertEquals(GitFailure.Unknown, load(repo, DiffTarget.InCommit(merge, "g.txt")).error)
        assertEquals(GitFailure.NotFound, load(repo, DiffTarget.InCommit("0123456789abcdef0123456789abcdef01234567", "f.txt")).error)
        assertEquals(GitFailure.NotFound, load(repo, DiffTarget.InCommit(blob, "f.txt")).error)
    }

    @Test
    fun `several hunks keep their line numbers`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        val lines = (1..30).map { "line $it" }
        sandbox.write(repo, "f.txt", lines.joinToString("\n", postfix = "\n"))
        sandbox.commitAll(repo)
        sandbox.write(repo, "f.txt", lines.map { if (it == "line 2" || it == "line 28") "$it changed" else it }.joinToString("\n", postfix = "\n"))

        val diff = diff(repo, DiffTarget.Working("f.txt"))

        val hunks = (diff.content as DiffContent.Text).hunks
        assertEquals(2, hunks.size)
        assertEquals(listOf(1, 25), hunks.map { it.oldStart })
        assertTrue(DiffLine(DiffLineKind.Deleted, "line 28", 28, null) in hunks[1].lines)
        assertTrue(DiffLine(DiffLineKind.Added, "line 28 changed", null, 28) in hunks[1].lines)
        assertTrue(DiffLine(DiffLineKind.Context, "line 30", 30, 30) in hunks[1].lines)
    }

    @Test
    fun `a missing final newline gets its marker`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "f.txt", "a\nb")
        sandbox.commitAll(repo)
        sandbox.write(repo, "f.txt", "a\nc")

        val lines = diff(repo, DiffTarget.Working("f.txt")).lines()

        assertEquals(
            listOf(
                DiffLine(DiffLineKind.Context, "a", 1, 1),
                DiffLine(DiffLineKind.Deleted, "b", 2, null),
                DiffLine(DiffLineKind.NoNewline, NO_NEWLINE_MARKER, null, null),
                DiffLine(DiffLineKind.Added, "c", null, 2),
                DiffLine(DiffLineKind.NoNewline, NO_NEWLINE_MARKER, null, null),
            ),
            lines,
        )
    }

    @Test
    fun `a binary file has no lines`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        repo.resolve("image.bin").writeBytes(byteArrayOf(0, 1, 2, 3, 0, 5))
        sandbox.commitAll(repo)
        repo.resolve("image.bin").writeBytes(byteArrayOf(0, 1, 9, 3, 0, 5, 6))

        val diff = diff(repo, DiffTarget.Working("image.bin"))

        assertTrue(diff.isBinary)
        assertEquals(GitChangeKind.Modified, diff.kind)
    }

    @Test
    fun `a diff over the limit is too large, with git's counts, and is never read whole`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "big.txt", (1..5_000).joinToString("\n", postfix = "\n") { "old line $it" })
        sandbox.commitAll(repo)
        sandbox.write(repo, "big.txt", (1..5_000).joinToString("\n", postfix = "\n") { "new line $it" })

        val diff = diff(repo, DiffTarget.Working("big.txt"), maxBytes = 4_096)

        assertEquals(GitFileDiff("big.txt", null, GitChangeKind.Modified, DiffContent.TooLarge(4_096), 5_000, 5_000), diff)
        assertTrue(4_096L in sandbox.exec.limits)

        val small = diff(repo, DiffTarget.Working("big.txt"))
        assertEquals(5_000, small.additions)
        assertTrue(small.content is DiffContent.Text)
    }

    @Test
    fun `a rename, a new file and a deleted file`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        val body = (1..20).joinToString("\n", postfix = "\n") { "line $it" }
        sandbox.write(repo, "old name.txt", body)
        sandbox.write(repo, "gone.txt", "bye\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "mv", "old name.txt", "new name.txt")
        sandbox.write(repo, "new name.txt", body.replace("line 10\n", "line ten\n"))
        sandbox.git(repo, "add", "new name.txt")
        sandbox.write(repo, "fresh.txt", "hello\n")
        sandbox.git(repo, "add", "fresh.txt")
        repo.resolve("gone.txt").deleteExisting()

        val renamed = diff(repo, DiffTarget.Staged("new name.txt"))
        assertEquals(GitChangeKind.Renamed, renamed.kind)
        assertEquals("old name.txt", renamed.oldPath)
        assertEquals(listOf(DiffLineKind.Deleted to "line 10", DiffLineKind.Added to "line ten"), renamed.changed())

        val added = diff(repo, DiffTarget.Staged("fresh.txt"))
        assertEquals(GitChangeKind.Added, added.kind)
        assertEquals(listOf(DiffLine(DiffLineKind.Added, "hello", null, 1)), added.lines())

        val deleted = diff(repo, DiffTarget.Working("gone.txt"))
        assertEquals(GitChangeKind.Deleted, deleted.kind)
        assertEquals(listOf(DiffLine(DiffLineKind.Deleted, "bye", 1, null)), deleted.lines())

        val committed = sandbox.commitAll(repo)
        val inCommit = diff(repo, DiffTarget.InCommit(committed, "new name.txt"))
        assertEquals(GitChangeKind.Renamed, inCommit.kind)
        assertEquals("old name.txt", inCommit.oldPath)
    }

    @Test
    fun `an untracked file, an unchanged file and a file that is not there`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "kept.txt", "a\n")
        sandbox.commitAll(repo)
        sandbox.write(repo, "new.txt", "hello\n")

        assertEquals(GitChangeKind.Untracked, diff(repo, DiffTarget.Working("new.txt")).kind)
        assertEquals(DiffContent.Unchanged, diff(repo, DiffTarget.Working("kept.txt")).content)
        assertEquals(DiffContent.Unchanged, diff(repo, DiffTarget.Staged("missing.txt")).content)
    }

    @Test
    fun `a file that became a symlink`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "link", "a\n")
        sandbox.commitAll(repo)
        repo.resolve("link").deleteExisting()
        Files.createSymbolicLink(repo.resolve("link"), Path.of("target"))

        val diff = diff(repo, DiffTarget.Working("link"))

        assertEquals(GitChangeKind.TypeChanged, diff.kind)
        assertEquals(1 to 1, diff.additions to diff.deletions)
    }

    @Test
    fun `an unmerged file is a conflict`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "f.txt", "a\nb\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "checkout", "-q", "-b", "side")
        sandbox.write(repo, "f.txt", "a\nside\n")
        sandbox.commitAll(repo)
        sandbox.git(repo, "checkout", "-q", "main")
        sandbox.write(repo, "f.txt", "a\nmain\n")
        sandbox.commitAll(repo)
        sandbox.shell(repo, "git merge -q side >/dev/null 2>&1; test -f .git/MERGE_HEAD")

        assertEquals(GitFailure.Conflict, load(repo, DiffTarget.Working("f.txt")).error)
        assertEquals(GitFailure.Conflict, load(repo, DiffTarget.Staged("f.txt")).error)
    }

    @Test
    fun `a conflicted file larger than the limit is still a conflict`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        val body = { tag: String -> (1..3_000).joinToString("\n", postfix = "\n") { "$tag line $it" } }
        sandbox.write(repo, "big.txt", body("base"))
        sandbox.commitAll(repo)
        sandbox.git(repo, "checkout", "-q", "-b", "side")
        sandbox.write(repo, "big.txt", body("side"))
        sandbox.commitAll(repo)
        sandbox.git(repo, "checkout", "-q", "main")
        sandbox.write(repo, "big.txt", body("main"))
        sandbox.commitAll(repo)
        sandbox.shell(repo, "git merge -q side >/dev/null 2>&1; test -f .git/MERGE_HEAD")

        assertEquals(GitFailure.Conflict, load(repo, DiffTarget.Working("big.txt"), maxBytes = 4_096).error)
        assertEquals(GitFailure.Conflict, load(repo, DiffTarget.Staged("big.txt"), maxBytes = 4_096).error)
    }

    @Test
    fun `the local shell ends its own child at the timeout`() = runTest(timeout = 120.seconds) {
        val started = System.nanoTime()
        val failure = runCatching {
            sandbox.exec.run(ExecRequest("sleep 30", timeout = 1.seconds), 1_024)
        }.exceptionOrNull()

        assertTrue(failure is SshException.Timeout, "got $failure")
        assertTrue(System.nanoTime() - started < 20_000_000_000L)
    }

    @Test
    fun `cancelling a local run ends its child quickly`() = runTest(timeout = 120.seconds) {
        val call = async(Dispatchers.Default) {
            sandbox.exec.run(ExecRequest("sleep 30", timeout = 60.seconds), 1_024)
        }
        val started = System.nanoTime()
        withContext(Dispatchers.Default) {
            while (sandbox.exec.commands.isEmpty()) delay(10)
            delay(500)
        }
        call.cancel()
        withContext(Dispatchers.Default) { call.join() }

        assertTrue(call.isCancelled)
        assertTrue(System.nanoTime() - started < 20_000_000_000L)
    }

    private fun oneLine(name: String) = name.replace('\n', ' ')

    @Test
    fun `hostile names are paths, never options or shell code`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        val names = listOf(
            "-rf",
            "--output=pwned-option",
            "\$(touch pwned-subst).txt",
            "`touch pwned-tick`.txt",
            "a b\nc.txt",
            "quote\"and'apostrophe\\.txt",
            "tab\there.txt",
            "*.txt",
            ":(top)magic.txt",
            "çalışma ağacı/dosya.txt",
        )
        names.forEach { sandbox.write(repo, it, "old ${oneLine(it)}\n") }
        sandbox.write(repo, "other.txt", "other\n")
        sandbox.commitAll(repo)
        names.forEach { sandbox.write(repo, it, "new ${oneLine(it)}\n") }
        sandbox.write(repo, "other.txt", "changed\n")
        sandbox.git(repo, "add", "-A")

        for (name in names) {
            val diff = diff(repo, DiffTarget.Staged(name))
            assertEquals(name, diff.path)
            assertEquals(listOf(DiffLineKind.Deleted to "old ${oneLine(name)}", DiffLineKind.Added to "new ${oneLine(name)}"), diff.changed(), "for one name")
        }
        assertEquals(emptyList(), sandbox.pwnedFiles())
    }

    @Test
    fun `non-UTF-8 names and content`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        repo.resolve("latin1.txt").writeBytes("café\n".toByteArray(Charsets.ISO_8859_1))
        sandbox.shell(repo, "printf 'x\\n' > \"\$(printf 'caf\\351.txt')\"")
        sandbox.commitAll(repo)
        repo.resolve("latin1.txt").writeBytes("naïve\n".toByteArray(Charsets.ISO_8859_1))
        sandbox.shell(repo, "printf 'y\\n' > \"\$(printf 'caf\\351.txt')\"")
        val commit = sandbox.commitAll(repo)

        // The content is shown with replacement characters; the other file's name does not disturb it.
        val content = diff(repo, DiffTarget.InCommit(commit, "latin1.txt"))
        assertEquals(listOf(DiffLineKind.Deleted to "caf�", DiffLineKind.Added to "na�ve"), content.changed())

        // A route's path is text: a name that is not UTF-8 cannot be named exactly, so it matches nothing.
        val name = diff(repo, DiffTarget.InCommit(commit, "caf�.txt"))
        assertEquals(DiffContent.Unchanged, name.content)
    }

    @Test
    fun `outside a repository`() = runTest(timeout = 120.seconds) {
        val plain = temp.resolve("plain").createDirectories()

        assertEquals(GitFailure.NotRepository, load(plain, DiffTarget.Working("f.txt")).error)
        assertEquals(GitFailure.NotFound, load(temp.resolve("absent"), DiffTarget.Working("f.txt")).error)
    }

    @Test
    fun `staged files before the first commit`() = runTest(timeout = 120.seconds) {
        val repo = sandbox.newRepo()
        sandbox.write(repo, "first.txt", "hello\n")
        sandbox.git(repo, "add", "first.txt")

        val diff = diff(repo, DiffTarget.Staged("first.txt"))

        assertEquals(GitChangeKind.Added, diff.kind)
        assertEquals(1 to 0, diff.additions to diff.deletions)
    }
}
