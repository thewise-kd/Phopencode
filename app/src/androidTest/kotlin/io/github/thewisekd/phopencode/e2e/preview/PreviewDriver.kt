package io.github.thewisekd.phopencode.e2e.preview

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thewisekd.phopencode.MainActivity
import io.github.thewisekd.phopencode.core.navigation.ConnectRoute
import io.github.thewisekd.phopencode.core.navigation.SessionsRoute
import io.github.thewisekd.phopencode.e2e.E2eApp
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressPresenter
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import io.github.thewisekd.phopencode.feature.sessions.ui.R as SessionsR

/** The title of `.github/fixtures/preview/index.html`. */
internal const val FIXTURE_TITLE = "phopencode preview fixture"

/** The page's own `console.log` line. */
internal const val FIXTURE_LOG = "phopencode-fixture: loaded"

/** The documentation-range address (RFC 5737) the fixture's off-origin link and fetch name. */
internal const val OFF_ORIGIN_HOST = "203.0.113.10"

/**
 * The fixture's port on the runner's loopback, the `previewPort` instrumentation argument that
 * `.github/workflows/emulator.yml` passes; missing, it fails or skips as [io.github.thewisekd.phopencode.e2e.E2eEnvironment] does.
 */
internal fun previewPortArgument(): Int {
    val arguments = InstrumentationRegistry.getArguments()
    val port = arguments.getString("previewPort")?.trim()?.toIntOrNull()
    if (port == null || port !in 1..65535) {
        val message = "No preview fixture: missing instrumentation argument previewPort"
        if (arguments.getString("requireE2e") == "true") fail(message)
        assumeTrue(message, false)
    }
    return checkNotNull(port)
}

/**
 * Drives the live preview the way a user does, through the app's real screens in [MainActivity]:
 * the session list's menu, the port picker and the preview page with its WebView. Compose nodes are
 * found by the test tags of [PreviewTags]; the WebView's page is read with `evaluateJavascript`.
 *
 * Nothing here prints an address of the preview: the bridge's first URL carries its secret.
 */
