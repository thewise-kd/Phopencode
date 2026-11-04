package io.github.thewisekd.phopencode.core.ssh.sftp

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The one lexical path policy that reads and writes share. */
class SftpRootTest {
    private val root = SftpRoot.of("/srv/proje")

    private fun assertRejected(path: String, within: SftpRoot = root) {
        val failure = assertFailsWith<SshException.Sftp>("<${printable(path)}>") { within.resolve(path) }
        assertEquals(SftpFailure.PathRejected, failure.failure)
    }

    private fun printable(text: String) = text.map { if (it == '\u0000' || it.isSurrogate()) '?' else it }.joinToString("")

    @Test
    fun `a root is absolute and normalised`() {
        assertEquals("/srv/proje", SftpRoot.of("/srv/proje/").path)
        assertEquals("/srv/proje", SftpRoot.of("//srv//proje").path)
        assertEquals("/", SftpRoot.of("/").path)
    }

    @Test
    fun `a malformed root is rejected`() {
        listOf("", "srv/proje", "~", "/srv/../etc", "/srv/./proje", "/srv\u0000", "/srv/\uD800").forEach { bad ->
            val failure = assertFailsWith<SshException.Sftp>("root <${printable(bad)}>") { SftpRoot.of(bad) }
            assertEquals(SftpFailure.PathRejected, failure.failure)
        }
    }

    @Test
    fun `a relative path is resolved against the root`() {
        assertEquals("/srv/proje/a.txt", root.resolve("a.txt"))
        assertEquals("/srv/proje/klasör adı/çğıİöşü dosya.txt", root.resolve("klasör adı/çğıİöşü dosya.txt"))
    }

    @Test
    fun `the empty path and the root itself are the root`() {
        assertEquals("/srv/proje", root.resolve(""))
        assertEquals("/srv/proje", root.resolve("/srv/proje"))
        assertEquals("/srv/proje", root.resolve("/srv/proje/"))
    }

    @Test
    fun `an absolute path inside the root is kept`() {
        assertEquals("/srv/proje/a/b.txt", root.resolve("/srv/proje/a/b.txt"))
    }

    @Test
    fun `empty segments and a trailing slash are dropped`() {
        assertEquals("/srv/proje/a/b", root.resolve("a//b/"))
        assertEquals("/srv/proje/a", root.resolve("//srv//proje/a"))
    }

    @Test
    fun `dot segments and NUL are rejected wherever they are`() {
        listOf(
            "../x", "a/../b", "a/..", "..", "./x", "a/./b", "a/.", ".", "a/../../x", "a\u0000b",
            "/srv/proje/../proje/a", "/srv/proje/.", "/srv/proje/../x",
        ).forEach { assertRejected(it) }
    }

    @Test
    fun `absolute paths outside the root are rejected`() {
        listOf("/", "/srv", "/etc/passwd", "/srv/proje-sibling", "/srv/projeX/a").forEach { assertRejected(it) }
    }

    @Test
    fun `encoded or look-alike segments are plain names, not parents`() {
        assertEquals("/srv/proje/%2e%2e", root.resolve("%2e%2e"))
        assertEquals("/srv/proje/%2e%2e/x", root.resolve("%2e%2e/x"))
        assertEquals("/srv/proje/...", root.resolve("..."))
        assertEquals("/srv/proje/..a", root.resolve("..a"))
        assertEquals("/srv/proje/~", root.resolve("~"))
        assertEquals("/srv/proje/a\\..\\b", root.resolve("a\\..\\b"))
    }

    @Test
    fun `a lone surrogate is malformed and rejected, since UTF-8 on the wire would change the name`() {
        assertRejected("a\uD800b")
        assertRejected("\uDC00")
        assertRejected("a\uD800")
        assertEquals("/srv/proje/😀", root.resolve("😀"))
    }

    @Test
    fun `a root of slash gives every absolute path with the same segment rules`() {
        val whole = SftpRoot.of("/")
        assertEquals("/", whole.resolve(""))
        assertEquals("/etc/hosts", whole.resolve("/etc/hosts"))
        assertEquals("/etc", whole.resolve("etc"))
        assertRejected("/a/../b", whole)
    }

    @Test
    fun `containment compares whole segments`() {
        assertTrue(root.contains("/srv/proje"))
        assertTrue(root.contains("/srv/proje/a"))
        assertFalse(root.contains("/srv/projeother"))
        assertFalse(root.contains("/srv"))
        assertTrue(SftpRoot.of("/").contains("/anything"))
    }

    @Test
    fun `segments relative to the root`() {
        assertEquals(listOf("a", "b"), root.segments("/srv/proje/a/b"))
        assertEquals(emptyList(), root.segments("/srv/proje"))
    }

    @Test
    fun `the failure message does not quote the path`() {
        val failure = assertFailsWith<SshException.Sftp> { root.resolve("/gizli/yer") }
        assertFalse(failure.message.orEmpty().contains("gizli"))
        assertNull(failure.cause)
    }
}
