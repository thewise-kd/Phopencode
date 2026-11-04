package io.github.thewisekd.phopencode.core.markdown

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which link targets may open. Robolectric runs it only so the Pi's `[26]` test filter picks it up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class LinkPolicyTest {
    @Test
    fun `http and https links with a host are openable as they are`() {
        assertEquals("https://example.com/a?b=c#d", LinkPolicy.openableTarget("https://example.com/a?b=c#d"))
        assertEquals("http://example.org", LinkPolicy.openableTarget("http://example.org"))
        assertEquals("https://example.com:8443/x", LinkPolicy.openableTarget("https://example.com:8443/x"))
    }

    @Test
    fun `the scheme is lower-cased so a browser matches it`() {
        assertEquals("https://Example.com/Path", LinkPolicy.openableTarget("HTTPS://Example.com/Path"))
    }

    @Test
    fun `surrounding whitespace is dropped`() {
        assertEquals("https://example.com", LinkPolicy.openableTarget("  https://example.com \n"))
    }

    @Test
    fun `user info stays in the target so the dialog shows it`() {
        assertEquals(
            "https://example.com@example.net/",
            LinkPolicy.openableTarget("https://example.com@example.net/"),
        )
    }

    @Test
    fun `non-ASCII and invisible characters are percent-encoded so nothing hides in the target`() {
        assertEquals(
            "https://example.com/%C3%A7%E2%80%AEtxt",
            LinkPolicy.openableTarget("https://example.com/ç‮txt"),
        )
    }

    @Test
    fun `other schemes never open`() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "file:///sdcard/a.txt",
            "content://media/external/1",
            "intent://example.com#Intent;scheme=https;end",
            "mailto:someone@example.com",
            "tel:123",
            "data:text/html,hi",
            "ftp://example.com/a",
            "phopencode://pending",
            "market://details?id=x",
        ).forEach { assertNull(LinkPolicy.openableTarget(it), it) }
    }

    @Test
    fun `targets without a usable host never open`() {
        listOf(
            "",
            "   ",
            "example.com",
            "/relative/path",
            "#anchor",
            "https:",
            "https:/",
            "https://",
            "https:///path",
            "https:example.com",
            "http:\\\\example.com",
            "https://exa mple.com",
            "https://example.com/\u0000",
            "https://ex‮ample.com/",
        ).forEach { assertNull(LinkPolicy.openableTarget(it), it) }
    }

    @Test
    fun `the shown form of any target escapes invisible characters`() {
        assertEquals("javascript:a\\u202Eb\\u0000", LinkPolicy.visibleForm("javascript:a‮b\u0000"))
        assertEquals("tel:123", LinkPolicy.visibleForm("tel:123"))
    }
}
