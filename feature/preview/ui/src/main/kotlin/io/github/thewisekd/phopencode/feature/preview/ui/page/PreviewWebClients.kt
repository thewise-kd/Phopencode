package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.ClientCertRequest
import android.webkit.ConsoleMessage
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleEntry
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleKind
import io.github.thewisekd.phopencode.feature.preview.logic.contract.ConsoleLevel
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import java.io.ByteArrayInputStream

/** The status a refused request gets; the page sees a failed request, not a page. */
private const val REFUSED_STATUS = 403
private const val REFUSED_REASON = "Forbidden"
private val HTTP_STATUSES = 100..599

/** A request the WebView did not make, for the console. The address is the page's own text. */
internal fun refusedEntry(url: String) =
    ConsoleEntry(kind = ConsoleKind.RefusedRequest, level = ConsoleLevel.Error, text = url, source = null, line = null)

/**
 * The preview's [WebViewClient]: keeps the WebView on the bridge's origin and reports what happens
 * there to the page logic.
 *
 * - Every request is checked by [RequestGate] in [shouldInterceptRequest], on the WebView's network
 *   thread: one off the origin is answered here with an empty 403 and reported to the console, so the
 *   phone never fetches it. One on the origin goes to [passThrough], which in the app is the network
 *   (null), that is the bridge.
 * - Every navigation asks the page logic ([PreviewWebHost.decide]): the origin is followed; another
 *   `http(s)` address in the main frame waits for the user's confirmation and the WebView stays;
 *   everything else, and any address off the origin in a frame, is refused and reported.
 * - HTTP authentication, client certificates and TLS errors are cancelled: the preview holds no
 *   credentials and trusts no certificate a warning was raised for.
 * - Load and HTTP errors on the origin are reported; off it there are only refused requests, already
 *   reported once.
 * - A dead renderer is handed to [PreviewWebHost.renderGone] instead of ending the app.
 *
 * @param passThrough Answers a request on the origin; null lets the WebView fetch it. The
 *   instrumented tests serve pages from memory here.
 */
internal class PreviewWebViewClient(
    private val host: PreviewWebHost,
    private val passThrough: (WebResourceRequest) -> WebResourceResponse? = { null },
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (RequestGate.mayFetch(url, host.origin)) return passThrough(request)
        host.send(PageIntent.Report(refusedEntry(url)))
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            REFUSED_STATUS,
            REFUSED_REASON,
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return when (val decision = host.decide(url)) {
            NavigationDecision.Allow -> false
            is NavigationDecision.AskExternal -> {
                // A frame never asks: pages embed other sites without the user doing anything.
                if (request.isForMainFrame) {
                    host.send(PageIntent.RequestExternal(decision.link.url))
                } else {
                    host.send(PageIntent.Report(refusedEntry(url)))
                }
                true
            }
            NavigationDecision.Refuse -> {
                host.send(PageIntent.Report(refusedEntry(url)))
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        host.send(PageIntent.LoadStarted)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        host.send(PageIntent.LoadFinished(url.orEmpty()))
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        val url = request.url.toString()
        if (!RequestGate.isOnOrigin(url, host.origin)) return
        if (request.isForMainFrame) host.send(PageIntent.LoadFailed(LoadError.Network))
        host.send(PageIntent.Report(entry(ConsoleKind.LoadError, error.description?.toString().orEmpty(), url)))
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        val url = request.url.toString()
        if (!RequestGate.isOnOrigin(url, host.origin)) return
        val status = errorResponse.statusCode
        if (request.isForMainFrame) {
            val error = if (status in HTTP_STATUSES) LoadError.HttpStatus(status) else LoadError.Network
            host.send(PageIntent.LoadFailed(error))
        }
        val text = "$status ${errorResponse.reasonPhrase.orEmpty()}".trim()
        host.send(PageIntent.Report(entry(ConsoleKind.HttpError, text, url)))
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, authHost: String?, realm: String?) {
        handler.cancel()
    }

    override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
        request.cancel()
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        host.send(PageIntent.Report(entry(ConsoleKind.LoadError, "TLS error ${error.primaryError}", error.url)))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        host.renderGone(view)
        return true
    }

    private fun entry(kind: ConsoleKind, text: String, source: String?) =
        ConsoleEntry(kind = kind, level = ConsoleLevel.Error, text = text, source = source, line = null)
}

/**
 * The preview's [WebChromeClient]: refuses everything that would reach past the page, and reports
 * the page's console and progress.
 *
 * - Camera, microphone and every other permission request is denied; geolocation is denied without
 *   asking and without remembering (it is also switched off in the settings).
 * - The file chooser is cancelled at once: no file of the phone reaches the page.
 * - No new window is made; the settings also refuse multiple windows.
 * - `console.*` messages go to the page logic's console, never to the log.
 */
internal class PreviewChromeClient(private val host: PreviewWebHost) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        return true
    }

    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        host.send(
            PageIntent.Report(
                ConsoleEntry(
                    kind = ConsoleKind.Console,
                    level = consoleMessage.messageLevel().toLevel(),
                    text = consoleMessage.message().orEmpty(),
                    source = consoleMessage.sourceId()?.ifEmpty { null },
                    line = consoleMessage.lineNumber().takeIf { it > 0 },
                ),
            ),
        )
        return true
    }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        host.progress(newProgress)
    }

    private fun ConsoleMessage.MessageLevel?.toLevel(): ConsoleLevel = when (this) {
        ConsoleMessage.MessageLevel.DEBUG -> ConsoleLevel.Debug
        ConsoleMessage.MessageLevel.TIP -> ConsoleLevel.Info
        ConsoleMessage.MessageLevel.WARNING -> ConsoleLevel.Warning
        ConsoleMessage.MessageLevel.ERROR -> ConsoleLevel.Error
        ConsoleMessage.MessageLevel.LOG, null -> ConsoleLevel.Log
    }
}

/** Refuses every download: nothing the page offers is saved, and the console says what was refused. */
internal class PreviewDownloads(private val host: PreviewWebHost) : DownloadListener {
    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        host.send(PageIntent.Report(refusedEntry(url.orEmpty())))
    }
}
