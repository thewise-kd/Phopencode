package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.FrameLayout
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadRequest
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageState
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageStatus
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The page stores a cookie and a localStorage value, then sets its title. */
private const val STORE_PAGE = """<script>
    document.cookie = 'preview_test=1; path=/';
    localStorage.setItem('preview_test', '1');
    document.title = 'stored';
</script>"""

/** Reads both back, as `read:<value>:<cookies>`. */
private const val READ_SCRIPT = "'read:' + localStorage.getItem('preview_test') + ':' + document.cookie"

/** The page reads both back into its title. */
private const val READ_PAGE = "<script>document.title = $READ_SCRIPT;</script>"

/** What a fresh WebView reads after the data was cleared: no stored value and no cookie. */
private const val NOTHING_LEFT = "read:null:"
private const val RETRY_MS = 200L

/**
 * Leaving the preview leaves nothing behind: cookies, web storage, the HTTP cache, history and form
 * data are cleared and the WebView is destroyed, whether the page logic closes it or the screen's
 * destination leaves the back stack.
 */
@RunWith(AndroidJUnit4::class)
class PreviewWebDataTest {
    private val host = RecordingHost()
    private val site = FakeSite(mapOf("/" to STORE_PAGE, "/second" to "<title>second</title>", "/read" to READ_PAGE))

