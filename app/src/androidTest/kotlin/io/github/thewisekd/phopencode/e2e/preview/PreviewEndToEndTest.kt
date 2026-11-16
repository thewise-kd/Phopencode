package io.github.thewisekd.phopencode.e2e.preview

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.e2e.E2eApp
import io.github.thewisekd.phopencode.e2e.E2eEnvironment
import io.github.thewisekd.phopencode.e2e.FirstRunDoneRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.thewisekd.phopencode.core.ui.R as UiR

/**
 * The live preview, end to end (P13.20, D9 option (b)): `.github/workflows/emulator.yml` serves
 * `.github/fixtures/preview/` with `python3 -m http.server --bind 127.0.0.1` beside the test sshd,
 * as a dev server would run on a real server. Each test connects as [E2eApp.connect] does, then
 * drives the app's real screens in [MainActivity]: the session list's menu opens the port picker,
 * the fixture's port opens the preview, and the page in the WebView is read with scripts.
 *
 * The page's off-origin link and fetch name 203.0.113.10, a documentation address: nothing there
 * answers, and the preview must never ask it anyway.
 *
 * Runs on the debug build only: the workflow leaves this package out of the releaseCheck run,
 * whose keep rules do not cover androidx.compose.ui.test.
 */
@RunWith(AndroidJUnit4::class)
class PreviewEndToEndTest {
    /** The first-run guide and the preview notice, ended before the activity starts (P14.3). */
    @get:Rule(order = 0)
    val firstRun = FirstRunDoneRule()

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var app: E2eApp
    private lateinit var preview: PreviewDriver

    @Before
    fun setUp() = runBlocking {
        val env = E2eEnvironment.fromArguments()
        val port = previewPortArgument()
        app = E2eApp(env)
        app.setUp()
        preview = PreviewDriver(app, compose, port)
    }

    @After
    fun tearDown() = runBlocking {
        if (::app.isInitialized) app.tearDown()
    }

    @Test
    fun opensTheListedPortAndKeepsThePageOnItsOrigin() = runBlocking<Unit> {
        openFixture()

        // The page's own console.log reaches the console panel.
        preview.click(PreviewTags.CONSOLE_BUTTON)
        preview.waitForNode("the page's console.log in the console", inConsole(FIXTURE_LOG))

        // The off-origin fetch fails in the page and the console says it was refused.
        PreviewDriver.waitFor("the off-origin fetch to fail") { preview.evaluate("window.offOriginFetch") == "refused" }
        preview.click(PreviewTags.consoleFilter("Error"))
        preview.waitForNode("the refused off-origin request in the console", inConsole(OFF_ORIGIN_HOST))
        preview.click(PreviewTags.consoleFilter(null))
        preview.click(PreviewTags.CONSOLE_HIDE)

        // An off-origin link asks first; cancelling keeps the preview where it was.
        preview.evaluate("document.getElementById('external').click(); true")
        val dialog = hasTestTag(PreviewTags.PAGE_EXTERNAL_DIALOG)
        preview.waitForNode("the off-origin link's confirmation", dialog)
        assertTrue(
            "The confirmation does not show the address",
            preview.exists(hasText("$OFF_ORIGIN_HOST/fixture-link", substring = true) and hasAnyAncestor(dialog)),
        )
        val cancel = hasText(compose.activity.getString(UiR.string.ui_common_cancel)) and hasClickAction() and hasAnyAncestor(dialog)
        compose.onNode(cancel, useUnmergedTree = true).performClick()
        preview.waitForNoNode("the confirmation to close", dialog)
        assertEquals(FIXTURE_TITLE, preview.title())
        assertEquals("The page left the preview origin", preview.bridgePort().toString(), preview.evaluate("location.port"))
    }

    @Test
    fun theDesktopToggleChangesTheUserAgent() = runBlocking<Unit> {
        openFixture()
        val mobile = preview.evaluate("navigator.userAgent") as String
        assertTrue("The mobile viewport hides Android", "Android" in mobile)

        preview.click(PreviewTags.VIEWPORT_TOGGLE)
        PreviewDriver.waitFor("the desktop user agent in the page") {
            (preview.evaluate("navigator.userAgent") as? String)?.let { "Android" !in it && "Mobile" !in it } == true
        }
        preview.awaitFixturePage()
        assertNotEquals(mobile, preview.evaluate("navigator.userAgent"))
    }

    @Test
    fun storageIsGoneAfterThePreviewIsClosedAndOpenedAgain() = runBlocking<Unit> {
        openFixture()
        assertEquals("kept", preview.evaluate("localStorage.setItem('phopencode-fixture', 'kept'); localStorage.getItem('phopencode-fixture')"))
        val origin = preview.evaluate("location.origin") as String
        // The probe, a fresh WebView of the test with a document on the same origin and no request,
        // sees the preview's storage while the preview is open: so its null below means cleared.
        assertEquals("The probe does not see the preview's storage", "kept", preview.evaluateOnOrigin(origin, READ_KEY))

        // Back closes the preview, which clears the WebView's data, and returns to the port list.
        preview.back()
        preview.waitForNoNode("the preview to close", hasTestTag(PreviewTags.PAGE_WEB_VIEW))

        // The proof, on the same origin: the value is gone. The reopened preview below has a new
        // bridge, so most likely a new origin, and could not show this alone.
        PreviewDriver.waitFor("the preview's storage to be cleared") { preview.evaluateOnOrigin(origin, READ_KEY) == null }

        preview.openFixtureFromList()
        preview.awaitFixturePage()
        assertNull(preview.evaluate(READ_KEY))
    }

