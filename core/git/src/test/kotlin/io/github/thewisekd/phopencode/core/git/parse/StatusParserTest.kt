package io.github.thewisekd.phopencode.core.git.parse

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusParserTest {
    @TempDir
    lateinit var root: File

    private fun TestRepo.status(vararg extra: String): GitStatus =
        StatusParser.parse(git(*(StatusParser.ARGUMENTS + extra).toTypedArray()))

    @Test
    fun initialRepositoryHasNoCommitsAndIsClean() {
        val repo = TestRepo.init(root)
        val status = repo.status()
        val branch = status.branch!!
        assertTrue(branch.isInitial)
        assertNull(branch.oid)
        assertEquals("main", branch.head)
        assertFalse(branch.isDetached)
        assertNull(branch.upstream)
        assertNull(branch.ahead)
        assertNull(branch.behind)
        assertTrue(status.isClean)
    }

    @Test
    fun untrackedNamesKeepTheirExactBytes() {
        val repo = TestRepo.init(root)
        val names = listOf("a b.txt", "line\nbreak.txt", "-rf", "\$(touch pwned).txt", "çğıöşü İ.txt", "dir/sub file")
        names.forEach { repo.write(it, "x\n") }
        repo.sh("printf 'x' > \"\$(printf 'bad\\377name.txt')\"")
        val status = repo.status()
        val paths = status.entries.map { assertIs<UntrackedEntry>(it).path }.toSet()
        val expected = names.map(GitPath::of).toSet() + GitPath(bytes("bad", 0xFF) + bytes("name.txt"))
        assertEquals(expected, paths)
        val bad = paths.single { !it.isValidUtf8 }
        assertEquals("bad�name.txt", bad.text)
    }

    @Test
    fun separatesStagedUnstagedAndUntracked() {
        val repo = TestRepo.init(root)
        repo.write("both.txt", "1\n")
        repo.write("gone.txt", "1\n")
        repo.write("staged.txt", "1\n")
        repo.commitAll("base")
        val head = repo.head()
        repo.write("both.txt", "2\n")
        repo.git("add", "both.txt")
        repo.write("both.txt", "3\n")
        File(repo.dir, "gone.txt").delete()
        repo.write("staged.txt", "2\n")
        repo.git("add", "staged.txt")
        repo.write("new file.txt", "n\n")
        repo.git("add", "new file.txt")
        repo.write("untracked.txt", "u\n")

        val status = repo.status()
        assertEquals(head, status.branch!!.oid)
        val byPath = status.entries.associateBy { it.path.text }
        val both = assertIs<ChangedEntry>(byPath.getValue("both.txt"))
        assertEquals(StatusCode.MODIFIED, both.index)
        assertEquals(StatusCode.MODIFIED, both.worktree)
        assertEquals(0b1_000_000_110_100_100, both.headMode)
        assertEquals(40, both.headHash.length)
        assertNull(both.submodule)
        val gone = assertIs<ChangedEntry>(byPath.getValue("gone.txt"))
        assertEquals(StatusCode.UNMODIFIED, gone.index)
        assertEquals(StatusCode.DELETED, gone.worktree)
        assertEquals(0, gone.worktreeMode)
        val staged = assertIs<ChangedEntry>(byPath.getValue("staged.txt"))
        assertEquals(StatusCode.MODIFIED to StatusCode.UNMODIFIED, staged.index to staged.worktree)
        val added = assertIs<ChangedEntry>(byPath.getValue("new file.txt"))
        assertEquals(StatusCode.ADDED, added.index)
        assertEquals("0".repeat(40), added.headHash)
        assertIs<UntrackedEntry>(byPath.getValue("untracked.txt"))
        assertEquals(5, status.entries.size)
    }

    @Test
    fun renameKeepsTheOriginalPath() {
        val repo = TestRepo.init(root)
        repo.write("old name.txt", "same content\nover lines\n")
        repo.commitAll("base")
        repo.git("mv", "old name.txt", "new\nnäme -rf.txt")
        val entry = assertIs<RenamedEntry>(repo.status().entries.single())
        assertEquals(RenameKind.RENAME, entry.kind)
        assertEquals(100, entry.score)
        assertEquals(StatusCode.RENAMED, entry.index)
        assertEquals(StatusCode.UNMODIFIED, entry.worktree)
        assertEquals(GitPath.of("new\nnäme -rf.txt"), entry.path)
        assertEquals(GitPath.of("old name.txt"), entry.originalPath)
    }

    @Test
    fun typeChangeFromFileToSymlink() {
        val repo = TestRepo.init(root)
        repo.write("link", "x\n")
        repo.commitAll("base")
        File(repo.dir, "link").delete()
        Files.createSymbolicLink(File(repo.dir, "link").toPath(), File("target").toPath())
        val entry = assertIs<ChangedEntry>(repo.status().entries.single())
        assertEquals(StatusCode.TYPE_CHANGED, entry.worktree)
        assertEquals(0b1_010_000_000_000_000, entry.worktreeMode)
    }

    @Test
    fun conflictsAreUnmergedEntries() {
        val repo = TestRepo.init(root)
        repo.write("both modified.txt", "base\n")
        repo.write("deleted by us.txt", "base\n")
        repo.commitAll("base")
        repo.git("checkout", "-q", "-b", "other")
        repo.write("both modified.txt", "theirs\n")
        repo.write("deleted by us.txt", "theirs\n")
        repo.write("both added.txt", "theirs\n")
        repo.commitAll("theirs")
        repo.git("checkout", "-q", "main")
        repo.write("both modified.txt", "ours\n")
        File(repo.dir, "deleted by us.txt").delete()
        repo.write("both added.txt", "ours\n")
        repo.commitAll("ours")
        repo.git("merge", "-q", "other", allowFailure = true)

        val byPath = repo.status().entries.associateBy { it.path.text }
        assertEquals(ConflictKind.BOTH_MODIFIED, assertIs<UnmergedEntry>(byPath.getValue("both modified.txt")).conflict)
        assertEquals(ConflictKind.BOTH_ADDED, assertIs<UnmergedEntry>(byPath.getValue("both added.txt")).conflict)
        val deleted = assertIs<UnmergedEntry>(byPath.getValue("deleted by us.txt"))
        assertEquals(ConflictKind.DELETED_BY_US, deleted.conflict)
        assertEquals(0, deleted.stage2Mode)
        assertEquals("0".repeat(40), deleted.stage2Hash)
        assertEquals(40, deleted.stage1Hash.length)
    }

    @Test
    fun detachedHead() {
        val repo = TestRepo.init(root)
        repo.commitAll("one")
        repo.git("checkout", "-q", "--detach")
        val branch = repo.status().branch!!
        assertTrue(branch.isDetached)
        assertNull(branch.head)
        assertEquals(repo.head(), branch.oid)
    }

    @Test
    fun upstreamAheadBehindAndGone() {
        val origin = TestRepo.bare(root, "origin.git")
        val seed = TestRepo.init(root, "seed")
        seed.commitAll("one")
        seed.git("remote", "add", "origin", origin.dir.absolutePath)
        seed.git("push", "-q", "origin", "main")
        val repo = TestRepo.clone(root, origin, "work")

        val inSync = repo.status().branch!!
        assertEquals("origin/main", inSync.upstream)
        assertEquals(0 to 0, inSync.ahead to inSync.behind)

        seed.commitAll("two")
        seed.commitAll("three")
        seed.git("push", "-q", "origin", "main")
        repo.git("fetch", "-q")
        repo.commitAll("local")
        val diverged = repo.status().branch!!
        assertEquals(1 to 2, diverged.ahead to diverged.behind)
        assertFalse(diverged.isUpstreamGone)

        repo.git("checkout", "-q", "-b", "çalışma/öz")
        assertNull(repo.status().branch!!.upstream)
        assertEquals("çalışma/öz", repo.status().branch!!.head)

        repo.git("push", "-q", "-u", "origin", "çalışma/öz")
        seed.git("push", "-q", "origin", "--delete", "çalışma/öz")
        repo.git("fetch", "-q", "--prune")
        val gone = repo.status().branch!!
        assertEquals("origin/çalışma/öz", gone.upstream)
        assertTrue(gone.isUpstreamGone)
        assertNull(gone.ahead)
    }

    @Test
    fun aheadBehindDisabledByConfigIsOverriddenOrUnknown() {
        val origin = TestRepo.bare(root, "origin.git")
        val seed = TestRepo.init(root, "seed")
        seed.commitAll("one")
        seed.git("remote", "add", "origin", origin.dir.absolutePath)
        seed.git("push", "-q", "origin", "main")
        val repo = TestRepo.clone(root, origin, "work")
        seed.commitAll("two")
        seed.git("push", "-q", "origin", "main")
        repo.git("fetch", "-q")
        repo.commitAll("local")
        repo.git("config", "status.aheadBehind", "false")

        val counted = repo.status().branch!!
        assertEquals(1 to 1, counted.ahead to counted.behind)
        assertFalse(counted.aheadBehindUnknown)

        // git 2.47 ignores status.aheadBehind for porcelain formats; --no-ahead-behind gives the
        // uncounted form that a git honouring the setting, or a caller without --ahead-behind, prints.
        val raw = repo.git("status", "--porcelain=v2", "-z", "--branch", "--no-ahead-behind")
        assertTrue(raw.toString(Charsets.UTF_8).contains("# branch.ab +? -?"), "uncounted form expected")
        val unknown = StatusParser.parse(raw).branch!!
        assertTrue(unknown.aheadBehindUnknown)
        assertNull(unknown.ahead)
        assertNull(unknown.behind)
        assertEquals("origin/main", unknown.upstream)
        assertFalse(unknown.isUpstreamGone)
    }

    @Test
    fun renamesDisabledByConfigAreStillDetected() {
        val repo = TestRepo.init(root)
        repo.write("old.txt", "same content\nover lines\n")
        repo.commitAll("base")
        repo.git("config", "status.renames", "false")
        repo.git("mv", "old.txt", "new.txt")
        val entry = assertIs<RenamedEntry>(repo.status().entries.single())
        assertEquals(GitPath.of("old.txt"), entry.originalPath)
    }

    @Test
    fun aheadBehindWithoutUpstreamIsMalformed() {
        val h = "1".repeat(40)
        assertFailsWith<GitParseException> {
            StatusParser.parse(bytes("# branch.oid $h\u0000# branch.head main\u0000# branch.ab +? -?\u0000"))
        }
    }

    @Test
    fun ignoredOnlyWhenAsked() {
        val repo = TestRepo.init(root)
        repo.write(".gitignore", "*.log\n")
        repo.commitAll("ignore")
        repo.write("debug run.log", "x\n")
        assertTrue(repo.status().isClean)
        val entry = assertIs<IgnoredEntry>(repo.status("--ignored=matching").entries.single())
        assertEquals(GitPath.of("debug run.log"), entry.path)
    }

    @Test
    fun withoutBranchHeadersBranchIsNull() {
        val repo = TestRepo.init(root)
        repo.commitAll("one")
        val status = StatusParser.parse(repo.git("status", "--porcelain=v2", "-z"))
        assertNull(status.branch)
        assertTrue(status.isClean)
        assertTrue(StatusParser.parse(ByteArray(0)).isClean)
    }

    @Test
    fun fixedSampleWithCopySubmoduleStashAndUnknownHeader() {
        val h1 = "1".repeat(40)
        val h2 = "2".repeat(40)
        val raw = "# branch.oid $h1\u0000# branch.head main\u0000# future.header value\u0000# stash 3\u0000" +
            "2 C. N... 100644 100644 100644 $h1 $h1 C75 copy name\u0000orig name\u0000" +
            "1 .M SCMU 160000 160000 160000 $h2 $h2 vendor/lib\u0000" +
            "u AU N... 000000 100644 100644 100644 ${"0".repeat(40)} $h1 $h2 conflict\u0000"
        val status = StatusParser.parse(bytes(raw))
        assertEquals(3, status.stashCount)
        val copy = assertIs<RenamedEntry>(status.entries[0])
        assertEquals(RenameKind.COPY, copy.kind)
        assertEquals(75, copy.score)
        assertEquals(StatusCode.COPIED, copy.index)
        assertEquals(GitPath.of("copy name"), copy.path)
        assertEquals(GitPath.of("orig name"), copy.originalPath)
        val sub = assertIs<ChangedEntry>(status.entries[1])
        assertEquals(SubmoduleStatus(commitChanged = true, hasTrackedChanges = true, hasUntrackedChanges = true), sub.submodule)
        assertEquals(ConflictKind.ADDED_BY_US, assertIs<UnmergedEntry>(status.entries[2]).conflict)
    }

    @Test
    fun sha256HashesAreAccepted() {
        val h = "a".repeat(64)
        val status = StatusParser.parse(bytes("# branch.oid $h\u0000# branch.head main\u00001 M. N... 100644 100644 100644 $h $h f\u0000"))
        assertEquals(h, status.branch!!.oid)
        assertEquals(h, assertIs<ChangedEntry>(status.entries.single()).indexHash)
    }

    @Test
    fun malformedInputFailsWithAParseError() {
        val h = "1".repeat(40)
        val bad = listOf(
            "? missing terminator",
            "# branch.oid $h",
            "x unknown\u0000",
            "?\u0000",
            "? \u0000",
            "1 M. N... 100644 100644 100644 $h $h\u0000",
            "1 MX N... 100644 100644 100644 $h $h f\u0000",
            "1 M. N... 100648 100644 100644 $h $h f\u0000",
            "1 M. N... 100644 100644 100644 ${"g".repeat(40)} $h f\u0000",
            "1 M. Q... 100644 100644 100644 $h $h f\u0000",
            "1 M.  N... 100644 100644 100644 $h $h f\u0000",
            "2 R. N... 100644 100644 100644 $h $h R100 new\u0000",
            "2 R. N... 100644 100644 100644 $h $h X100 new\u0000old\u0000",
            "2 R. N... 100644 100644 100644 $h $h R1000 new\u0000old\u0000",
            "u MM N... 100644 100644 100644 100644 $h $h $h f\u0000",
            "# branch.ab +1\u0000",
            "# branch.ab +x -1\u0000",
            "# branch.oid nothex\u0000",
            "# stash -1\u0000",
            "\u0000",
        )
        for (sample in bad) {
            val e = assertFailsWith<GitParseException>(sample.replace("\u0000", "\\0")) { StatusParser.parse(bytes(sample)) }
            assertTrue(e.offset >= 0)
        }
    }

    @Test
    fun parseErrorsDoNotLeakContent() {
        val e = assertFailsWith<GitParseException> { StatusParser.parse(bytes("1 M. N... secret-path-name")) }
        assertFalse(e.message!!.contains("secret"))
    }

    @Test
    fun toStringHidesNames() {
        val branch = StatusBranch("1".repeat(40), "secret-branch", "origin/secret-branch", 1, 2)
        assertFalse(branch.toString().contains("secret"))
        assertFalse(UntrackedEntry(GitPath.of("secret.txt")).toString().contains("secret"))
    }
}
