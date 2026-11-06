package io.github.thewisekd.phopencode.core.git.parse

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GitPathTest {
    @Test
    fun equalityIsByBytesAndCopiesAreDefensive() {
        val raw = bytes("bad", 0xFF)
        val path = GitPath(raw)
        raw[0] = 'x'.code.toByte()
        assertEquals(GitPath(bytes("bad", 0xFF)), path)
        assertEquals(GitPath(bytes("bad", 0xFF)).hashCode(), path.hashCode())
        path.toByteArray()[0] = 'y'.code.toByte()
        assertContentEquals(bytes("bad", 0xFF), path.toByteArray())
        assertNotEquals(GitPath(bytes("bad", 0xFE)), path)
        assertEquals(GitPath(bytes("bad", 0xFE)).text, path.text)
        assertFalse(path.isValidUtf8)
        assertEquals(4, path.size)
    }

    @Test
    fun utf8TextRoundTrips() {
        val path = GitPath.of("çğıöşü İ/\$(x) -rf")
        assertTrue(path.isValidUtf8)
        assertEquals("çğıöşü İ/\$(x) -rf", path.text)
    }

    @Test
    fun toStringHidesThePath() {
        assertEquals("GitPath(6 bytes)", GitPath.of("secret").toString())
    }

    @Test
    fun parseErrorCarriesOffsetAndReason() {
        val e = GitParseException("bad thing", 7)
        assertEquals(7, e.offset)
        assertEquals("bad thing at byte 7", e.message)
    }

    /** minSdk is 26: these compile against Java 17 but are missing on old Android. */
    @Test
    fun mainSourcesAvoidApisMissingOnOldAndroid() {
        val dir = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it, it.resolve("core/git")) }
            .map { it.resolve("src/main/kotlin/io/github/thewisekd/phopencode/core/git/parse") }
            .first { it.isDirectory }
        val forbidden = Regex("""\bHexFormat\b|\.readAllBytes\(|\bString\.repeat\b|\.transferTo\(""")
        val hits = dir.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { forbidden.containsMatchIn(it) }.map { "${f.name}: ${it.trim()}" }
        }.toList()
        assertTrue(hits.isEmpty(), hits.joinToString("\n"))
    }
}
