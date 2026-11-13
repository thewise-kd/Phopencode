package io.github.thewisekd.phopencode.feature.preview.logic.page

import io.github.thewisekd.phopencode.feature.preview.logic.contract.ExternalLink
import io.github.thewisekd.phopencode.feature.preview.logic.contract.NavigationDecision
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import io.github.thewisekd.phopencode.feature.preview.logic.transport.BridgeSecret
import java.net.URI
import java.net.URISyntaxException

/**
 * Where the preview's WebView may go (P13.11).
 *
 * - An `http` address on the preview origin ([PreviewOrigin.HOST] on the bridge's port, with no
 *   user info) is followed.
 * - Any other `http` or `https` address is handed to another app, but only after the user has seen
 *   it in full and confirmed it.
 * - Every other scheme (`file:`, `content:`, `intent:`, `javascript:`, `data:`, `blob:`, `about:`
 *   and the rest), a relative address and anything [URI] cannot parse as a hierarchical address
 *   with a host is refused. So are addresses with whitespace or control characters, which the
 *   WebView never hands over itself.
 *
 * Pure: it never blocks, logs or changes anything, so the WebView's callbacks may call it.
 */
internal object NavigationPolicy {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")

    fun decide(origin: PreviewOrigin?, url: String): NavigationDecision {
        val uri = parseHttp(url) ?: return NavigationDecision.Refuse
        return if (origin != null && isOn(origin, uri)) {
            NavigationDecision.Allow
        } else {
            NavigationDecision.AskExternal(ExternalLink(url))
        }
    }

    /**
     * The path, query and fragment of [url] when it is on [origin], with the bridge's secret
     * parameter dropped from the query; null when it is not on [origin].
     */
    fun pathOn(origin: PreviewOrigin, url: String): String? {
        val uri = parseHttp(url)?.takeIf { isOn(origin, it) } ?: return null
        return pathOf(uri)
    }

    /** [uri]'s raw path (`/` when empty), query without the secret and fragment, each only when non-empty. */
    fun pathOf(uri: URI): String {
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = withoutSecret(uri.rawQuery)
        val fragment = uri.rawFragment
        return buildString {
            append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
            if (!fragment.isNullOrEmpty()) append('#').append(fragment)
        }
    }

    /** [query] without any `phopencode_secret` pair; null when nothing is left. */
    fun withoutSecret(query: String?): String? =
        query?.split('&')?.filter { it.substringBefore('=') != BridgeSecret.PARAM }?.joinToString("&")?.ifEmpty { null }

    /** An absolute, hierarchical `http(s)` address with a host, or null. */
    fun parseHttp(url: String): URI? {
        if (url.any { it.isWhitespace() || it.isISOControl() }) return null
        val scheme = SCHEME.find(url)?.value?.dropLast(1)?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.isOpaque || uri.host.isNullOrEmpty()) return null
        return uri
    }

    fun isOn(origin: PreviewOrigin, uri: URI): Boolean =
        uri.scheme.equals("http", ignoreCase = true) &&
            uri.rawUserInfo == null &&
            uri.host == PreviewOrigin.HOST &&
            uri.port == origin.bridgePort
}
