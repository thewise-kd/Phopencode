package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.net.Uri
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin

/**
 * Which addresses the preview's WebView may fetch: the bridge's origin ([PreviewOrigin], plain
 * `http` on `127.0.0.1` and the bridge's port, with no user info), and the few that never leave the
 * WebView (`data:`, `about:blank`, `about:srcdoc`, and `blob:` made on the origin). Everything else,
 * another port of the phone's loopback included, is refused, never fetched by the phone.
 *
 * The WebView hands its callbacks canonical URLs, so [Uri]'s reading of the host and port is the
 * WebView's.
 */
internal object RequestGate {
    private const val HTTP = "http"
    private const val BLOB = "blob:"
    private val IN_WEB_VIEW = setOf("about:blank", "about:srcdoc")

    /** [url] is on [origin]; false while there is none. */
    fun isOnOrigin(url: String, origin: PreviewOrigin?): Boolean {
        if (origin == null) return false
        val uri = Uri.parse(url)
        return uri.scheme.equals(HTTP, ignoreCase = true) &&
            uri.encodedUserInfo == null &&
            uri.host == PreviewOrigin.HOST &&
            uri.port == origin.bridgePort
    }

    /** The WebView may fetch [url]: it is on [origin], or never leaves the WebView. */
    fun mayFetch(url: String, origin: PreviewOrigin?): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
        return when (scheme) {
            HTTP -> isOnOrigin(url, origin)
            "data" -> true
            "about" -> url.lowercase() in IN_WEB_VIEW
            "blob" -> isOnOrigin(url.substring(BLOB.length), origin)
            else -> false
        }
    }
}
