package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin

/**
 * Rewrites heads between the preview origin (`http://127.0.0.1:<bridgePort>` on the phone) and the
 * dev server (`localhost:<devPort>` on the server's loopback), so the dev server sees the requests
 * a browser on the server would send and the WebView sees answers on its own origin.
 */
internal class HeadRewriter(bridgePort: Int, private val devPort: Int, private val secret: BridgeSecret) {
    private val previewOrigin = PreviewOrigin(bridgePort).url
    private val devAuthority = "localhost:$devPort"
    private val devOrigin = "http://$devAuthority"

    /**
     * Keeps everything the page loads on the preview origin. The WebView does not ask
     * `shouldInterceptRequest` again when an allowed sub-resource is redirected, so this policy is
     * what refuses a redirect off the origin. Browsers enforce it beside the page's own policy.
     *
     * - `default-src`: the origin, `data:` and `blob:`; every fetch directive not named here (images,
     *   fonts, media, frames, workers, objects, manifests) falls back to it.
     * - `script-src`: as `default-src`, plus `'unsafe-inline'` and `'unsafe-eval'`, which Vite and
     *   webpack dev servers rely on.
     * - `style-src`: as `default-src`, plus `'unsafe-inline'` for injected styles.
     * - `connect-src`: the origin over http and ws, so `fetch`, `XMLHttpRequest`, `EventSource` and
     *   the hot-reload WebSocket stay on it.
     * - `form-action`: the origin only; a form post is a navigation that no fetch directive covers.
     */
    val policy = run {
        val ws = "ws://${PreviewOrigin.HOST}:$bridgePort"
        "default-src $previewOrigin data: blob:; " +
            "script-src $previewOrigin 'unsafe-inline' 'unsafe-eval' data: blob:; " +
            "style-src $previewOrigin 'unsafe-inline' data: blob:; " +
            "connect-src $previewOrigin $ws; " +
            "form-action $previewOrigin"
    }

    /**
     * The request head for the dev server: `Host` names `localhost:<devPort>`, `Origin` and `Referer`
     * on the preview origin move to the dev server's, the bridge's cookie is dropped, and so is
     * `Expect`, which the bridge answers itself.
     */
    fun request(head: RequestHead): ByteArray {
        val headers = ArrayList<Header>(head.headers.size + 1)
        headers += Header("Host", devAuthority)
        head.headers.forEach { header ->
            when (header.name.lowercase()) {
                "host", "expect", "proxy-connection", "cookie" -> Unit
                "origin" -> headers += Header(header.name, if (header.value.equals(previewOrigin, ignoreCase = true)) devOrigin else header.value)
                "referer" -> headers += Header(header.name, toDev(header.value))
                else -> headers += header
            }
        }
        secret.forwardedCookies(head.headers)?.let { headers += Header("Cookie", it) }
        return headBytes("${head.method} ${head.target} ${head.version}", headers)
    }

    /**
     * The response head for the WebView: loopback `Location`s on the dev port point back to the
     * preview origin, a `Set-Cookie` of the bridge's own cookie is dropped, the policy is added to
     * every final response, and [close] ends the connection after it.
     */
    fun response(head: ResponseHead, close: Boolean): ByteArray {
        val headers = ArrayList<Header>(head.headers.size + 2)
        val final = head.status !in INFORMATIONAL
        head.headers.forEach { header ->
            when (header.name.lowercase()) {
                "location" -> headers += Header(header.name, location(header.value))
                "set-cookie" -> if (!secret.setsBridgeCookie(header.value)) headers += Header(header.name, hostOnly(header.value))
                "connection", "keep-alive" -> if (!close) headers += header
                else -> headers += header
            }
        }
        if (final && head.status != SWITCHING_PROTOCOLS) headers += Header("Content-Security-Policy", policy)
        if (close) headers += Header("Connection", "close")
        val reason = if (head.reason.isEmpty()) "" else " ${head.reason}"
        return headBytes("${head.version} ${head.status}$reason", headers)
    }

