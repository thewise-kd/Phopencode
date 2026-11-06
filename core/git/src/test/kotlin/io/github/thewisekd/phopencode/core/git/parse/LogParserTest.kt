package io.github.thewisekd.phopencode.core.git.parse

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogParserTest {
    @TempDir
    lateinit var root: File

    private fun TestRepo.log(vararg extra: String): List<GitCommit> =
        LogParser.parse(git(*(LogParser.ARGUMENTS + extra).toTypedArray()))

    @Test
    fun emptyOutputIsNoCommits() {
        assertEquals(emptyList(), LogParser.parse(ByteArray(0)))
    }

    @Test
    fun parsesSubjectsBodiesAndIdentities() {
        val repo = TestRepo.init(root)
        repo.commitAll("root commit")
        val rootHash = repo.head()
        repo.write("f", "1\n")
        repo.commitAll("Türkçe başlık \$(rm -rf)\n\nGövde satırı 1\n\nparagraph two\n  indented\n")
        repo.commitAll("subject over\ntwo lines")

        val commits = repo.log()
        assertEquals(3, commits.size)
        val (last, middle, first) = commits
        assertEquals(rootHash, first.hash)
        assertEquals(emptyList(), first.parents)
        assertEquals("root commit", first.subject)
        assertEquals("", first.body)
        assertEquals(listOf(rootHash), middle.parents)
        assertEquals("Türkçe başlık \$(rm -rf)", middle.subject)
        assertEquals("Gövde satırı 1\n\nparagraph two\n  indented", middle.body)
        assertEquals("subject over two lines", last.subject)
        assertEquals("", last.body)
        assertEquals("Ayşe Yılmaz", middle.authorName)
        assertEquals("ayse@example.invalid", middle.authorEmail)
        assertEquals("Committer", middle.committerName)
        assertEquals("committer@example.invalid", middle.committerEmail)
        assertTrue(middle.authorTime > first.authorTime)
        assertEquals(middle.authorTime, middle.commitTime)
        assertTrue(middle.authorTime in 1_700_000_000L..1_700_100_000L)
        assertFalse(middle.isMerge)
    }

    @Test
    fun mergeCommitsHaveTwoParents() {
        val repo = TestRepo.init(root)
        repo.commitAll("base")
        repo.git("checkout", "-q", "-b", "side")
        repo.write("side", "s\n")
        repo.commitAll("side")
        val side = repo.head()
        repo.git("checkout", "-q", "main")
        repo.write("main", "m\n")
        repo.commitAll("main")
        val main = repo.head()
        repo.git("merge", "-q", "--no-ff", "-m", "merge side", "side")

        val merge = repo.log("--max-count=1").single()
        assertTrue(merge.isMerge)
        assertEquals(listOf(main, side), merge.parents)
        assertEquals("merge side", merge.subject)
    }

    @Test
    fun paginationWithSkipAndMaxCount() {
        val repo = TestRepo.init(root)
        repeat(7) { repo.commitAll("commit $it") }
        val all = repo.log()
        val pages = listOf(repo.log("--max-count=3"), repo.log("--max-count=3", "--skip=3"), repo.log("--max-count=3", "--skip=6"))
        assertEquals(listOf(3, 3, 1), pages.map { it.size })
        assertEquals(all, pages.flatten())
        assertEquals(emptyList(), repo.log("--max-count=3", "--skip=7"))
    }

    @Test
    fun nonUtf8LogOutputEncodingIsOverridden() {
        val repo = TestRepo.init(root)
        repo.commitAll("Türkçe başlık: şğıİ\n\nGövde: çöü")
        repo.git("config", "i18n.logOutputEncoding", "ISO-8859-9")
        val reencoded = repo.git("log", "--no-color", "-z", LogParser.FORMAT)
        assertFalse(isValidUtf8(reencoded), "the setting must take effect for this test to mean anything")

        val commit = repo.log().single()
        assertEquals("Türkçe başlık: şğıİ", commit.subject)
        assertEquals("Gövde: çöü", commit.body)
        assertEquals("Ayşe Yılmaz", commit.authorName)
    }

    @Test
    fun realOutputTerminatesTheLastCommit() {
        val repo = TestRepo.init(root)
        repo.commitAll("one")
        val raw = repo.git(*LogParser.ARGUMENTS.toTypedArray())
        assertEquals(0, raw.last().toInt())
        assertFailsWith<GitParseException> { LogParser.parse(raw.copyOf(raw.size - 1)) }
    }

    @Test
    fun fixedSampleWithSha256AndOctopusParents() {
        val h = "b".repeat(64)
        val p1 = "c".repeat(64)
        val p2 = "d".repeat(64)
        val p3 = "e".repeat(64)
        val raw = "$h\u0000$p1 $p2 $p3\u0000n\u0000e\u00001\u0000cn\u0000ce\u0000-5\u0000s\u0000body\n\n\u0000"
        val commit = LogParser.parse(bytes(raw)).single()
        assertEquals(listOf(p1, p2, p3), commit.parents)
        assertEquals(-5, commit.commitTime)
        assertEquals("body", commit.body)
    }

    @Test
    fun invalidUtf8InMessagesIsReplacedNotFatal() {
        val h = "a".repeat(40)
        val raw = bytes("$h\u0000\u0000n\u0000e\u00001\u0000cn\u0000ce\u00002\u0000bad ") + byteArrayOf(0xFF.toByte()) + bytes("\u0000\u0000")
        assertEquals("bad �", LogParser.parse(raw).single().subject)
    }

    @Test
    fun malformedInputFailsWithAParseError() {
        val h = "a".repeat(40)
        val good = "$h\u0000\u0000n\u0000e\u00001\u0000cn\u0000ce\u00002\u0000s\u0000b\u0000"
        val bad = listOf(
            "$h\u0000\u0000n\u0000e\u00001\u0000cn\u0000ce\u00002\u0000s\u0000",
            "$h\u0000\u0000n\u0000e\u00001",
            "nothash\u0000\u0000n\u0000e\u00001\u0000cn\u0000ce\u00002\u0000s\u0000b\u0000",
            "$h\u0000xyz\u0000n\u0000e\u00001\u0000cn\u0000ce\u00002\u0000s\u0000b\u0000",
            "$h\u0000$h  $h\u0000n\u0000e\u00001\u0000cn\u0000ce\u00002\u0000s\u0000b\u0000",
            "$h\u0000\u0000n\u0000e\u0000x\u0000cn\u0000ce\u00002\u0000s\u0000b\u0000",
            "$h\u0000\u0000n\u0000e\u00001\u0000cn\u0000ce\u0000\u0000s\u0000b\u0000",
            "$h\u0000\u0000n\u0000e\u00001\u0000cn\u0000ce\u000099999999999999999999\u0000s\u0000b\u0000",
            good + "\n",
            good + h,
        )
        for (sample in bad) {
            assertFailsWith<GitParseException>(sample.replace("\u0000", "\\0")) { LogParser.parse(bytes(sample)) }
        }
        assertEquals(1, LogParser.parse(bytes(good)).size)
    }

    @Test
    fun toStringHidesIdentitiesAndMessage() {
        val c = GitCommit("a".repeat(40), emptyList(), "secret", "secret@x", 1, "secret", "secret@x", 2, "secret", "secret")
        assertFalse(c.toString().contains("secret"))
    }
}
