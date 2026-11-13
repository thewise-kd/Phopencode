package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent

/**
 * The one place the preview's WebView is made and unmade (P13.13, D9 option (b)). The app has no
 * other WebView, so the process-wide stores cleared here (cookies, web storage, the HTTP cache) hold
 * the preview's data only.
 *
 * The WebView is isolated:
 * - JavaScript runs, as the dev server's pages need it; nothing of the app is handed to it: no
 *   `addJavascriptInterface`, no message channel, no other bridge.
 * - No file or content access, from the page or from `file:` URLs.
 * - Mixed content is never allowed.
 * - Geolocation is off, and every permission request (camera, microphone, the rest) is denied.
 * - The file chooser and downloads are refused; no pop-up windows.
 * - Form data and passwords are not saved; the autofill service sees nothing of the page.
 * - HTTP authentication and TLS errors are cancelled.
 * - Third-party cookies are refused.
 * - Web contents debugging is on only in a debuggable build of the app, never in a release.
 * - Requests off the bridge's origin are refused before they leave the WebView ([PreviewWebViewClient]).
 * - WebRTC is taken out of every frame before the page's scripts run ([WebRtcBlock]); a system
 *   WebView that cannot do that gets no preview WebView at all ([WebViewTooOld]).
 */
internal object PreviewWebViews {
    /**
     * A new isolated WebView whose callbacks report to [host]. Main thread.
     *
     * [WebRtcBlock.SCRIPT] is registered in it once, here, before anything else is done to it, so it
     * is in place before the first `loadUrl`, which only the caller can make.
     *
     * @param passThrough Answers requests on the origin; see [PreviewWebViewClient].
     * @param scripts Registers the WebRTC block; the Robolectric tests stand in for the system WebView.
     * @param newView Makes the bare view; the instrumented tests record what it is asked to clear.
     * @throws WebViewTooOld when the system WebView cannot run [WebRtcBlock.SCRIPT] at document
     *   start; then no WebView is left behind.
     */
    fun create(
        context: Context,
        host: PreviewWebHost,
        passThrough: (WebResourceRequest) -> WebResourceResponse? = { null },
        scripts: DocumentStartScripts = SystemDocumentStartScripts,
        newView: (Context) -> PreviewWebView = ::PreviewWebView,
    ): PreviewWebView {
        if (!scripts.supported) throw WebViewTooOld()
        WebView.setWebContentsDebuggingEnabled(webContentsDebugging(context))
        val view = newView(context)
        try {
            scripts.add(view, WebRtcBlock.SCRIPT, WebRtcBlock.ORIGIN_RULES)
        } catch (unsupported: UnsupportedOperationException) {
            view.destroy()
            throw WebViewTooOld(unsupported)
        } catch (failure: RuntimeException) {
            view.destroy()
            throw failure
        }
        isolate(view.settings)
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        // The page's history and form state never go into the activity's saved state.
        view.isSaveEnabled = false
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webViewClient = PreviewWebViewClient(host, passThrough)
        view.webChromeClient = PreviewChromeClient(host)
        view.setDownloadListener(PreviewDownloads(host))
        view.onPull = host::pulled
        view.onPullRelease = { host.send(PageIntent.Reload) }
        return view
    }

    /** Whether the WebView may be debugged: only when the app itself is a debuggable build. */
    fun webContentsDebugging(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @Suppress("DEPRECATION")
    private fun isolate(settings: WebSettings) = with(settings) {
        javaScriptEnabled = true
        // Dev servers' pages keep state in localStorage; it is cleared when the preview closes.
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setGeolocationEnabled(false)
        setSupportMultipleWindows(false)
        javaScriptCanOpenWindowsAutomatically = false
        saveFormData = false
    }

    /**
     * Clears what the preview left: [view]'s history, the HTTP cache, form data, TLS choices, every
     * cookie, all web storage, and saved HTTP authentication. Main thread.
     */
    @Suppress("DEPRECATION")
    fun clearData(view: WebView) {
        view.stopLoading()
        view.clearHistory()
        view.clearCache(true)
        view.clearFormData()
        view.clearSslPreferences()
        view.clearMatches()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            removeSessionCookies(null)
            flush()
        }
        WebViewDatabase.getInstance(view.context).apply {
            clearHttpAuthUsernamePassword()
            clearFormData()
        }
    }

    /** [clearData], then takes [view] off the screen and destroys it. Main thread. */
    fun clearAndDestroy(view: WebView) {
        clearData(view)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }
}

/**
 * Registers scripts that run at document start in a WebView. The system WebView's own, through
 * androidx.webkit, is [SystemDocumentStartScripts]; the Robolectric tests stand in for it, since
 * Robolectric has no WebView to ask.
 */
internal interface DocumentStartScripts {
    /** Whether the system WebView runs scripts at document start. */
    val supported: Boolean

    /**
     * Registers [script] in [view] for every frame whose origin matches [originRules]. Main thread,
     * and only when [supported]; throws [UnsupportedOperationException] or [WebViewTooOld] if not.
     */
    fun add(view: WebView, script: String, originRules: Set<String>)
}

/** The system WebView's document start scripts. */
internal object SystemDocumentStartScripts : DocumentStartScripts {
    override val supported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    override fun add(view: WebView, script: String, originRules: Set<String>) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, script, originRules)
        } else {
            throw WebViewTooOld()
        }
    }
}

/** The system WebView cannot run [WebRtcBlock.SCRIPT] ahead of a page, so no preview WebView is made. */
internal class WebViewTooOld(cause: Throwable? = null) :
    IllegalStateException("The system WebView cannot run scripts at document start", cause)
