package io.github.thewisekd.phopencode.feature.git.logic.log

import io.github.thewisekd.phopencode.core.git.parse.GitParseException
import io.github.thewisekd.phopencode.feature.git.logic.contract.CommitFileChange
import io.github.thewisekd.phopencode.feature.git.logic.contract.GitChangeKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommitFilesTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `numstat reads counts, binary files and renames`() {
        val parsed = CommitFiles.parseNumStat(bytes("3\t1\tsrc/a.kt\u0000-\t-\tlogo.png\u00000\t0\t\u0000eski ad.txt\u0000yeni ad.txt\u0000"))

        assertEquals(3, parsed.size)
        assertEquals("src/a.kt", parsed[0].path)
        assertNull(parsed[0].oldPath)
        assertEquals(3, parsed[0].additions)
        assertEquals(1, parsed[0].deletions)
        assertEquals("logo.png", parsed[1].path)
        assertNull(parsed[1].additions)
        assertNull(parsed[1].deletions)
        assertEquals("yeni ad.txt", parsed[2].path)
        assertEquals("eski ad.txt", parsed[2].oldPath)
        assertEquals(0, parsed[2].additions)
    }

    @Test
    fun `numstat keeps tabs, newlines and a leading dash in paths`() {
        val parsed = CommitFiles.parseNumStat(bytes("1\t0\ta\tb\nc\u00002\t2\t-rf\u0000"))

        assertEquals(listOf("a\tb\nc", "-rf"), parsed.map { it.path })
    }

    @Test
    fun `empty output is no files`() {
        assertEquals(emptyList(), CommitFiles.parseNumStat(ByteArray(0)))
        assertEquals(emptyList(), CommitFiles.parseNameStatus(ByteArray(0)))
    }

    @Test
    fun `malformed numstat fails without reading past the end`() {
        val bad = listOf(
            "1\t0\tpath",
            "1\t0\t\u0000old\u0000new",
            "1\t0\t\u0000old\u0000",
            "1\t0\u0000",
            "1\u0000",
            "x\t0\tpath\u0000",
            "-\t3\tpath\u0000",
            "1\t-\tpath\u0000",
            "+1\t0\tpath\u0000",
            "99999999999\t0\tpath\u0000",
            "\t0\tpath\u0000",
            "1\t0\t\u0000\u0000new\u0000",
            "1\t0\t\u0000old\u0000\u0000",
            "\n1\t0\tpath\u0000",
        )
        for (text in bad) assertFailsWith<GitParseException>(text.replace("\u0000", "\\0")) { CommitFiles.parseNumStat(bytes(text)) }
    }

    @Test
    fun `name status reads every kind`() {
        val parsed = CommitFiles.parseNameStatus(
            bytes("A\u0000yeni.txt\u0000M\u0000a\u0000D\u0000b\u0000T\u0000link\u0000R087\u0000eski\u0000yeni\u0000C100\u0000kaynak\u0000kopya\u0000"),
        )

        assertEquals(
            listOf(GitChangeKind.Added, GitChangeKind.Modified, GitChangeKind.Deleted, GitChangeKind.TypeChanged, GitChangeKind.Renamed, GitChangeKind.Copied),
            parsed.map { it.kind },
        )
        assertEquals(listOf("yeni.txt", "a", "b", "link", "yeni", "kopya"), parsed.map { it.path })
        assertEquals(listOf(null, null, null, null, "eski", "kaynak"), parsed.map { it.oldPath })
    }

    @Test
    fun `malformed name status fails`() {
        val bad = listOf(
            "M\u0000path",
            "M\u0000",
            "M",
            "Q\u0000path\u0000",
            "MM\u0000path\u0000",
            "R\u0000old\u0000new\u0000",
            "R1000\u0000old\u0000new\u0000",
            "R101\u0000old\u0000new\u0000",
            "Rxy\u0000old\u0000new\u0000",
            "R100\u0000old\u0000",
            "M100\u0000path\u0000",
            "M\u0000\u0000",
            "\u0000path\u0000",
            "X\u0000path\u0000",
        )
        for (text in bad) assertFailsWith<GitParseException>(text.replace("\u0000", "\\0")) { CommitFiles.parseNameStatus(bytes(text)) }
    }

    @Test
    fun `join pairs kinds with counts`() {
        val numStat = CommitFiles.parseNumStat(bytes("2\t0\tyeni.txt\u0000-\t-\tbin\u00001\t1\t\u0000eski\u0000yeni\u0000"))
        val nameStatus = CommitFiles.parseNameStatus(bytes("A\u0000yeni.txt\u0000M\u0000bin\u0000R090\u0000eski\u0000yeni\u0000"))

        assertEquals(
            listOf(
                CommitFileChange("yeni.txt", GitChangeKind.Added, additions = 2, deletions = 0),
                CommitFileChange("bin", GitChangeKind.Modified),
                CommitFileChange("yeni", GitChangeKind.Renamed, oldPath = "eski", additions = 1, deletions = 1),
            ),
            CommitFiles.join(numStat, nameStatus),
        )
        assertTrue(CommitFiles.join(numStat, nameStatus)[1].isBinary)
    }

    @Test
    fun `join refuses lists that do not match`() {
        val numStat = CommitFiles.parseNumStat(bytes("2\t0\ta\u0000"))

        assertFailsWith<GitParseException> { CommitFiles.join(numStat, emptyList()) }
        assertFailsWith<GitParseException> { CommitFiles.join(numStat, CommitFiles.parseNameStatus(bytes("A\u0000b\u0000"))) }
        assertFailsWith<GitParseException> {
            CommitFiles.join(numStat, CommitFiles.parseNameStatus(bytes("R100\u0000x\u0000a\u0000")))
        }
    }
}