    @Before
    fun startClean() {
        onMain {
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        waitForNoCookies()
        waitForNoStorage()
    }

    /** A WebView on [TEST_ORIGIN] that stored a cookie and a value and went on to a second page. */
    private fun usedWebView(): RecordingWebView {
        val view = onMain { previewWebView(host, site) }
        loadAndWait(view, "/", "stored")
        loadAndWait(view, "/second", "second")
        assertTrue(onMain { view.canGoBack() })
        waitUntil("the cookie is stored") { CookieManager.getInstance().hasCookies() }
        return view
    }

    /**
     * No cookie, no stored value and no saved login is left: a fresh WebView on the same origin reads
     * nothing. Deleting storage finishes on the WebView's own thread, so the read is tried again
     * until it comes back empty or the time is up.
     */
    private fun assertNothingLeft() {
        waitForNoCookies()
        waitForNoStorage()
        @Suppress("DEPRECATION")
        assertFalse(onMain { WebViewDatabase.getInstance(targetContext).hasHttpAuthUsernamePassword() })
        val fresh = onMain { previewWebView(host, site) }
        try {
            onMain { fresh.loadUrl(TEST_ORIGIN.url + "/read") }
            waitUntil("the read page answers") { fresh.title?.startsWith("read:") == true }
            val deadline = System.currentTimeMillis() + WAIT_MS
            var read = onMain { fresh.title }
            while (read != NOTHING_LEFT && System.currentTimeMillis() < deadline) {
                Thread.sleep(RETRY_MS)
                read = evaluate(fresh, READ_SCRIPT)?.removeSurrounding("\"")
            }
            assertEquals(NOTHING_LEFT, read)
        } finally {
            onMain { PreviewWebViews.clearAndDestroy(fresh) }
        }
    }

    @Test
    fun clearingRemovesCookiesStorageCacheHistoryAndFormData() {
        val view = usedWebView()

        onMain { PreviewWebViews.clearData(view) }

        assertTrue(view.calls.contains("clearCache:true"))
        assertTrue(view.calls.contains("clearHistory"))
        assertTrue(view.calls.contains("clearFormData"))
        assertFalse(onMain { view.canGoBack() })
        onMain { view.destroy() }
        assertNothingLeft()
    }

    @Test
    fun destroyingTakesTheWebViewOffTheScreen() {
        val view = usedWebView()
        val parent = onMain { FrameLayout(targetContext).also { it.addView(view) } }

        onMain { PreviewWebViews.clearAndDestroy(view) }

        assertNull(onMain { view.parent })
        assertEquals(0, onMain { parent.childCount })
        assertEquals("destroy", view.calls.last())
        assertNothingLeft()
    }

    @Test
    fun theHolderLoadsTheBridgeUrlOncePerRequest() {
        val holder = holder()
        val first = opened(LoadRequest(1, TEST_ORIGIN.url + "/"))

        onMain { holder.sync(targetContext, first) }
        val view = checkNotNull(holder.webView) as RecordingWebView
        waitUntil("the entry page loads") { view.title == "stored" }
        onMain { holder.sync(targetContext, first.copy(status = PageStatus.Loaded)) }
        onMain { holder.sync(targetContext, first.copy(load = LoadRequest(2, TEST_ORIGIN.url + "/second"))) }
        waitUntil("the second request loads") { view.title == "second" }

        assertEquals(2, view.calls.count { it == "loadUrl" })
        assertSame(view, holder.webView)
        onMain { holder.release() }
    }

    @Test
    fun aClosedPreviewClearsAndDestroysItsWebView() {
        val holder = holder()
        val state = opened(LoadRequest(1, TEST_ORIGIN.url + "/"))
        onMain { holder.sync(targetContext, state) }
        val view = holder.webView as RecordingWebView
        waitUntil("the entry page stores its data") { view.title == "stored" }

        onMain { holder.sync(targetContext, state.copy(status = PageStatus.Closed)) }

        assertNull(holder.webView)
        assertTrue(view.calls.containsAll(listOf("clearCache:true", "clearHistory", "clearFormData", "destroy")))
        assertNothingLeft()
    }

    @Test
    fun leavingTheScreenClearsTheDataAndClosesThePreview() {
        val sent = CopyOnWriteArrayList<PageIntent>()
        val store = ViewModelStore()
        val holder = ViewModelProvider(store, factoryOf(holder()))[PreviewWebHolder::class.java]
        onMain { holder.bind(TEST_ORIGIN, { sent += it }, { NavigationDecision.Refuse }) }
        onMain { holder.sync(targetContext, opened(LoadRequest(1, TEST_ORIGIN.url + "/"))) }
        val view = holder.webView as RecordingWebView
        waitUntil("the entry page stores its data") { view.title == "stored" }

        onMain { store.clear() }

        assertNull(holder.webView)
        assertTrue(view.calls.contains("destroy"))
        // Closing the page logic closes the bridge and the tunnel.
        assertEquals(PageIntent.Close, sent.last())
        assertNothingLeft()
    }

    @Test
    fun aDeadRendererIsDestroyedAndReportedAsANetworkFailure() {
        val sent = CopyOnWriteArrayList<PageIntent>()
        val holder = holder()
        onMain { holder.bind(TEST_ORIGIN, { sent += it }, { NavigationDecision.Refuse }) }
        val first = opened(LoadRequest(1, TEST_ORIGIN.url + "/"))
        onMain { holder.sync(targetContext, first) }
        val view = holder.webView as RecordingWebView

        onMain { holder.renderGone(view) }

        assertNull(holder.webView)
        assertTrue(view.calls.contains("destroy"))
        assertTrue(sent.last() is PageIntent.LoadFailed)

        // The same request is not replayed on a new WebView by itself: a page that kills its renderer
        // would loop. Only the page logic's next request, after the user reloads, loads again.
        onMain { holder.sync(targetContext, first) }
        onMain { holder.sync(targetContext, first.copy(status = PageStatus.Loaded)) }
        assertNull(holder.webView)

        onMain { holder.sync(targetContext, first.copy(load = LoadRequest(2, TEST_ORIGIN.url + "/"))) }
        val reloaded = checkNotNull(holder.webView) as RecordingWebView
        assertTrue(reloaded !== view)
        assertEquals(1, reloaded.calls.count { it == "loadUrl" })
        onMain { holder.release() }
    }

    private fun opened(load: LoadRequest) =
        PageState(port = 5173, status = PageStatus.Opening, origin = TEST_ORIGIN, load = load)

    /** A holder whose WebViews serve [site] from memory and record what they are asked. */
    private fun holder() = PreviewWebHolder { context, webHost ->
        PreviewWebViews.create(context, webHost, site::serve) { RecordingWebView(it) }
    }.also { holder -> onMain { holder.bind(TEST_ORIGIN, host::send, host::decide) } }

    private fun factoryOf(holder: PreviewWebHolder) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = holder as T
    }
}
