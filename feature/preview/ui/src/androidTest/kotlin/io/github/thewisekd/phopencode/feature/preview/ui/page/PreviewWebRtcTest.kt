package io.github.thewisekd.phopencode.feature.preview.ui.page

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A script expression: the names of [WebRtcBlock.GLOBALS] that `win` still has, comma-joined. */
private val LEFT_IN = """
    (function (win) {
      return ${WebRtcBlock.GLOBALS.joinToString(", ", "[", "]") { "\"$it\"" }}
        .filter(function (name) { return typeof win[name] !== 'undefined'; })
        .join(',');
    })
""".trimIndent()

/**
 * The WebRTC block (P13.22) in a real WebView: [PreviewWebViews.create] registers [WebRtcBlock.SCRIPT]
 * before the first load, and the page finds no WebRTC global in the top frame, in a frame of its
 * origin or in an `about:blank` frame it makes itself, and cannot bring one back.
 */
@RunWith(AndroidJUnit4::class)
class PreviewWebRtcTest {
    private val host = RecordingHost()
    private val site = FakeSite(
        mapOf(
            "/rtc" to """
                <script>window.rtcAtStart = typeof RTCPeerConnection;</script>
                <iframe id="origin-frame" src="/rtc-frame"></iframe>
                <script>
                    window.addEventListener('load', function () { document.title = 'rtc'; });
                </script>
            """.trimIndent(),
            "/rtc-frame" to "<script>window.rtcAtStart = typeof RTCPeerConnection;</script>",
        ),
    )
    private val view = onMain { previewWebView(host, site) }

    @After
    fun destroy() {
        onMain { PreviewWebViews.clearAndDestroy(view) }
    }

    @Test
    fun theTestWebViewRunsDocumentStartScripts() {
        assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
    }

    @Test
    fun theTopFrameHasNoWebRtcEvenForItsFirstScript() {
        loadAndWait(view, "/rtc", "rtc")

        assertEquals("\"undefined\"", evaluate(view, "window.rtcAtStart"))
        assertEquals("\"undefined\"", evaluate(view, "typeof RTCPeerConnection"))
        assertEquals("\"\"", evaluate(view, "$LEFT_IN(window)"))
    }

    @Test
    fun aFrameOfTheOriginHasNoWebRtc() {
        loadAndWait(view, "/rtc", "rtc")
        val frame = "document.getElementById('origin-frame').contentWindow"

        assertEquals("\"undefined\"", evaluate(view, "$frame.rtcAtStart"))
        assertEquals("\"undefined\"", evaluate(view, "typeof $frame.RTCPeerConnection"))
        assertEquals("\"\"", evaluate(view, "$LEFT_IN($frame)"))
    }

    @Test
    fun anAboutBlankFrameMadeByScriptHasNoWebRtc() {
        loadAndWait(view, "/rtc", "rtc")

        val script = """
            (function () {
              var frame = document.createElement('iframe');
              document.body.appendChild(frame);
              return typeof frame.contentWindow.RTCPeerConnection + '|' + $LEFT_IN(frame.contentWindow);
            })()
        """.trimIndent()

        assertEquals("\"undefined|\"", evaluate(view, script))
    }

    @Test
    fun assigningOrDeletingDoesNotBringWebRtcBack() {
        loadAndWait(view, "/rtc", "rtc")

        assertEquals(
            "\"undefined\"",
            evaluate(view, "(function () { try { window.RTCPeerConnection = 1; } catch (e) {} return typeof RTCPeerConnection; })()"),
        )
        assertEquals(
            "\"undefined\"",
            evaluate(view, "(function () { try { delete window.RTCPeerConnection; } catch (e) {} return typeof RTCPeerConnection; })()"),
        )
        assertEquals(
            "\"undefined\"",
            evaluate(
                view,
                "(function () { try { Object.defineProperty(window, 'RTCPeerConnection', { value: function () {} }); } " +
                    "catch (e) {} return typeof RTCPeerConnection; })()",
            ),
        )
        assertEquals(
            "\"false|false\"",
            evaluate(
                view,
                "(function () { var d = Object.getOwnPropertyDescriptor(window, 'RTCPeerConnection'); " +
                    "return d.configurable + '|' + d.writable; })()",
            ),
        )
    }
}
