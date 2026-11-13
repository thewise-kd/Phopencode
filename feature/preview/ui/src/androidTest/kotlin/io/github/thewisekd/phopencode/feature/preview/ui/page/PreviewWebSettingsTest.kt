package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.ClientCertRequest
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Each setting [PreviewWebViews.create] gives the preview's WebView, and each refusal of its
 * clients, on a real WebView.
 */
@RunWith(AndroidJUnit4::class)
class PreviewWebSettingsTest {
    private val host = RecordingHost()
    private val site = FakeSite(mapOf("/" to "<title>blank</title>"))
    private lateinit var view: RecordingWebView

    @Before
    fun create() {
        view = onMain { previewWebView(host, site) }
    }

    @After
    fun destroy() {
        onMain { PreviewWebViews.clearAndDestroy(view) }
    }

    private fun <T> settings(read: WebSettings.() -> T): T = onMain { view.settings.read() }

    private val chrome: PreviewChromeClient
        get() = onMain { view.webChromeClient } as PreviewChromeClient

    private val client: WebViewClient
        get() = onMain { view.webViewClient }

    @Test
    fun theClientsAreThePreviews() {
        assertTrue(client is PreviewWebViewClient)
        assertTrue(onMain { view.webChromeClient } is PreviewChromeClient)
        assertTrue(view.downloads is PreviewDownloads)
    }

    @Test
    fun javaScriptIsOn() {
        assertTrue(settings { javaScriptEnabled })
    }

    @Test
    @Suppress("DEPRECATION")
    fun fileAndContentAccessAreOff() {
        assertFalse(settings { allowFileAccess })
        assertFalse(settings { allowContentAccess })
        assertFalse(settings { allowFileAccessFromFileURLs })
        assertFalse(settings { allowUniversalAccessFromFileURLs })
    }

