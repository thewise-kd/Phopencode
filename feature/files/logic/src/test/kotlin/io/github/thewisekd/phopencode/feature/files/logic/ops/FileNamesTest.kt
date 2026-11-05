package io.github.thewisekd.phopencode.feature.files.logic.ops

import io.github.thewisekd.phopencode.feature.files.logic.contract.NameProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileNamesTest {
    @Test
    fun `empty and blank names are refused`() {
        assertEquals(NameProblem.Blank, FileNames.problem(""))
        assertEquals(NameProblem.Blank, FileNames.problem("   "))
        assertEquals(NameProblem.Blank, FileNames.problem("\t \n"))
    }

    @Test
    fun `dot and dot-dot are refused, also with spaces around`() {
        assertEquals(NameProblem.Reserved, FileNames.problem("."))
        assertEquals(NameProblem.Reserved, FileNames.problem(".."))
        assertEquals(NameProblem.Reserved, FileNames.problem(" .. "))
    }

    @Test
    fun `names that only start with dots are fine`() {
        assertNull(FileNames.problem("..."))
        assertNull(FileNames.problem(".gitignore"))
        assertNull(FileNames.problem("..rest"))
    }

    @Test
    fun `a slash is refused anywhere`() {
        assertEquals(NameProblem.Separator, FileNames.problem("a/b"))
        assertEquals(NameProblem.Separator, FileNames.problem("/"))
        assertEquals(NameProblem.Separator, FileNames.problem("../etc"))
        assertEquals(NameProblem.Separator, FileNames.problem("name/"))
    }

    @Test
    fun `NUL and other control characters are refused`() {
        assertEquals(NameProblem.ControlCharacter, FileNames.problem("a\u0000b"))
        assertEquals(NameProblem.ControlCharacter, FileNames.problem("\u0000"))
        assertEquals(NameProblem.ControlCharacter, FileNames.problem("a\nb"))
        assertEquals(NameProblem.ControlCharacter, FileNames.problem("a\u007fb"))
        assertEquals(NameProblem.ControlCharacter, FileNames.problem("a\u009bb"))
    }

    @Test
    fun `names are limited to 255 bytes of UTF-8`() {
        assertEquals(255, FileNames.MAX_NAME_BYTES)
        assertNull(FileNames.problem("a".repeat(255)))
        assertEquals(NameProblem.TooLong, FileNames.problem("a".repeat(256)))
        // "ç" is two bytes: 127 of them fit, 128 do not.
        assertNull(FileNames.problem("ç".repeat(127)))
        assertEquals(NameProblem.TooLong, FileNames.problem("ç".repeat(128)))
    }

    @Test
    fun `the limit counts the trimmed name`() {
        assertNull(FileNames.problem("  " + "a".repeat(255) + "  "))
    }

    @Test
    fun `ordinary names pass`() {
        assertNull(FileNames.problem("yeni klasör"))
        assertNull(FileNames.problem("main.kt"))
        assertNull(FileNames.problem("  padded  "))
        assertNull(FileNames.problem("a\\b"))
    }
}
