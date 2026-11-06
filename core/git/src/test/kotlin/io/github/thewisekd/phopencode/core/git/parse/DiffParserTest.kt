package io.github.thewisekd.phopencode.core.git.parse

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiffParserTest {
    @TempDir
    lateinit var root: File

    private fun TestRepo.diff(vararg extra: String, config: List<String> = emptyList()): List<DiffFile> =
        DiffParser.parse(git(*(config.flatMap { listOf("-c", it) } + DiffParser.ARGUMENTS + extra).toTypedArray()))

    private fun lines(range: IntRange, changed: Map<Int, String> = emptyMap()): String =
        range.joinToString("") { (changed[it] ?: "line $it") + "\n" }

    private val octal644 = 0b1_000_000_110_100_100
    private val octal755 = 0b1_000_000_111_101_101

    @Test
    fun emptyDiffIsNoFiles() {
        val repo = TestRepo.init(root)
        repo.commitAll("one")
        assertEquals(emptyList(), repo.diff())
        assertEquals(emptyList(), DiffParser.parse(ByteArray(0)))
    }

    @Test
    fun modificationWithThreeHunksAndLineNumbers() {
        val repo = TestRepo.init(root)
        repo.write("f.txt", lines(1..30))
        repo.commitAll("base")
        repo.write("f.txt", lines(1..30, mapOf(2 to "changed 2", 25 to "changed 25")).replace("line 10\n", ""))

        val file = repo.diff().single()
        assertEquals(DiffChange.MODIFIED, file.change)
        assertEquals(GitPath.of("f.txt"), file.oldPath)
        assertEquals(GitPath.of("f.txt"), file.newPath)
        assertEquals(octal644, file.oldMode)
        assertEquals(octal644, file.newMode)
        assertEquals(7, file.oldHash!!.length)
        assertFalse(file.isBinary)
        assertEquals(3, file.deletions)
        assertEquals(2, file.additions)
        assertEquals(3, file.hunks.size)

        val first = file.hunks[0]
        assertEquals(1, first.oldStart)
        assertEquals(1, first.newStart)
        val removed = first.lines.single { it.kind == DiffLineKind.REMOVED }
        assertEquals("line 2", removed.text)
        assertEquals(2, removed.oldLine)
        assertNull(removed.newLine)
        val added = first.lines.single { it.kind == DiffLineKind.ADDED }
        assertEquals("changed 2", added.text)
        assertEquals(2, added.newLine)
        assertNull(added.oldLine)
        val context = first.lines.first()
        assertEquals(DiffLineKind.CONTEXT, context.kind)
        assertEquals(1, context.oldLine)
        assertEquals(1, context.newLine)

        assertEquals(10, file.hunks[1].lines.single { it.kind == DiffLineKind.REMOVED }.oldLine)
        val second = file.hunks[2]
        val removed25 = second.lines.single { it.kind == DiffLineKind.REMOVED && it.text == "line 25" }
        assertEquals(25, removed25.oldLine)
        assertEquals(24, second.lines.single { it.kind == DiffLineKind.ADDED }.newLine)
        for (h in file.hunks) {
            assertEquals(h.oldCount, h.lines.count { it.kind != DiffLineKind.ADDED })
            assertEquals(h.newCount, h.lines.count { it.kind != DiffLineKind.REMOVED })
        }
    }

    @Test
    fun noNewlineAtEndOfFile() {
        val repo = TestRepo.init(root)
        repo.write("f", "a\nb")
        repo.commitAll("base")
        repo.write("f", "a\nc\n")
        val lines = repo.diff().single().hunks.single().lines
        val removed = lines.single { it.kind == DiffLineKind.REMOVED }
        assertEquals("b", removed.text)
        assertTrue(removed.noNewlineAtEnd)
        val added = lines.single { it.kind == DiffLineKind.ADDED }
        assertFalse(added.noNewlineAtEnd)

        repo.commitAll("two")
        repo.write("f", "a\nc")
        val again = repo.diff().single().hunks.single().lines
        assertTrue(again.single { it.kind == DiffLineKind.ADDED }.noNewlineAtEnd)
        assertFalse(again.single { it.kind == DiffLineKind.REMOVED }.noNewlineAtEnd)
    }

    @Test
    fun binaryFilesHaveNoHunks() {
        val repo = TestRepo.init(root)
        repo.writeBytes("image.bin", byteArrayOf(0, 1, 2, 3, 0, 5))
        repo.commitAll("base")
        repo.writeBytes("image.bin", byteArrayOf(0, 9, 9, 9, 0, 5))
        repo.writeBytes("new image.bin", byteArrayOf(0, 7))
        repo.git("add", "-N", "new image.bin")
        val files = repo.diff().associateBy { it.path.text }
        val modified = files.getValue("image.bin")
        assertTrue(modified.isBinary)
        assertEquals(DiffChange.MODIFIED, modified.change)
        assertTrue(modified.hunks.isEmpty())
        val added = files.getValue("new image.bin")
        assertTrue(added.isBinary)
        assertEquals(DiffChange.ADDED, added.change)
        assertNull(added.oldPath)

        val withPatch = repo.diff("--binary").associateBy { it.path.text }
        assertTrue(withPatch.getValue("image.bin").isBinary)
        assertTrue(withPatch.getValue("new image.bin").isBinary)
        assertEquals(2, withPatch.size)
    }

    @Test
    fun renameWithModificationAndPureRename() {
        val repo = TestRepo.init(root)
        repo.write("old dir/a b.txt", lines(1..20))
        repo.write("keep.txt", lines(1..10, mapOf(1 to "keep")))
        repo.commitAll("base")
        File(repo.dir, "new dir").mkdirs()
        repo.git("mv", "old dir/a b.txt", "new dir/c d.txt")
        repo.write("new dir/c d.txt", lines(1..20, mapOf(20 to "edited")))
        repo.git("mv", "keep.txt", "kept -rf.txt")
        repo.git("add", "-A")

        val files = repo.diff("--cached").associateBy { it.path.text }
        val edited = files.getValue("new dir/c d.txt")
        assertEquals(DiffChange.RENAMED, edited.change)
        assertEquals(GitPath.of("old dir/a b.txt"), edited.oldPath)
        assertTrue(edited.similarity!! in 50..99)
        assertEquals(1, edited.additions)
        assertEquals(1, edited.deletions)

        val pure = files.getValue("kept -rf.txt")
        assertEquals(DiffChange.RENAMED, pure.change)
        assertEquals(GitPath.of("keep.txt"), pure.oldPath)
        assertEquals(100, pure.similarity)
        assertTrue(pure.hunks.isEmpty())
        assertNull(pure.oldHash)
    }

    @Test
    fun copiesAreDetectedWithFindCopiesHarder() {
        val repo = TestRepo.init(root)
        repo.write("source.txt", lines(1..20))
        repo.commitAll("base")
        repo.write("copy.txt", lines(1..20))
        repo.git("add", "-A")
        val copy = repo.diff("--cached", "--find-copies-harder").single()
        assertEquals(DiffChange.COPIED, copy.change)
        assertEquals(GitPath.of("source.txt"), copy.oldPath)
        assertEquals(GitPath.of("copy.txt"), copy.newPath)
    }

    @Test
    fun modeChangeOnly() {
        val repo = TestRepo.init(root)
        repo.write("run.sh", "echo hi\n")
        repo.commitAll("base")
        assertTrue(File(repo.dir, "run.sh").setExecutable(true))
        val file = repo.diff().single()
        assertTrue(file.isModeChange)
        assertEquals(octal644, file.oldMode)
        assertEquals(octal755, file.newMode)
        assertEquals(DiffChange.MODIFIED, file.change)
        assertTrue(file.hunks.isEmpty())
        assertEquals(GitPath.of("run.sh"), file.path)
    }

    @Test
    fun newDeletedEmptyAndSymlinkFilesInOneDiff() {
        val repo = TestRepo.init(root)
        repo.write("doomed.txt", "a\nb\nc\n")
        repo.commitAll("base")
        File(repo.dir, "doomed.txt").delete()
        repo.write("born.txt", "x\ny\n")
        repo.write("empty.txt", "")
        Files.createSymbolicLink(File(repo.dir, "link").toPath(), File("born.txt").toPath())
        repo.git("add", "-A")

        val files = repo.diff("--cached").associateBy { it.path.text }
        assertEquals(4, files.size)
        val doomed = files.getValue("doomed.txt")
        assertEquals(DiffChange.DELETED, doomed.change)
        assertNull(doomed.newPath)
        assertNull(doomed.newMode)
        assertEquals(octal644, doomed.oldMode)
        assertEquals(3, doomed.deletions)
        assertEquals(1, doomed.hunks.single().oldStart)
        assertEquals(0, doomed.hunks.single().newCount)
        val born = files.getValue("born.txt")
        assertEquals(DiffChange.ADDED, born.change)
        assertNull(born.oldMode)
        assertEquals(2, born.additions)
        assertEquals(listOf(1, 2), born.hunks.single().lines.map { it.newLine })
        val empty = files.getValue("empty.txt")
        assertEquals(DiffChange.ADDED, empty.change)
        assertTrue(empty.hunks.isEmpty())
        assertFalse(empty.isBinary)
        val link = files.getValue("link")
        assertEquals(0b1_010_000_000_000_000, link.newMode)
        assertEquals("born.txt", link.hunks.single().lines.single().text)
        assertTrue(link.hunks.single().lines.single().noNewlineAtEnd)
    }

    @Test
    fun awkwardNamesRoundTripWithAndWithoutQuotePath() {
        val repo = TestRepo.init(root)
        val names = listOf(
            "plain.txt",
            "with space.txt",
            "line\nbreak.txt",
            "tab\tname.txt",
            "-rf",
            "\$(touch pwned).txt",
            "quote\"and\\backslash.txt",
            "çğıöşü İ.txt",
            "dir/a b/c.txt",
            " leading and trailing ",
        )
        names.forEach { repo.write(it, "old\n") }
        repo.sh("printf 'old\\n' > \"\$(printf 'bad\\377name.txt')\"")
        repo.commitAll("base")
        names.forEach { repo.write(it, "new\n") }
        repo.sh("printf 'new\\n' > \"\$(printf 'bad\\377name.txt')\"")

        val expected = names.map(GitPath::of).toSet() + GitPath(bytes("bad", 0xFF) + bytes("name.txt"))
        for (config in listOf(emptyList(), listOf("core.quotePath=false"))) {
            val files = repo.diff(config = config)
            assertEquals(expected, files.map { it.path }.toSet(), "config $config")
            for (f in files) {
                assertEquals(f.oldPath, f.newPath)
                assertEquals(listOf("old", "new"), f.hunks.single().lines.map { it.text })
            }
        }
    }

    @Test
    fun renamesBetweenQuotedAndUnquotedNames() {
        val repo = TestRepo.init(root)
        repo.write("plain name.txt", lines(1..10))
        repo.write("other\nname.txt", lines(11..20))
        repo.commitAll("base")
        File(repo.dir, "new\nname b").mkdirs()
        File(repo.dir, "simple b").mkdirs()
        repo.git("mv", "plain name.txt", "new\nname b/x.txt")
        repo.git("mv", "other\nname.txt", "simple b/y.txt")
        val files = repo.diff("--cached").associateBy { it.oldPath!!.text }
        assertEquals(GitPath.of("new\nname b/x.txt"), files.getValue("plain name.txt").newPath)
        assertEquals(GitPath.of("simple b/y.txt"), files.getValue("other\nname.txt").newPath)
    }

    @Test
    fun crlfContentKeepsTheCarriageReturn() {
        val repo = TestRepo.init(root)
        repo.write("dos.txt", "one\r\ntwo\r\n")
        repo.commitAll("base")
        repo.write("dos.txt", "one\r\nzwei\r\n")
        val lines = repo.diff().single().hunks.single().lines
        assertContentEquals(bytes("zwei\r"), lines.single { it.kind == DiffLineKind.ADDED }.contentBytes())
        assertEquals("one\r", lines.first().text)
    }

    @Test
    fun nonUtf8ContentIsKeptAsBytes() {
        val repo = TestRepo.init(root)
        repo.writeBytes("latin1.txt", bytes("caf", 0xE9, 0x0A))
        repo.commitAll("base")
        repo.writeBytes("latin1.txt", bytes("caf", 0xE8, 0x0A))
        val lines = repo.diff().single().hunks.single().lines
        assertContentEquals(bytes("caf", 0xE9), lines[0].contentBytes())
        assertEquals("caf�", lines[0].text)
    }

    @Test
    fun suppressBlankEmptyContextLines() {
        val repo = TestRepo.init(root)
        repo.write("f", "a\n\nb\n")
        repo.commitAll("base")
        repo.write("f", "a\n\nc\n")
        val hunk = repo.diff(config = listOf("diff.suppressBlankEmpty=true")).single().hunks.single()
        val blank = hunk.lines[1]
        assertEquals(DiffLineKind.CONTEXT, blank.kind)
        assertEquals("", blank.text)
        assertEquals(2, blank.oldLine)
    }

    @Test
    fun hunkSectionHeaderAndDiffTree() {
        val repo = TestRepo.init(root)
        fun body(changed: Int) = (1..10).joinToString("") { if (it == changed) "\tx = -$it;\n" else "\tx = $it;\n" }
        repo.write("code.c", "int main(void)\n{\n" + body(0) + "}\n")
        repo.commitAll("base")
        repo.write("code.c", "int main(void)\n{\n" + body(9) + "}\n")
        repo.commitAll("edit")
        val hunk = repo.diff("HEAD~1", "HEAD").single().hunks.single()
        assertEquals("int main(void)", hunk.section)
        val tree = DiffParser.parse(
            repo.git("diff-tree", "-p", "--no-commit-id", "--no-color", "--no-ext-diff", "--find-renames", "--src-prefix=a/", "--dst-prefix=b/", "HEAD"),
        )
        assertEquals(hunk, tree.single().hunks.single())
    }

    @Test
    fun submoduleChangeWithDiffSubmoduleLogConfigured() {
        val sub = TestRepo.init(root, "subsrc")
        sub.write("s.txt", "s\n")
        sub.commitAll("sub base")
        val repo = TestRepo.init(root)
        repo.git("-c", "protocol.file.allow=always", "submodule", "add", "-q", sub.dir.absolutePath, "sub")
        repo.commitAll("add submodule")
        val oldCommit = repo.git("-C", "sub", "rev-parse", "HEAD").toString(Charsets.UTF_8).trim()
        repo.git("-C", "sub", "commit", "-q", "--allow-empty", "-m", "sub change")
        val newCommit = repo.git("-C", "sub", "rev-parse", "HEAD").toString(Charsets.UTF_8).trim()
        repo.git("config", "diff.submodule", "log")

        val summary = repo.git("diff", "--no-color")
        assertTrue(summary.toString(Charsets.UTF_8).startsWith("Submodule sub "), "the setting must take effect")
        assertFailsWith<GitParseException> { DiffParser.parse(summary) }

        val file = repo.diff().single()
        assertEquals(GitPath.of("sub"), file.path)
        assertEquals(0b1_110_000_000_000_000, file.newMode)
        assertEquals(
            listOf("Subproject commit $oldCommit", "Subproject commit $newCommit"),
            file.hunks.single().lines.map { it.text },
        )
    }

    @Test
    fun filesWithoutContentChangeAreCompleteWithoutHunks() {
        val repo = TestRepo.init(root)
        repo.write("empty doomed.txt", "")
        repo.write("run.sh", "echo\n")
        repo.write("moved.txt", lines(1..10))
        repo.commitAll("base")
        File(repo.dir, "empty doomed.txt").delete()
        repo.write("empty born.txt", "")
        assertTrue(File(repo.dir, "run.sh").setExecutable(true))
        repo.git("mv", "moved.txt", "moved again.txt")
        repo.git("add", "-A")

        // Without --no-renames git pairs the two empty files as a rename.
        val files = repo.diff("--cached", "--", "run.sh", "moved.txt", "moved again.txt").associateBy { it.path.text } +
            repo.diff("--cached", "--no-renames", "--", "empty doomed.txt", "empty born.txt").associateBy { it.path.text }
        assertEquals(4, files.size)
        assertEquals(DiffChange.DELETED, files.getValue("empty doomed.txt").change)
        assertEquals(DiffChange.ADDED, files.getValue("empty born.txt").change)
        assertTrue(files.getValue("run.sh").isModeChange)
        assertEquals(DiffChange.RENAMED, files.getValue("moved again.txt").change)
        assertTrue(files.values.all { it.hunks.isEmpty() })

        val sha256Empty = "diff --git a/e b/e\nnew file mode 100644\nindex 0000000..473a0f4\n"
        assertEquals(DiffChange.ADDED, DiffParser.parse(bytes(sha256Empty)).single().change)
    }

    @Test
    fun outputTruncatedAfterTheHeadersFails() {
        val repo = TestRepo.init(root)
        repo.write("modified.txt", "a\n")
        repo.write("doomed.txt", "d\n")
        repo.write("renamed.txt", lines(1..20))
        repo.commitAll("base")
        repo.write("modified.txt", "b\n")
        File(repo.dir, "doomed.txt").delete()
        repo.write("born.txt", "n\n")
        repo.git("mv", "renamed.txt", "renamed now.txt")
        repo.write("renamed now.txt", lines(1..20, mapOf(20 to "edited")))
        repo.git("add", "-A")

        for (path in listOf("modified.txt", "doomed.txt", "born.txt", "renamed now.txt")) {
            val raw = repo.git(*(DiffParser.ARGUMENTS + listOf("--cached", "--", path) + if (path.startsWith("renamed")) listOf("renamed.txt") else emptyList()).toTypedArray())
            val text = raw.toString(Charsets.UTF_8)
            assertEquals(1, DiffParser.parse(raw).size, path)
            val headerLines = text.substring(0, text.indexOf("\n--- ") + 1).split("\n").dropLast(1)
            // Cut after each header line past `diff --git`: every such prefix loses content.
            var cut = 0
            for ((i, line) in headerLines.withIndex()) {
                cut += line.length + 1
                if (i == 0) continue
                val prefix = text.substring(0, cut)
                assertFailsWith<GitParseException>("$path cut after '$line'") { DiffParser.parse(bytes(prefix)) }
            }
        }
    }

    @Test
    fun namesWithSpacesTrailingSpacesAndTabsInEveryChangeKind() {
        val names = listOf("sub/old name.txt", "trailing space ", "sub/both \t tab and space", "tab\tonly", "plain")
        for ((i, name) in names.withIndex()) {
            val repo = TestRepo.init(root, "names$i")
            repo.write("mod_$name", "a\n")
            repo.write("del_$name", "d\n")
            repo.write("ren_$name", lines(1..20))
            repo.commitAll("base")
            repo.write("mod_$name", "b\n")
            File(repo.dir, "del_$name").delete()
            repo.write("add_$name", "n\n")
            File(repo.dir, "ren_$name").delete()
            repo.write("ren2_$name", lines(1..20, mapOf(20 to "edited")))
            repo.git("add", "-A")
            for (config in listOf(emptyList(), listOf("core.quotePath=false"))) {
                val raw = repo.git(*(config.flatMap { listOf("-c", it) } + DiffParser.ARGUMENTS + "--cached").toTypedArray())
                if (name.contains(' ') && !name.contains('\t')) {
                    assertTrue(raw.toString(Charsets.UTF_8).contains("--- a/mod_$name\t\n"), "git appends a tab after a name with a space")
                }
                val files = DiffParser.parse(raw).associateBy { it.change }
                val what = "name #$i config $config"
                assertEquals(4, files.size, what)
                with(files.getValue(DiffChange.MODIFIED)) {
                    assertEquals(GitPath.of("mod_$name"), oldPath, what)
                    assertEquals(GitPath.of("mod_$name"), newPath, what)
                }
                with(files.getValue(DiffChange.DELETED)) {
                    assertEquals(GitPath.of("del_$name"), oldPath, what)
                    assertNull(newPath, what)
                }
                with(files.getValue(DiffChange.ADDED)) {
                    assertNull(oldPath, what)
                    assertEquals(GitPath.of("add_$name"), newPath, what)
                }
                with(files.getValue(DiffChange.RENAMED)) {
                    assertEquals(GitPath.of("ren_$name"), oldPath, what)
                    assertEquals(GitPath.of("ren2_$name"), newPath, what)
                }
            }
        }
    }

    @Test
    fun oldModeWithoutNewModeFails() {
        val repo = TestRepo.init(root)
        repo.write("run.sh", "echo\n")
        repo.commitAll("base")
        assertTrue(File(repo.dir, "run.sh").setExecutable(true))
        val text = repo.git(*DiffParser.ARGUMENTS.toTypedArray()).toString(Charsets.UTF_8)
        val cut = text.indexOf("new mode ")
        assertTrue(cut > 0)
        assertEquals(1, DiffParser.parse(bytes(text)).size)
        assertFailsWith<GitParseException> { DiffParser.parse(bytes(text.substring(0, cut))) }
        assertFailsWith<GitParseException> { DiffParser.parse(bytes("diff --git a/f b/f\nold mode 100644\n")) }
        assertFailsWith<GitParseException> { DiffParser.parse(bytes("diff --git a/f b/f\nnew mode 100755\n")) }
    }

    @Test
    fun onlyTheExactNoNewlineMarkerIsAccepted() {
        val head = "diff --git a/f b/f\nindex 1234567..89abcde 100644\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n"
        val good = head + "\\ No newline at end of file\n+y\n"
        assertTrue(DiffParser.parse(bytes(good)).single().hunks.single().lines[0].noNewlineAtEnd)
        val bad = listOf(
            head + "\\ Kein Zeilenumbruch am Dateiende.\n+y\n",
            head + "\\\n+y\n",
            head + "\\ No newline at end of file\n\\ No newline at end of file\n+y\n",
            head + "+y\n\\ No newline at end of file \n",
        )
        for (sample in bad) {
            assertFailsWith<GitParseException>(sample.replace("\n", "\\n")) { DiffParser.parse(bytes(sample)) }
        }
    }

    @Test
    fun truncatedBinaryPatchFails() {
        val repo = TestRepo.init(root)
        repo.writeBytes("image.bin", ByteArray(200) { (it * 7).toByte() }.also { it[0] = 0 })
        repo.commitAll("base")
        repo.writeBytes("image.bin", ByteArray(300) { (it * 13).toByte() }.also { it[0] = 0 })
        val raw = repo.git(*(DiffParser.ARGUMENTS + "--binary").toTypedArray())
        assertTrue(DiffParser.parse(raw).single().isBinary)
        val start = raw.toString(Charsets.ISO_8859_1).indexOf("GIT binary patch\n")
        assertTrue(start > 0)
        for (cut in start until raw.size) {
            if (raw[cut - 1] != '\n'.code.toByte()) continue
            assertFailsWith<GitParseException>("cut at $cut of ${raw.size}") { DiffParser.parse(raw.copyOf(cut)) }
        }
    }

    @Test
    fun fixedSamplesWithOmittedCounts() {
        val raw = "diff --git a/f b/f\nindex 1234567..89abcde 100644\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n+y\n" +
            "diff --git a/g b/g\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/g\n@@ -0,0 +1 @@\n+z\n"
        val (f, g) = DiffParser.parse(bytes(raw))
        assertEquals(listOf(1, 1, 1, 1), with(f.hunks.single()) { listOf(oldStart, oldCount, newStart, newCount) })
        assertEquals("1234567", f.oldHash)
        assertEquals("89abcde", f.newHash)
        assertEquals(DiffChange.ADDED, g.change)
        assertEquals(1, g.hunks.single().newCount)
        assertEquals(0, g.hunks.single().oldCount)
    }

    @Test
    fun dissimilarityAndTypeChangeHeadersAreAccepted() {
        val raw = "diff --git a/f b/f\ndissimilarity index 90%\nindex 1234567..89abcde 100644\n--- a/f\n+++ b/f\n@@ -1 +1 @@\n-x\n+y\n"
        assertEquals(DiffChange.MODIFIED, DiffParser.parse(bytes(raw)).single().change)
    }

    @Test
    fun malformedInputFailsWithAParseError() {
        val head = "diff --git a/f b/f\nindex 1234567..89abcde 100644\n--- a/f\n+++ b/f\n"
        val bad = listOf(
            "commit abc\n",
            "diff --cc f\nindex 1,2..3\n",
            head + "@@ -1,2 +1,2 @@\n-x\n+y\n",
            head + "@@ -1 +1 @@\n-x\n",
            head + "@@ -1 +1 @@\n-x\n+y",
            head + "@@ -1 +1 @@\n-x\n-y\n",
            head + "@@ -1 +1 @@\n*x\n+y\n",
            head + "@@ -a +1 @@\n-x\n+y\n",
            head + "@@ -1 +1\n-x\n+y\n",
            head + "@@@ -1 -1 +1 @@@\n",
            head + "@@ -1 +1 @@\n\\ No newline\n-x\n+y\n",
            head + "@@ -1 +1 @@\n-x\n+y\ntrailing junk\n",
            head + "@@ -99999999999 +1 @@\n-x\n+y\n",
            "diff --git a/f b/f\n--- a/f\n",
            "diff --git a/f b/f\n--- a/f\n@@ -1 +1 @@\n-x\n+y\n",
            "diff --git a/f b/f\nweird header\n",
            "diff --git a/f b/f\nold mode 1x0644\nnew mode 100755\n",
            "diff --git a/f b/f\nindex zz..yy 100644\n",
            "diff --git a/f b/f\nsimilarity index 101%\n",
            "diff --git \"a/unterminated b/f\n",
            "diff --git \"a/bad\\q\" b/f\n",
            "diff --git a/one b/two\n",
            "diff --git a/f b/f\n--- x/f\n+++ b/f\n",
            "diff --git a/f b/f\nrename from x\n",
            "diff --git a/f b/f\n",
        )
        for (sample in bad) {
            assertFailsWith<GitParseException>(sample.replace("\n", "\\n")) { DiffParser.parse(bytes(sample)) }
        }
    }

    @Test
    fun modelsHideContentInToString() {
        val line = DiffLine(DiffLineKind.ADDED, null, 1, bytes("secret"), false)
        assertFalse(line.toString().contains("secret"))
        val hunk = DiffHunk(1, 1, 1, 1, "secret section", listOf(line))
        assertFalse(hunk.toString().contains("secret"))
        val file = DiffFile(GitPath.of("secret"), GitPath.of("secret"), DiffChange.MODIFIED, null, null, null, null, null, false, listOf(hunk))
        assertFalse(file.toString().contains("secret"))
    }
}
