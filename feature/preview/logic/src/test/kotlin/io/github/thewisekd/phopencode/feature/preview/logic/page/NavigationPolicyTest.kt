package io.github.thewisekd.phopencode.feature.preview.logic.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationPolicyTest {
    private val origin = PreviewOrigin(41234)

    @Test
    fun `addresses on the preview origin are followed`() {
        listOf(
            "http://127.0.0.1:41234",
            "http://127.0.0.1:41234/",
            "http://127.0.0.1:41234/app/index.html?x=1#top",
            "HTTP://127.0.0.1:41234/upper-case-scheme",
        ).forEach { url -> assertEquals(NavigationDecision.Allow, NavigationPolicy.decide(origin, url), url) }
    }

    @Test
    fun `other http and https addresses ask first and keep the full address`() {
        listOf(
            "https://example.com/docs?q=1#a",
            "http://example.com",
            "https://127.0.0.1:41234/",
            "http://127.0.0.1:41235/",
            "http://127.0.0.1/",
            "http://localhost:41234/",
            "http://[::1]:41234/",
            "http://127.0.0.1:41234@example.com/",
            "http://user@127.0.0.1:41234/",
        ).forEach { url ->
            assertEquals(NavigationDecision.AskExternal(ExternalLink(url)), NavigationPolicy.decide(origin, url), url)
        }
    }

    @Test
    fun `every other scheme and every malformed address is refused`() {
        listOf(
            "file:///sdcard/x.html",
            "content://com.android.contacts/contacts",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(document.cookie)",
            "JavaScript:alert(1)",
            "data:text/html,<b>x</b>",
            "blob:http://127.0.0.1:41234/abc",
            "about:blank",
            "chrome://settings",
            "market://details?id=x",
            "tel:123",
            "mailto:a@example.com",
            "ws://127.0.0.1:41234/",
            "ftp://example.com/",
            "http:example.com",
            "http:/example.com",
            "http://",
            "http://exa mple.com/",
            "http://127.0.0.1:41234\\@example.com/",
            "http://example.com/\n",
            " http://127.0.0.1:41234/",
            "/relative/path",
            "127.0.0.1:41234/",
            "",
            "   ",
        ).forEach { url -> assertEquals(NavigationDecision.Refuse, NavigationPolicy.decide(origin, url), url) }
    }

    @Test
    fun `without an open bridge nothing is on the origin`() {
        assertEquals(
            NavigationDecision.AskExternal(ExternalLink("http://127.0.0.1:41234/")),
            NavigationPolicy.decide(null, "http://127.0.0.1:41234/"),
        )
        assertEquals(NavigationDecision.Refuse, NavigationPolicy.decide(null, "javascript:alert(1)"))
    }

    @Test
    fun `the path of an address on the origin drops the bridge secret`() {
        assertEquals("/", NavigationPolicy.pathOn(origin, "http://127.0.0.1:41234"))
        assertEquals("/", NavigationPolicy.pathOn(origin, "http://127.0.0.1:41234/?phopencode_secret=abc"))
        assertEquals("/a/b?x=1&y=2#frag", NavigationPolicy.pathOn(origin, "http://127.0.0.1:41234/a/b?x=1&phopencode_secret=abc&y=2#frag"))
        assertEquals("/a?x=%20", NavigationPolicy.pathOn(origin, "http://127.0.0.1:41234/a?x=%20"))
    }

    @Test
    fun `an address off the origin has no path on it`() {
        assertNull(NavigationPolicy.pathOn(origin, "https://example.com/a"))
        assertNull(NavigationPolicy.pathOn(origin, "http://127.0.0.1:41235/a"))
        assertNull(NavigationPolicy.pathOn(origin, "chrome-error://chromewebdata/"))
        assertNull(NavigationPolicy.pathOn(origin, "about:blank"))
    }
}
