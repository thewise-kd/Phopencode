package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail

internal const val WAIT_MS = 15_000L
private const val POLL_MS = 50L

/** A made-up bridge port; the tests serve its pages from memory, so nothing listens on it. */
internal val TEST_ORIGIN = PreviewOrigin(41234)

internal val targetContext: Context
    get() = InstrumentationRegistry.getInstrumentation().targetContext

/** Runs [block] on the main thread and gives back its result or throws its exception. */
internal fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

/** Waits up to [WAIT_MS] for [condition], checked on the main thread, and fails with [what]. */
internal fun waitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + WAIT_MS
    while (System.currentTimeMillis() < deadline) {
        if (onMain(condition)) return
        Thread.sleep(POLL_MS)
    }
    fail("Timed out waiting until $what")
}

/** Runs [script] in [view] and gives back its JSON result. */
internal fun evaluate(view: WebView, script: String): String? {
    val latch = CountDownLatch(1)
    var result: String? = null
    onMain { view.evaluateJavascript(script) { result = it; latch.countDown() } }
    check(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "Script did not answer" }
    return result
}

/** Loads [path] of [TEST_ORIGIN] in [view] and waits until the page sets its title to [title]. */
internal fun loadAndWait(view: WebView, path: String, title: String) {
    onMain { view.loadUrl(TEST_ORIGIN.url + path) }
    waitUntil("$path sets its title to $title") { view.title == title }
}

/** All cookies gone, waiting for the cookie store's own thread. */
internal fun waitForNoCookies() = waitUntil("no cookie is left") { !CookieManager.getInstance().hasCookies() }

/** No origin holds web storage any more; deleting it runs on the WebView's storage thread. */
internal fun waitForNoStorage() {
    val deadline = System.currentTimeMillis() + WAIT_MS
    while (storageOrigins() != 0) {
        if (System.currentTimeMillis() > deadline) fail("Timed out waiting until web storage is empty")
        Thread.sleep(POLL_MS)
    }
}

/** The origins that hold web storage, from the WebView's storage thread. */
internal fun storageOrigins(): Int {
    val latch = CountDownLatch(1)
    var count = -1
    onMain { WebStorage.getInstance().getOrigins { origins -> count = origins?.size ?: 0; latch.countDown() } }
    check(latch.await(WAIT_MS, TimeUnit.MILLISECONDS)) { "Storage did not answer" }
    return count
}

/**
 * Stands in for the page logic: records what the WebView sends and answers navigations as the
 * contract says: the origin is followed, another `http(s)` address asks, the rest is refused.
 */
internal class RecordingHost(origin: PreviewOrigin? = TEST_ORIGIN) : PreviewWebHost {
    @Volatile
    override var origin: PreviewOrigin? = origin

    val intents = CopyOnWriteArrayList<PageIntent>()
    val renderersGone = CopyOnWriteArrayList<WebView>()

    override fun decide(url: String): NavigationDecision = when {
        RequestGate.isOnOrigin(url, origin) -> NavigationDecision.Allow
        url.startsWith("http://") || url.startsWith("https://") -> NavigationDecision.AskExternal(ExternalLink(url))
        else -> NavigationDecision.Refuse
    }

    override fun send(intent: PageIntent) {
        intents += intent
    }

    override fun progress(percent: Int) = Unit

    override fun pulled(fraction: Float) = Unit

    override fun renderGone(view: WebView) {
        renderersGone += view
    }

    /** The addresses reported as refused, in order. */
    fun refused(): List<String> = intents.filterIsInstance<PageIntent.Report>()
        .filter { it.entry.kind == ConsoleKind.RefusedRequest }
        .map { it.entry.text }
}

/**
 * The bridge's pages, served from memory through [PreviewWebViews.create]'s pass-through, so the
 * tests need no socket and no cleartext permission. Only requests the gate let through reach it;
 * [requested] lists them.
 */
internal class FakeSite(private val pages: Map<String, String>) {
    val requested = CopyOnWriteArrayList<String>()

    fun serve(request: WebResourceRequest): WebResourceResponse {
        requested += request.url.toString()
        val page = pages[request.url.path]
        return if (page == null) {
            WebResourceResponse("text/html", "utf-8", 404, "Not Found", NO_STORE, ByteArrayInputStream(ByteArray(0)))
        } else {
            WebResourceResponse("text/html", "utf-8", 200, "OK", NO_STORE, ByteArrayInputStream(page.toByteArray()))
        }
    }

    private companion object {
        val NO_STORE = mapOf("Cache-Control" to "no-store")
    }
}

/** A preview WebView that records what clearing and loading call on it. */
internal class RecordingWebView(context: Context) : PreviewWebView(context) {
    val calls = CopyOnWriteArrayList<String>()

    override fun loadUrl(url: String) {
        calls += "loadUrl"
        super.loadUrl(url)
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        calls += "clearCache:$includeDiskFiles"
        super.clearCache(includeDiskFiles)
    }

    override fun clearHistory() {
        calls += "clearHistory"
        super.clearHistory()
    }

    override fun clearFormData() {
        calls += "clearFormData"
        super.clearFormData()
    }

    override fun destroy() {
        calls += "destroy"
        super.destroy()
    }
}

/** An isolated preview WebView on [TEST_ORIGIN] whose pages come from [site]. Main thread. */
internal fun previewWebView(host: PreviewWebHost, site: FakeSite): RecordingWebView =
    PreviewWebViews.create(targetContext, host, site::serve) { RecordingWebView(it) } as RecordingWebView