    @Test
    fun aDroppedConnectionShowsConnectionLostAndAReloadAfterTheReconnectWorks() = runBlocking<Unit> {
        openFixture()
        assertEquals(true, preview.evaluate("window.beforeTheDrop = true; true"))

        app.connection.disconnect()
        preview.waitForNode("connection lost", hasTestTag(PreviewTags.PAGE_CONNECTION_LOST))

        preview.reconnect()
        preview.click(PreviewTags.PAGE_RELOAD)
        preview.waitForNoNode("the connection lost panel to go", hasTestTag(PreviewTags.PAGE_CONNECTION_LOST))
        // A fresh fixture page, loaded through the new tunnel: the old page's marker is gone, and the
        // new document has the fixture's title and finished loading. The bridge's port may be reused,
        // so it proves nothing here.
        PreviewDriver.waitFor("the fixture page loaded again") {
            preview.evaluate("[typeof window.beforeTheDrop, document.title, document.readyState].join('|')") ==
                "undefined|$FIXTURE_TITLE|complete"
        }
    }

    @Test
    fun theBridgeRefusesAClientWithoutTheSecretAndClosesWhenTheScreenIsLeft() = runBlocking<Unit> {
        openFixture()
        val bridge = preview.bridgePort()

        val status = requestWithoutSecret(bridge)
        assertTrue("The bridge did not answer 403 without the secret", status?.startsWith("HTTP/1.1 403") == true)
        // The WebView, which holds the secret's cookie, still gets the page.
        assertEquals(FIXTURE_TITLE, preview.title())

        preview.back()
        preview.waitForNoNode("the preview to close", hasTestTag(PreviewTags.PAGE_WEB_VIEW))
        PreviewDriver.waitFor("the bridge port to close") { requestWithoutSecret(bridge) == null }
        assertFalse(preview.exists(hasTestTag(PreviewTags.PAGE_WEB_VIEW)))
    }

    /**
     * P13.22: WebRTC is gone from the page and from a frame of its origin before their first
     * scripts, and the page cannot bring it back.
     */
    @Test
    fun webRtcIsGoneFromThePageAndItsOriginFrameAndCannotBeRestored() = runBlocking<Unit> {
        openFixture()

        // The top frame, from the page's first script on.
        assertEquals("undefined", preview.evaluate("window.rtcAtStart"))
        assertEquals("undefined", preview.evaluate("typeof RTCPeerConnection"))
        assertEquals("", preview.evaluate("$WEB_RTC_LEFT_IN(window)"))

        // A frame loaded from the preview origin.
        PreviewDriver.waitFor("the origin frame to finish loading") {
            preview.evaluate("$ORIGIN_FRAME.document.readyState") == "complete"
        }
        assertEquals("undefined", preview.evaluate("$ORIGIN_FRAME.rtcAtStart"))
        assertEquals("undefined", preview.evaluate("typeof $ORIGIN_FRAME.RTCPeerConnection"))
        assertEquals("", preview.evaluate("$WEB_RTC_LEFT_IN($ORIGIN_FRAME)"))

        // Neither assigning nor deleting brings the constructor back.
        assertEquals(
            "undefined",
            preview.evaluate("(function () { try { window.RTCPeerConnection = 1; } catch (e) {} return typeof RTCPeerConnection; })()"),
        )
        assertEquals(
            "undefined",
            preview.evaluate("(function () { try { delete window.RTCPeerConnection; } catch (e) {} return typeof RTCPeerConnection; })()"),
        )
        assertEquals("undefined", preview.evaluate("typeof window.RTCPeerConnection"))
    }

    /**
     * P13.22: an about:blank frame the page makes by script, read at once through `contentWindow`, as
     * a page would to get a fresh `RTCPeerConnection`, has no WebRTC either.
     */
    @Test
    fun webRtcIsGoneFromAnAboutBlankFrameMadeByScript() = runBlocking<Unit> {
        openFixture()

        assertEquals(
            "undefined|",
            preview.evaluate(
                """
                (function () {
                  var frame = document.createElement('iframe');
                  document.body.appendChild(frame);
                  var win = frame.contentWindow;
                  return typeof win.RTCPeerConnection + '|' + $WEB_RTC_LEFT_IN(win);
                })()
                """.trimIndent(),
            ),
        )
    }

    /** Connects, opens the port picker from the session list's menu and the fixture from the list. */
    private suspend fun openFixture() {
        app.connect()
        preview.openPortPicker()
        preview.openFixtureFromList()
        preview.awaitFixturePage()
    }

    private companion object {
        const val READ_KEY = "localStorage.getItem('phopencode-fixture')"

        /** The fixture's frame on the preview origin, `frame.html`. */
        const val ORIGIN_FRAME = "document.getElementById('origin-frame').contentWindow"

        /**
         * A function expression: the WebRTC globals the window it is given still has, comma-joined.
         * The names repeat feature/preview/ui/…/page/WebRtcBlock.kt's `GLOBALS`, which is internal.
         */
        val WEB_RTC_LEFT_IN = """
            (function (win) {
              return ["RTCPeerConnection", "webkitRTCPeerConnection", "mozRTCPeerConnection", "RTCIceTransport",
                "RTCIceGatherer", "RTCDataChannel", "RTCSessionDescription", "RTCIceCandidate", "RTCRtpSender",
                "RTCRtpReceiver", "RTCRtpTransceiver", "RTCCertificate"]
                .filter(function (name) { return typeof win[name] !== 'undefined'; })
                .join(',');
            })
        """.trimIndent()
    }

    private fun inConsole(text: String) =
        hasText(text, substring = true) and hasAnyAncestor(hasTestTag(PreviewTags.CONSOLE_PANEL))
}
