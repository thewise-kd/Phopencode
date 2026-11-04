package io.github.thewisekd.phopencode.core.ssh.sftp.read

import io.github.thewisekd.phopencode.core.ssh.api.SftpFailure
import io.github.thewisekd.phopencode.core.ssh.api.SshException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SftpReadPathsTest {
    private val paths = SftpReadPaths("/srv/proje")

    private fun assertRejected(path: String, within: SftpReadPaths = paths) {
        val failure = assertFailsWith<SshException.Sftp>("<${path.replace('\u0000', '0')}>") { within.resolve(path) }
        assertEquals(SftpFailure.PathRejected, failure.failure)
    }

    @Test
    fun `a relative path is resolved against the root`() {
        assertEquals("/srv/proje/a.txt", paths.resolve("a.txt"))
        assertEquals("/srv/proje/klasör adı/çğıİöşü dosya.txt", paths.resolve("klasör adı/çğıİöşü dosya.txt"))
    }

    @Test
    fun `the empty path and the root itself are the root`() {
        assertEquals("/srv/proje", paths.resolve(""))
        assertEquals("/srv/proje", paths.resolve("/srv/proje"))
        assertEquals("/srv/proje", paths.resolve("/srv/proje/"))
    }

    @Test
    fun `an absolute path inside the root is kept`() {
        assertEquals("/srv/proje/a/b.txt", paths.resolve("/srv/proje/a/b.txt"))
    }

    @Test
    fun `empty segments and a trailing slash are dropped`() {
        assertEquals("/srv/proje/a/b", paths.resolve("a//b/"))
        assertEquals("/srv/proje/a", paths.resolve("//srv//proje/a"))
    }

    @Test
    fun `dot segments and NUL are rejected wherever they are`() {
        listOf("../x", "a/../b", "a/..", "..", "./x", "a/./b", ".", "a\u0000b", "/srv/proje/../proje/a", "/srv/proje/.")
            .forEach { assertRejected(it) }
    }

    @Test
    fun `absolute paths outside the root are rejected`() {
        assertRejected("/")
        assertRejected("/srv")
        assertRejected("/etc/passwd")
        assertRejected("/srv/proje-sibling")
        assertRejected("/srv/projeX/a")
    }

    @Test
    fun `encoded or look-alike segments are plain names, not parents`() {
        assertEquals("/srv/proje/%2e%2e", paths.resolve("%2e%2e"))
        assertEquals("/srv/proje/...", paths.resolve("..."))
        assertEquals("/srv/proje/..a", paths.resolve("..a"))
        assertEquals("/srv/proje/~", paths.resolve("~"))
        assertEquals("/srv/proje/a\\..\\b", paths.resolve("a\\..\\b"))
    }

    @Test
    fun `a lone surrogate is malformed and rejected`() {
        assertRejected("a\uD800b")
        assertRejected("\uDC00")
    }

    @Test
    fun `a root of slash gives every absolute path with the same segment rules`() {
        val whole = SftpReadPaths("/")
        assertEquals("/", whole.root)
        assertEquals("/", whole.resolve(""))
        assertEquals("/etc/hosts", whole.resolve("/etc/hosts"))
        assertEquals("/etc", whole.resolve("etc"))
        assertRejected("/a/../b", whole)
    }

    @Test
    fun `the root is normalised`() {
        assertEquals("/srv/proje", SftpReadPaths("/srv/proje/").root)
        assertEquals("/srv/proje", SftpReadPaths("//srv//proje").root)
    }

    @Test
    fun `a malformed root is rejected`() {
        listOf("", "srv/proje", "~", "/srv/../etc", "/srv/./proje", "/srv\u0000", "/srv/\uD800")
            .forEach { root ->
                val failure = assertFailsWith<SshException.Sftp>("root <${root.replace('\u0000', '0')}>") { SftpReadPaths(root) }
                assertEquals(SftpFailure.PathRejected, failure.failure)
            }
    }

    @Test
    fun `the failure message does not quote the path`() {
        val failure = assertFailsWith<SshException.Sftp> { paths.resolve("/gizli/yer") }
        assertEquals(false, failure.message.orEmpty().contains("gizli"))
        assertEquals(null, failure.cause)
    }
}
