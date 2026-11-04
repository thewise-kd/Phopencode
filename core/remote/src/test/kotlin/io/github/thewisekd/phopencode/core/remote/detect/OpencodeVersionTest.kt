package io.github.thewisekd.phopencode.core.remote.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpencodeVersionTest {
    @Test
    fun `reads a plain version`() {
        assertEquals(OpencodeVersion(2, 2, 5, null), OpencodeVersion.find("2.2.5\n"))
    }

    @Test
    fun `reads a version after the program name and a v`() {
        assertEquals(OpencodeVersion(2, 2, 5, null), OpencodeVersion.find("opencode v2.2.5"))
        assertEquals(OpencodeVersion(1, 19, 5, null), OpencodeVersion.find("opencode version 1.19.5 (linux-arm64)"))
    }

    @Test
    fun `reads a pre-release and drops build metadata`() {
        val version = OpencodeVersion.find("2.3.0-beta.1+abc.7")
        assertEquals(OpencodeVersion(2, 3, 0, "beta.1"), version)
        assertEquals("2.3.0", version?.numbers)
    }

    @Test
    fun `reads major and minor alone`() {
        assertEquals(OpencodeVersion(2, 2, null, null), OpencodeVersion.find("2.2"))
    }

    @Test
    fun `skips colour codes and lines of noise before the version`() {
        val output = "\u001B[33mwarning: config not found\u001B[0m\n\u001B[1m2.2.5\u001B[0m\n"
        assertEquals(OpencodeVersion(2, 2, 5, null), OpencodeVersion.find(output))
    }

    @Test
    fun `ignores a version that ends a sentence with a dot`() {
        assertEquals(OpencodeVersion(2, 2, 5, null), OpencodeVersion.find("This is opencode 2.2.5."))
    }

    @Test
    fun `refuses text that is not a version`() {
        assertNull(OpencodeVersion.find(""))
        assertNull(OpencodeVersion.find("local"))
        assertNull(OpencodeVersion.find("dev build"))
        assertNull(OpencodeVersion.find("10.0.0.1"))
        assertNull(OpencodeVersion.find("2"))
        assertNull(OpencodeVersion.find("1234567890.1.0"))
        assertNull(OpencodeVersion.find("2.2.5-" + "a".repeat(65)))
    }

    @Test
    fun `does not read past a bounded prefix of the output`() {
        assertNull(OpencodeVersion.find("x ".repeat(10_000) + "2.2.5"))
    }

    @Test
    fun `compares by numbers, a missing patch counting as zero`() {
        assertTrue(OpencodeVersion(2, 10, 0, null) > OpencodeVersion(2, 9, 9, null))
        assertEquals(0, OpencodeVersion(2, 2, null, null).compareTo(OpencodeVersion(2, 2, 0, "rc.1")))
    }

    @Test
    fun `knows the app's minimum server version`() {
        assertTrue(OpencodeVersion(2, 2, 5, null).meetsMinimum)
        assertTrue(OpencodeVersion(3, 0, 0, null).meetsMinimum)
        assertFalse(OpencodeVersion(1, 19, 5, null).meetsMinimum)
    }

    @Test
    fun `prints only the numbers and the pre-release`() {
        assertEquals("2.3.0-beta.1", OpencodeVersion(2, 3, 0, "beta.1").toString())
    }
}