internal class PreviewDriver(
    private val app: E2eApp,
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    val fixturePort: Int,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** From the session list of the project folder, its menu's "Live preview" opens the port picker. */
    fun openPortPicker() {
        val activity = compose.activity
        compose.runOnIdle {
            checkNotNull(activity.navController) { "No NavHost" }.navigate(SessionsRoute.SessionList(app.env.projectDir))
        }
        val menu = hasContentDescription(activity.getString(SessionsR.string.sessions_list_catalog))
        waitForNode("the session list's menu", menu)
        compose.onNode(menu).performClick()
        val item = hasTestTag(PreviewTags.SESSIONS_MENU_PREVIEW) and isEnabled()
        waitForNode("the menu's live preview item, enabled while connected", item)
        compose.onNode(item).performClick()
    }

    /** Taps the fixture's port in the picker's list, once the listing shows it. */
    fun openFixtureFromList() {
        val row = PreviewTags.port(fixturePort)
        waitForNode("the fixture's port in the list", hasTestTag(row))
        compose.onNodeWithTag(row, useUnmergedTree = true).performScrollTo().performClick()
    }

    /** Waits for the preview's WebView to show the fixture page. */
    fun awaitFixturePage() {
        waitForNode("the preview's WebView", hasTestTag(PreviewTags.PAGE_WEB_VIEW))
        waitFor("the fixture's title in the preview") { title() == FIXTURE_TITLE }
        waitFor("the fixture to finish loading") { evaluate("document.readyState") == "complete" }
    }

    /** The bridge's port on the phone: the port of the address the WebView shows. */
    fun bridgePort(): Int {
        val activity = compose.activity
        val port = onMain { findWebView(activity)?.url?.let { Uri.parse(it).port } } ?: -1
        check(port in 1..65535) { "The WebView shows no address on a port" }
        return port
    }

    /** The WebView's page title; null without a WebView. */
    fun title(): String? {
        val activity = compose.activity
        return onMain { findWebView(activity)?.title }
    }

    /**
     * Runs [script] in the page and returns its result decoded from JSON: a string, a number, a
     * boolean, or null for `null`, `undefined` or no WebView.
     */
    fun evaluate(script: String): Any? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val activity = compose.activity
        val started = onMain {
            val web = findWebView(activity) ?: return@onMain false
            web.evaluateJavascript(script) { value ->
                result.set(value)
                latch.countDown()
            }
            true
        }
        if (!started) return null
        if (!latch.await(JS_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)) throw AssertionError("The page did not answer a script")
        return decode(result.get())
    }

    /** A script's result as `evaluateJavascript` gives it, JSON, decoded; null for `null` and none. */
    private fun decode(raw: String?): Any? {
        if (raw == null) return null
        return JSONArray("[$raw]").opt(0).takeUnless { it == JSONObject.NULL }
    }

    /**
     * Runs [script] in a fresh, plain WebView of the test whose document has [origin], and returns its
     * result decoded as [evaluate] does. The document comes from `loadDataWithBaseURL`, so nothing
     * is fetched: it only shares the origin, and so the origin's `localStorage`, with the preview,
     * which uses the app's default WebView profile. The WebView is destroyed afterwards.
     */
    fun evaluateOnOrigin(origin: String, script: String): Any? {
        val activity = compose.activity
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val asked = AtomicBoolean(false)
        val web = onMain {
            WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!asked.compareAndSet(false, true)) return
                        view.evaluateJavascript(script) { value ->
                            result.set(value)
                            latch.countDown()
                        }
                    }
                }
                loadDataWithBaseURL("$origin/", "<html><body></body></html>", "text/html", "utf-8", null)
            }
        }
        try {
            if (!latch.await(JS_TIMEOUT.inWholeSeconds, TimeUnit.SECONDS)) {
                throw AssertionError("The probe WebView did not answer a script")
            }
        } finally {
            onMain { web.destroy() }
        }
        return decode(result.get())
    }

    /** Leaves the current screen with the system back, as a user does. */
    fun back() {
        val activity = compose.activity
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
    }

    fun click(tag: String) {
        waitForNode("the node $tag", hasTestTag(tag))
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    fun waitForNode(what: String, matcher: SemanticsMatcher) = waitFor(what) { exists(matcher) }

    fun waitForNoNode(what: String, matcher: SemanticsMatcher) = waitFor(what) { !exists(matcher) }

    fun exists(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    /**
     * Connects the test's profile again after it was disconnected: its host key is approved and its
     * server password stored by [E2eApp.connect], so the progress screen connects without asking.
     */
    suspend fun reconnect() {
        val progress = app.open<ProgressPresenter>(ConnectRoute.Progress(app.profileId.value))
        assertEquals(ProgressEvent.Connected, E2eApp.await(progress.presenter.events, "the reconnection"))
        progress.close()
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    /** The preview's WebView on screen: there is only one WebView in the app. Main thread. */
    private fun findWebView(activity: MainActivity): WebView? = find(activity.window.decorView)

    private fun find(view: View): WebView? {
        if (view is WebView) return view.takeIf { it.isAttachedToWindow }
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }

    companion object {
        private val JS_TIMEOUT = 30.seconds
        private const val POLL_MILLIS = 250L

        /** Polls [done] on the test thread until it holds; a timeout names [what] only. */
        fun waitFor(what: String, timeout: Duration = E2eApp.STEP, done: () -> Boolean) {
            val mark = TimeSource.Monotonic.markNow()
            while (true) {
                if (done()) return
                if (mark.elapsedNow() > timeout) throw AssertionError("Waited $timeout for $what")
                Thread.sleep(POLL_MILLIS)
            }
        }
    }
}

/**
 * What a client that is not the WebView gets from the bridge on the phone's loopback: a plain
 * HTTP/1.1 request with neither the secret's query nor its cookie. Returns the status line, or
 * null when the connection is refused, which is what a closed bridge gives.
 */
internal fun requestWithoutSecret(port: Int): String? {
    val socket = Socket()
    try {
        socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MILLIS)
    } catch (refused: java.net.ConnectException) {
        socket.close()
        return null
    }
    return socket.use {
        it.soTimeout = READ_TIMEOUT_MILLIS
        val request = "GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAccept: text/html\r\nConnection: close\r\n\r\n"
        it.getOutputStream().apply {
            write(request.toByteArray(Charsets.US_ASCII))
            flush()
        }
        val input = it.getInputStream()
        val status = input.readLine()
        // The bridge closes the connection after its 403: reading on reaches the end.
        while (input.read() != -1) Unit
        status
    }
}

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val READ_TIMEOUT_MILLIS = 30_000

private fun InputStream.readLine(): String {
    val line = StringBuilder()
    while (true) {
        val byte = read()
        if (byte == -1 || byte == '\n'.code) break
        if (byte != '\r'.code) line.append(byte.toChar())
    }
    return line.toString()
}
