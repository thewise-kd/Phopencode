package io.github.thewisekd.phopencode.feature.preview.ui.page

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Another port of the phone's loopback: another app's server, or an older bridge. */
private val OTHER_PORT_URL = "http://127.0.0.1:${TEST_ORIGIN.bridgePort + 1}/other.png"
private const val OFF_SITE_IMAGE = "http://preview-test.invalid/image.png"
private const val OFF_SITE_FETCH = "https://preview-test.invalid/api"
private const val EXTERNAL = "https://preview-test.invalid/docs"
private const val INTENT = "intent://scan/#Intent;scheme=zxing;end"

/**
 * The preview's WebView stays on the bridge's origin: requests off it are refused before they leave
 * the WebView and reported to the console; navigations off it ask or are refused.
 */
@RunWith(AndroidJUnit4::class)
class PreviewWebIsolationTest {
    private val host = RecordingHost()
    private val site = FakeSite(
        mapOf(
            "/requests" to """
                <img src="$OTHER_PORT_URL">
                <img src="$OFF_SITE_IMAGE">
                <script>
                    fetch('$OFF_SITE_FETCH').catch(function () {}).then(function () { document.title = 'asked'; });
                </script>
            """.trimIndent(),
            "/links" to """
                <title>links</title>
                <a id="external" href="$EXTERNAL">docs</a>
                <a id="intent" href="$INTENT">app</a>
                <a id="same" href="/landed">same</a>
            """.trimIndent(),
            "/landed" to "<title>landed</title>",
        ),
    )
    private val view = onMain { previewWebView(host, site) }

    @After
    fun destroy() {
        onMain { PreviewWebViews.clearAndDestroy(view) }
    }

    @Test
    fun requestsOffTheOriginAreRefusedAndReported() {
        loadAndWait(view, "/requests", "asked")
        waitUntil("every request off the origin is reported") {
            host.refused().containsAll(listOf(OTHER_PORT_URL, OFF_SITE_IMAGE, OFF_SITE_FETCH))
        }

        assertTrue(site.requested.all { RequestGate.isOnOrigin(it, TEST_ORIGIN) })
        assertTrue(site.requested.contains(TEST_ORIGIN.url + "/requests"))
    }

    @Test
    fun withoutAnOriginNothingIsFetched() {
        host.origin = null
        onMain { view.loadUrl(TEST_ORIGIN.url + "/requests") }
        waitUntil("the load is refused") { host.refused().contains(TEST_ORIGIN.url + "/requests") }

        assertTrue(site.requested.isEmpty())
    }

    @Test
    fun anotherHttpsAddressAsksAndTheWebViewStays() {
        loadAndWait(view, "/links", "links")

        evaluate(view, "document.getElementById('external').click()")
        waitUntil("the address waits for confirmation") { host.intents.contains(PageIntent.RequestExternal(EXTERNAL)) }

        assertEquals(TEST_ORIGIN.url + "/links", onMain { view.url })
        assertTrue(site.requested.none { it.startsWith(EXTERNAL) })
    }

    @Test
    fun otherSchemesAreRefusedAndReported() {
        loadAndWait(view, "/links", "links")

        evaluate(view, "document.getElementById('intent').click()")
        waitUntil("the intent link is refused") { host.refused().any { it.startsWith("intent:") } }

        assertEquals(TEST_ORIGIN.url + "/links", onMain { view.url })
    }

    @Test
    fun theOriginIsFollowed() {
        loadAndWait(view, "/links", "links")

        evaluate(view, "document.getElementById('same').click()")
        // The title changes before onPageFinished runs: wait for the report itself.
        waitUntil("the same-origin page reports it finished") {
            host.intents.contains(PageIntent.LoadFinished(TEST_ORIGIN.url + "/landed"))
        }

        assertEquals("landed", onMain { view.title })
    }

    @Test
    fun anHttpErrorOfTheMainFrameIsALoadError() {
        onMain { view.loadUrl(TEST_ORIGIN.url + "/missing") }
        waitUntil("the 404 is reported") { host.intents.contains(PageIntent.LoadFailed(LoadError.HttpStatus(404))) }

        assertTrue(
            host.intents.filterIsInstance<PageIntent.Report>()
                .any { it.entry.kind == ConsoleKind.HttpError && it.entry.source == TEST_ORIGIN.url + "/missing" },
        )
        assertTrue(host.intents.contains(PageIntent.LoadStarted))
    }
}
