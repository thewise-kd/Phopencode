package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Every global the task names, plus the prefixed constructor older Chromium had. */
private val REQUIRED_GLOBALS = listOf(
    "RTCPeerConnection",
    "webkitRTCPeerConnection",
    "RTCDataChannel",
    "RTCSessionDescription",
    "RTCIceCandidate",
    "RTCRtpSender",
    "RTCRtpReceiver",
    "RTCRtpTransceiver",
    "RTCCertificate",
    // Constructible transports that could gather ICE candidates on their own.
    "RTCIceTransport",
    "RTCIceGatherer",
)

/**
 * The WebRTC block (P13.22): its script, its origin rules, and how [PreviewWebViews.create] and
 * [PreviewWebHolder] register it: once per WebView, before the WebView's first load, and never a
 * WebView without it. The system WebView is stood in for by [FakeDocumentStartScripts]: that the
 * script really runs in each frame is checked on an emulator (`PreviewWebRtcTest` and the app's
 * `PreviewEndToEndTest`). Level 36 has no arm64 Linux runtime, so the Pi runs 26 only; CI runs both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class WebRtcBlockTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** What happened to the WebViews, in order: `new:<n>`, `script:<n>`, `load:<n>:<url>`, `destroy:<n>`. */
    private val events = mutableListOf<String>()
    private var made = 0

    private inner class RecordingView(context: Context, private val number: Int) : PreviewWebView(context) {
        init {
            events += "new:$number"
        }

        override fun loadUrl(url: String) {
            events += "load:$number:$url"
        }

        override fun destroy() {
            events += "destroy:$number"
            super.destroy()
        }

        /** What [FakeDocumentStartScripts] writes after `script:`. */
        override fun toString(): String = "$number"
    }

    private fun holder(scripts: FakeDocumentStartScripts) = PreviewWebHolder { viewContext, host ->
        PreviewWebViews.create(viewContext, host, scripts = scripts, newView = { RecordingView(it, ++made) })
    }

    private fun page(id: Long) = PageState(
        port = 5173,
        status = PageStatus.Opening,
        load = LoadRequest(id, "http://127.0.0.1:41234/page$id"),
    )

    @Test
    fun `the script names each WebRTC global`() {
        REQUIRED_GLOBALS.forEach { name ->
            assertTrue(name in WebRtcBlock.GLOBALS, "$name is not in the list")
            assertTrue("\"$name\"" in WebRtcBlock.SCRIPT, "the script does not name $name")
        }
        WebRtcBlock.GLOBALS.forEach { name -> assertTrue("\"$name\"" in WebRtcBlock.SCRIPT, "the script does not name $name") }
    }

    @Test
    fun `the script defines each global as undefined, non-configurable and non-writable`() {
        val script = WebRtcBlock.SCRIPT
        assertTrue("Object.defineProperty(window, " in script, script)
        assertTrue("value: undefined" in script, script)
        assertTrue("configurable: false" in script, script)
        assertTrue("writable: false" in script, script)
        assertFalse("configurable: true" in script, script)
        assertFalse("writable: true" in script, script)
        // A getter or a setter would leave a way to put something back.
        assertFalse("get:" in script || "set:" in script, script)
    }

    @Test
    fun `the origin rules match every frame`() {
        assertEquals(setOf("*"), WebRtcBlock.ORIGIN_RULES)
    }

    @Test
    fun `the script is registered once per WebView, before its first load`() {
        val scripts = FakeDocumentStartScripts(events)
        val holder = holder(scripts)

        holder.sync(context, page(1))
        holder.sync(context, page(2))

        assertEquals(
            listOf("new:1", "script:1", "load:1:http://127.0.0.1:41234/page1", "load:1:http://127.0.0.1:41234/page2"),
            events,
        )
        val added = scripts.added.single()
        assertSame(holder.webView, added.view)
        assertEquals(WebRtcBlock.SCRIPT, added.script)
        assertEquals(WebRtcBlock.ORIGIN_RULES, added.originRules)
        assertFalse(holder.webViewTooOld)
    }

    @Test
    fun `a new WebView after a release gets the script again before it loads`() {
        val scripts = FakeDocumentStartScripts(events)
        val holder = holder(scripts)

        holder.sync(context, page(1))
        holder.release()
        holder.sync(context, page(2))

        val second = events.drop(events.indexOf("new:2"))
        assertEquals(listOf("new:2", "script:2", "load:2:http://127.0.0.1:41234/page2"), second)
        assertEquals(2, scripts.added.size)
    }

    @Test
    fun `a system WebView without document start scripts gets no WebView and loads nothing`() {
        val scripts = FakeDocumentStartScripts(events, supported = false)
        val holder = holder(scripts)

        holder.sync(context, page(1))
        holder.sync(context, page(2))

        assertEquals(emptyList(), events)
        assertEquals(emptyList(), scripts.added)
        assertNull(holder.webView)
        assertTrue(holder.webViewTooOld)
    }

    @Test
    fun `a WebView that refuses the script is destroyed and loads nothing`() {
        val scripts = FakeDocumentStartScripts(events, failure = UnsupportedOperationException("no document start"))
        val holder = holder(scripts)

        holder.sync(context, page(1))

        assertEquals(listOf("new:1", "destroy:1"), events)
        assertNull(holder.webView)
        assertTrue(holder.webViewTooOld)
    }
}
