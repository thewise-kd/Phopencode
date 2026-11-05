package io.github.thewisekd.phopencode.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemotePathTest {
    private fun rejected(root: String, path: String) {
        val failure = assertFailsWith<FileException> { RemotePath.resolve(root, path) }
        assertEquals(FileError.PathRejected, failure.error)
    }

    @Test
    fun `relative paths resolve under the root and absolute ones stay`() {
        assertEquals("/srv/p/a/b", RemotePath.resolve("/srv/p", "a/b"))
        assertEquals("/srv/p/a/b", RemotePath.resolve("/srv/p/", "/srv/p//a/b/"))
        assertEquals("/srv/p", RemotePath.resolve("/srv/p", ""))
        assertEquals("/x", RemotePath.resolve("/", "x"))
        assertEquals("/", RemotePath.resolve("/", "/"))
    }

    @Test
    fun `hostile names are plain segments`() {
        listOf("\$(touch x).txt", "-rf", "a b.txt", "it's \"q\".txt", "new\nline", "çğıöşü İ.txt", "a:1:b", "%2e%2e", "a\\..\\b")
            .forEach { name -> assertEquals("/r/$name", RemotePath.resolve("/r", name), name) }
    }

    @Test
    fun `dot segments, NUL, lone surrogates and escapes are rejected`() {
        rejected("/r", "..")
        rejected("/r", "a/../b")
        rejected("/r", "./a")
        rejected("/r", "/r/a/..")
        rejected("/r", "/rx/a")
        rejected("/r", "/etc/passwd")
        rejected("/r", "a\u0000b")
        rejected("/r", "a\uD800b")
        rejected("/r", "a\uDC00")
        rejected("relative", "a")
    }

    @Test
    fun `within compares segments, not prefixes`() {
        assertTrue(RemotePath.isWithin("/a/b", "/a"))
        assertTrue(RemotePath.isWithin("/a", "/a"))
        assertTrue(RemotePath.isWithin("/a", "/"))
        assertFalse(RemotePath.isWithin("/ab", "/a"))
        assertFalse(RemotePath.isWithin("/a", "/a/b"))
    }

    @Test
    fun `parent and name`() {
        assertEquals("/a", RemotePath.parentOf("/a/b"))
        assertEquals("/", RemotePath.parentOf("/a"))
        assertEquals("b", RemotePath.nameOf("/a/b"))
        assertEquals("/a/b", RemotePath.child("/a", "b"))
        assertEquals("/b", RemotePath.child("/", "b"))
    }
}