    /**
     * An answer of the bridge itself, with a short plain-text body naming [reason]. For a
     * [headRequest] the head is the same, `Content-Length` included, and the body is left out.
     */
    fun own(
        status: Int,
        reason: String,
        extra: List<Header> = emptyList(),
        close: Boolean = true,
        headRequest: Boolean = false,
    ): ByteArray {
        val body = if (status == FOUND || status == CONTINUE) "" else reason
        val headers = ArrayList<Header>()
        headers += extra
        if (status != CONTINUE) {
            headers += Header("Content-Type", "text/plain; charset=utf-8")
            headers += Header("Content-Length", body.toByteArray().size.toString())
            headers += Header("Cache-Control", "no-store")
            headers += Header("Content-Security-Policy", policy)
            if (close) headers += Header("Connection", "close")
        }
        val head = headBytes("HTTP/1.1 $status $reason", headers)
        return if (headRequest) head else head + body.toByteArray()
    }

    /**
     * A `Set-Cookie` value without a `Domain` attribute that names a loopback host the dev server
     * knows itself by (`localhost`, `127.0.0.1`, `[::1]`): the WebView on `127.0.0.1` would refuse
     * such a cookie, so it becomes host-only on the preview origin. Every other attribute, and any
     * other domain, stays as it was.
     */
    private fun hostOnly(setCookie: String): String {
        val parts = setCookie.split(';')
        val kept = listOf(parts.first()) + parts.drop(1).filterNot { attribute ->
            attribute.substringBefore('=').trim().equals("Domain", ignoreCase = true) &&
                attribute.substringAfter('=', "").trim().removePrefix(".").lowercase() in COOKIE_LOOPBACK_DOMAINS
        }
        return if (kept.size == parts.size) setCookie else kept.joinToString(";")
    }

    /** [value] on the preview origin moved to the dev server's; anything else unchanged. */
    private fun toDev(value: String): String = when {
        value.equals(previewOrigin, ignoreCase = true) -> devOrigin
        value.startsWith("$previewOrigin/", ignoreCase = true) -> devOrigin + value.substring(previewOrigin.length)
        else -> value
    }

    /**
     * A `Location` that names `localhost`, `127.0.0.1` or `[::1]` on the dev port, over `http` or
     * scheme-relative, mapped to the preview origin; anything else unchanged.
     */
    fun location(value: String): String {
        val rest = when {
            value.startsWith("http://", ignoreCase = true) -> value.substring("http://".length)
            value.startsWith("//") -> value.substring(2)
            else -> return value
        }
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
        val (host, port) = splitAuthority(authority) ?: return value
        if (host.lowercase() !in LOOPBACK_HOSTS) return value
        if ((port ?: DEFAULT_HTTP_PORT) != devPort) return value
        return previewOrigin + rest.substring(end)
    }

    /** Host and port of [authority]; null with user info or a port that is not a number. */
    private fun splitAuthority(authority: String): Pair<String, Int?>? {
        if ('@' in authority) return null
        val hostEnd = if (authority.startsWith("[")) authority.indexOf(']') + 1 else authority.indexOf(':').let { if (it < 0) authority.length else it }
        if (hostEnd <= 0) return null
        val host = authority.substring(0, hostEnd)
        val portText = authority.substring(hostEnd)
        if (portText.isEmpty()) return host to null
        if (!portText.startsWith(":")) return null
        val digits = portText.substring(1)
        if (digits.isEmpty()) return host to null
        if (digits.length > MAX_PORT_DIGITS || !digits.all { it in '0'..'9' }) return null
        return host to digits.toInt()
    }

    override fun toString(): String = "HeadRewriter"

    private companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
        val COOKIE_LOOPBACK_DOMAINS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
        val INFORMATIONAL = 100..199
        const val SWITCHING_PROTOCOLS = 101
        const val CONTINUE = 100
        const val FOUND = 302
        const val DEFAULT_HTTP_PORT = 80
        const val MAX_PORT_DIGITS = 5
    }
}
