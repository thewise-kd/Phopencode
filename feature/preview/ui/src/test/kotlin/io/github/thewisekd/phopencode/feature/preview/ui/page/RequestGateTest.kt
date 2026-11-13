package io.github.thewisekd.phopencode.feature.preview.ui.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which addresses the WebView may fetch: the bridge's own origin, and what never leaves the WebView.
 * Robolectric for `android.net.Uri`; level 26 only, as nothing here depends on the level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class RequestGateTest {
    private val origin = PreviewOrigin(41234)

    @Test
    fun `the bridge origin may be fetched, on any path`() {
        listOf(
            "http://127.0.0.1:41234/",
            "http://127.0.0.1:41234/src/main.ts?t=1",
            "http://127.0.0.1:41234/@vite/client",
            "HTTP://127.0.0.1:41234/upper-case-scheme",
        ).forEach { assertTrue(RequestGate.mayFetch(it, origin), it) }
    }

    @Test
    fun `addresses that never leave the WebView pass`() {
        listOf(
            "data:image/png;base64,iVBORw0KGgo=",
            "about:blank",
            "about:srcdoc",
            "blob:http://127.0.0.1:41234/9f1c2a7e-0000-4000-8000-000000000000",
        ).forEach { assertTrue(RequestGate.mayFetch(it, origin), it) }
    }

    @Test
    fun `everything else is refused`() {
        listOf(
            // Another port of the phone's loopback: another app's server, or an older bridge.
            "http://127.0.0.1:41235/",
            "http://127.0.0.1/",
            "http://localhost:41234/",
            "http://[::1]:41234/",
            "http://0.0.0.0:41234/",
            // The right host and port in the wrong place.
            "https://127.0.0.1:41234/",
            "ws://127.0.0.1:41234/",
            "http://127.0.0.1:41234@evil.example.invalid/",
            "http://user@127.0.0.1:41234/",
            "http://evil.example.invalid/?u=http://127.0.0.1:41234/",
            "blob:https://evil.example.invalid/9f1c2a7e",
            "blob:http://127.0.0.1:41235/9f1c2a7e",
            // Other schemes.
            "file:///sdcard/secret.txt",
            "content://io.github.thewisekd.phopencode.provider/x",
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "about:config",
            "chrome://settings",
            "ftp://127.0.0.1:41234/",
            "",
            "not a url",
        ).forEach { assertFalse(RequestGate.mayFetch(it, origin), it) }
    }

    @Test
    fun `without an origin only what never leaves the WebView passes`() {
        assertFalse(RequestGate.mayFetch("http://127.0.0.1:41234/", null))
        assertFalse(RequestGate.isOnOrigin("http://127.0.0.1:41234/", null))
        assertTrue(RequestGate.mayFetch("about:blank", null))
    }

    @Test
    fun `on the origin means the bridge's own http address only`() {
        assertTrue(RequestGate.isOnOrigin("http://127.0.0.1:41234/a", origin))
        assertFalse(RequestGate.isOnOrigin("data:text/plain,x", origin))
        assertFalse(RequestGate.isOnOrigin("blob:http://127.0.0.1:41234/x", origin))
        assertFalse(RequestGate.isOnOrigin("http://127.0.0.1:41235/a", origin))
    }
}