    @Test
    fun mixedContentIsNeverAllowed() {
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, settings { mixedContentMode })
    }

    @Test
    fun popUpWindowsAreOff() {
        assertFalse(settings { supportMultipleWindows() })
        assertFalse(settings { javaScriptCanOpenWindowsAutomatically })
        assertFalse(onMain { chrome.onCreateWindow(view, false, true, null) })
    }

    @Test
    @Suppress("DEPRECATION")
    fun formAndPasswordSavingAreOff() {
        assertFalse(settings { saveFormData })
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS, onMain { view.importantForAutofill })
        assertFalse(onMain { view.isSaveEnabled })
    }

    @Test
    fun thirdPartyCookiesAreRefused() {
        assertFalse(onMain { CookieManager.getInstance().acceptThirdPartyCookies(view) })
    }

    @Test
    fun geolocationIsDeniedWithoutAsking() {
        var answer: Pair<Boolean, Boolean>? = null
        onMain {
            chrome.onGeolocationPermissionsShowPrompt(TEST_ORIGIN.url, GeolocationPermissions.Callback { _, allow, retain ->
                answer = allow to retain
            })
        }
        assertEquals(false to false, answer)

        // And the page itself is told no.
        val geoSite = FakeSite(
            mapOf(
                "/geo" to """<script>
                    navigator.geolocation.getCurrentPosition(
                        function () { document.title = 'granted'; },
                        function (e) { document.title = 'denied'; });
                </script>""",
            ),
        )
        val geoView = onMain { previewWebView(host, geoSite) }
        try {
            loadAndWait(geoView, "/geo", "denied")
        } finally {
            onMain { PreviewWebViews.clearAndDestroy(geoView) }
        }
    }

    @Test
    fun cameraMicrophoneAndOtherPermissionsAreDenied() {
        listOf(
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
            PermissionRequest.RESOURCE_MIDI_SYSEX,
        ).forEach { resource ->
            val request = RecordingPermissionRequest(arrayOf(resource))
            onMain { chrome.onPermissionRequest(request) }
            assertTrue(resource, request.denied)
            assertNull(resource, request.granted)
        }
    }

    @Test
    fun theFileChooserIsCancelled() {
        val answers = mutableListOf<Array<Uri>?>()
        val handled = onMain { chrome.onShowFileChooser(view, { answers += it }, null) }

        assertTrue(handled)
        assertEquals(1, answers.size)
        assertNull(answers.single())
    }

    @Test
    fun downloadsAreRefusedAndReported() {
        val url = TEST_ORIGIN.url + "/bundle.zip"
        onMain { checkNotNull(view.downloads).onDownloadStart(url, "agent", "attachment", "application/zip", 10L) }

        assertEquals(listOf(url), host.refused())
    }

    @Test
    fun clientCertificatesAreNeverSent() {
        val request = RecordingCertRequest()
        onMain { client.onReceivedClientCertRequest(view, request) }

        assertTrue(request.cancelled)
        assertFalse(request.proceeded)
    }

    /**
     * The platform hides the constructors of [HttpAuthHandler] and [SslErrorHandler], so a test cannot
     * hand the client one; [PreviewHttpAuthTest] checks HTTP authentication against a real server.
     * Here: the client answers both itself rather than leaving them to the default, which would
     * prompt or keep the request waiting.
     */
    @Test
    fun httpAuthenticationAndTlsErrorsAreAnsweredByThePreview() {
        val declared = PreviewWebViewClient::class.java.declaredMethods
        assertTrue(declared.any { it.name == "onReceivedHttpAuthRequest" && it.parameterTypes[1] == HttpAuthHandler::class.java })
        assertTrue(declared.any { it.name == "onReceivedSslError" && it.parameterTypes[1] == SslErrorHandler::class.java })
        assertTrue(declared.any { it.name == "onReceivedSslError" && it.parameterTypes[2] == SslError::class.java })
    }

    @Test
    fun webContentsDebuggingIsOffInARelease() {
        assertFalse(PreviewWebViews.webContentsDebugging(contextWithFlags(0)))
        assertTrue(PreviewWebViews.webContentsDebugging(contextWithFlags(ApplicationInfo.FLAG_DEBUGGABLE)))
    }

    @Test
    fun theConsoleIsReported() {
        val consoleSite = FakeSite(mapOf("/log" to "<script>console.warn('hello'); document.title = 'logged';</script>"))
        val logView = onMain { previewWebView(host, consoleSite) }
        try {
            loadAndWait(logView, "/log", "logged")
            waitUntil("the warning is reported") {
                host.intents.filterIsInstance<PageIntent.Report>().any {
                    it.entry.kind == ConsoleKind.Console && it.entry.level == ConsoleLevel.Warning && it.entry.text == "hello"
                }
            }
        } finally {
            onMain { PreviewWebViews.clearAndDestroy(logView) }
        }
    }

    /**
     * No Java object is handed to the page: its global names are those of a plain WebView showing
     * the same page.
     */
    @Test
    fun noJavaObjectReachesThePage() {
        val page = "<title>globals</title>"
        val globals = "Object.getOwnPropertyNames(window).sort().join(',')"
        val previewSite = FakeSite(mapOf("/g" to page))
        val preview = onMain { previewWebView(host, previewSite) }
        val plain = onMain { WebView(targetContext).apply { settings.javaScriptEnabled = true } }
        try {
            loadAndWait(preview, "/g", "globals")
            onMain { plain.loadDataWithBaseURL(TEST_ORIGIN.url + "/g", page, "text/html", "utf-8", null) }
            waitUntil("the plain page loads") { plain.title == "globals" }

            assertEquals(evaluate(plain, globals), evaluate(preview, globals))
        } finally {
            onMain {
                PreviewWebViews.clearAndDestroy(preview)
                plain.destroy()
            }
        }
    }

    @Test
    fun aDeadRendererIsHandedToTheHostInsteadOfEndingTheApp() {
        val detail = object : RenderProcessGoneDetail() {
            override fun didCrash(): Boolean = true

            override fun rendererPriorityAtExit(): Int = WebView.RENDERER_PRIORITY_BOUND
        }

        assertTrue(onMain { client.onRenderProcessGone(view, detail) })
        assertEquals(listOf<WebView>(view), host.renderersGone.toList())
    }

    private fun contextWithFlags(flags: Int): Context = object : ContextWrapper(targetContext) {
        override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(targetContext.applicationInfo).also { it.flags = flags }
    }

    private class RecordingPermissionRequest(private val resources: Array<String>) : PermissionRequest() {
        var denied = false
        var granted: Array<String>? = null

        override fun getOrigin(): Uri = Uri.parse(TEST_ORIGIN.url + "/")

        override fun getResources(): Array<String> = resources

        override fun grant(resources: Array<String>?) {
            granted = resources
        }

        override fun deny() {
            denied = true
        }
    }

    private class RecordingCertRequest : ClientCertRequest() {
        var cancelled = false
        var proceeded = false

        override fun getKeyTypes(): Array<String>? = null

        override fun getPrincipals(): Array<Principal>? = null

        override fun getHost(): String = PreviewOrigin.HOST

        override fun getPort(): Int = TEST_ORIGIN.bridgePort

        override fun proceed(privateKey: PrivateKey?, chain: Array<X509Certificate>?) {
            proceeded = true
        }

        override fun ignore() = Unit

        override fun cancel() {
            cancelled = true
        }
    }
}
