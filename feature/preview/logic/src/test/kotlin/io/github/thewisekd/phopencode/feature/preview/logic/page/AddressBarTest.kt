package io.github.thewisekd.phopencode.feature.preview.logic.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddressBarTest {
    private val origin = PreviewOrigin(41234)

    private fun normalize(text: String) = AddressBar.normalize(text, origin)

    @Test
    fun `an empty address is the root`() {
        assertEquals("/", normalize(""))
        assertEquals("/", normalize("   "))
        assertEquals("/", normalize("/"))
    }

    @Test
    fun `a path keeps its query and fragment`() {
        assertEquals("/app/index.html?x=1&y=2#top", normalize("/app/index.html?x=1&y=2#top"))
        assertEquals("/a?b=/c?d", normalize("/a?b=/c?d"))
    }

    @Test
    fun `a missing leading slash is added`() {
        assertEquals("/about", normalize("about"))
        assertEquals("/?q=1", normalize("?q=1"))
        assertEquals("/#top", normalize("#top"))
        assertEquals("/example.com/x", normalize("example.com/x"))
    }

    @Test
    fun `surrounding spaces are dropped`() {
        assertEquals("/a", normalize("  /a \t"))
    }

    @Test
    fun `dot segments are resolved and never climb above the root`() {
        assertEquals("/a/c", normalize("/a/b/../c"))
        assertEquals("/a/", normalize("/a/./"))
        assertEquals("/x", normalize("/../../x"))
        assertEquals("/", normalize("/a/.."))
        assertEquals("/x", normalize("/%2e%2E/x"))
        assertEquals("/a/", normalize("/a/b/.%2e"))
    }

    @Test
    fun `repeated slashes and backslashes collapse so the address cannot leave the origin`() {
        assertEquals("/evil.example/x", normalize("//evil.example/x"))
        assertEquals("/evil.example/x", normalize("\\\\evil.example/x"))
        assertEquals("/evil.example/x", normalize("/\\evil.example/x"))
        assertEquals("/a/b", normalize("/a///b"))
    }

    @Test
    fun `characters a URL cannot hold are percent-encoded once`() {
        assertEquals("/a%20b", normalize("/a b"))
        assertEquals("/a%20b", normalize("/a%20b"))
        assertEquals("/%25zz", normalize("/%zz"))
        assertEquals("/%C3%A7ay?q=%C4%B1#%22x%22", normalize("/çay?q=ı#\"x\""))
        assertEquals("/%F0%9F%98%80", normalize("/😀"))
        assertEquals("/a%3Cb%3E", normalize("/a<b>"))
        assertEquals("/a?x=%23#b%23c", normalize("/a?x=%23#b#c"))
    }

    @Test
    fun `an empty query or fragment is dropped`() {
        assertEquals("/a", normalize("/a?"))
        assertEquals("/a", normalize("/a#"))
        assertEquals("/a", normalize("/a?#"))
    }

    @Test
    fun `the bridge secret never enters the address`() {
        assertEquals("/a", normalize("/a?phopencode_secret=abc"))
        assertEquals("/a?x=1", normalize("/a?phopencode_secret=abc&x=1"))
    }

    @Test
    fun `a full address on the preview origin becomes its path`() {
        assertEquals("/", normalize("http://127.0.0.1:41234"))
        assertEquals("/a/b?x=1#c", normalize("http://127.0.0.1:41234/a/./b?x=1#c"))
        assertEquals("/a", normalize("HTTP://127.0.0.1:41234/a"))
    }

    @Test
    fun `an address with a scheme off the origin is refused`() {
        listOf(
            "https://example.com/",
            "http://example.com/",
            "http:\\\\example.com",
            "https://127.0.0.1:41234/",
            "http://127.0.0.1:41235/",
            "http://localhost:41234/",
            "http://user@127.0.0.1:41234/",
            "localhost:3000/x",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "content://x/y",
            "intent://x#Intent;end",
            "data:text/html,x",
            "about:blank",
        ).forEach { assertNull(normalize(it), it) }
    }

    @Test
    fun `without an origin a full address is refused but a path is normalized`() {
        assertNull(AddressBar.normalize("http://127.0.0.1:41234/a", null))
        assertEquals("/a", AddressBar.normalize("a", null))
    }

    @Test
    fun `control characters are refused`() {
        listOf("/a\nb", "/a\rb", "/a\u0000", "/a\u007f", "/a\tb").forEach { assertNull(normalize(it), it) }
    }

    @Test
    fun `an overlong address is refused`() {
        assertEquals(AddressBar.MAX_LENGTH, normalize("/" + "a".repeat(AddressBar.MAX_LENGTH - 1))?.length)
        assertNull(normalize("/" + "a".repeat(AddressBar.MAX_LENGTH)))
        assertNull(normalize("/a" + " ".repeat(AddressBar.MAX_LENGTH / 2) + "b"))
    }
}
