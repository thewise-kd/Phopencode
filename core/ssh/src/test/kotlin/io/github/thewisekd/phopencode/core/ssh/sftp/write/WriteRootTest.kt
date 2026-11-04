package io.github.thewisekd.phopencode.core.ssh.sftp.write

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WriteRootTest {
    private val root = WriteRoot.of("/srv/work")

    private fun assertRejected(block: () -> Unit) {
        val error = assertFailsWith<SshException.Sftp> { block() }
        assertEquals(SftpFailure.PathRejected, error.failure)
    }

    @Test
    fun `a root is absolute and normalised`() {
        assertEquals("/srv/work", WriteRoot.of("/srv/work/").path)
        assertEquals("/srv/work", WriteRoot.of("//srv//work").path)
        assertEquals("/", WriteRoot.of("/").path)
    }

    @Test
    fun `a malformed root is rejected`() {
        for (bad in listOf("", "srv", "/srv/../etc", "/srv/./work", "/srv/\u0000")) {
            assertRejected { WriteRoot.of(bad) }
        }
    }

    @Test
    fun `relative paths are joined to the root`() {
        assertEquals("/srv/work/a.txt", root.resolve("a.txt"))
        assertEquals("/srv/work/a/b c/ç.txt", root.resolve("a/b c/ç.txt"))
        assertEquals("/srv/work/a/b", root.resolve("a//b/"))
        assertEquals("/srv/work", root.resolve(""))
    }

    @Test
    fun `absolute paths inside the root are accepted`() {
        assertEquals("/srv/work/a", root.resolve("/srv/work/a"))
        assertEquals("/srv/work", root.resolve("/srv/work/"))
    }

    @Test
    fun `traversal and escapes are rejected`() {
        val bad = listOf(
            "..", "../x", "a/../../x", "a/..", "./a", "a/./b", "a/.",
            "/etc/passwd", "/srv/workother/x", "/srv", "/", "/srv/work/../x",
            "a\u0000b",
        )
        for (path in bad) assertRejected { root.resolve(path) }
    }

    @Test
    fun `encoded dots and backslashes are plain names`() {
        assertEquals("/srv/work/%2e%2e/x", root.resolve("%2e%2e/x"))
        assertEquals("/srv/work/..\\x", root.resolve("..\\x"))
        assertEquals("/srv/work/...", root.resolve("..."))
    }

    @Test
    fun `the whole server is a root with the same segment rules`() {
        val all = WriteRoot.of("/")
        assertEquals("/etc/x", all.resolve("/etc/x"))
        assertEquals("/a", all.resolve("a"))
        assertEquals("/", all.resolve(""))
        assertRejected { all.resolve("/a/../b") }
    }

    @Test
    fun `containment compares whole segments`() {
        assertTrue(root.contains("/srv/work"))
        assertTrue(root.contains("/srv/work/a"))
        assertFalse(root.contains("/srv/workother"))
        assertFalse(root.contains("/srv"))
        assertTrue(WriteRoot.of("/").contains("/anything"))
    }

    @Test
    fun `segments relative to the root`() {
        assertEquals(listOf("a", "b"), root.segments("/srv/work/a/b"))
        assertEquals(emptyList(), root.segments("/srv/work"))
    }
}
