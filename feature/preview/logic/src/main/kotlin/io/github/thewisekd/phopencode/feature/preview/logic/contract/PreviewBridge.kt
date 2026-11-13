package io.github.thewisekd.phopencode.feature.preview.logic.contract

import kotlinx.coroutines.flow.Flow

/**
 * The preview origin: the bridge on the phone's loopback (D9, option (b)). The WebView loads only
 * this origin; every other request the page makes is refused, never fetched by the phone.
 *
 * @property bridgePort The bridge's port on the phone, chosen at random by the system.
 */
data class PreviewOrigin(val bridgePort: Int) {
    init {
        requirePort(bridgePort)
    }

    /** `http://127.0.0.1:<bridgePort>`, without a trailing slash. */
    val url: String
        get() = "http://$HOST:$bridgePort"

    companion object {
        /** The bridge binds this address only, never a wildcard or another interface. */
        const val HOST = "127.0.0.1"
    }
}

/**
 * The first URL the WebView loads for one bridge: on [origin], carrying the bridge's per-session
 * secret once. The bridge answers it by setting the secret in an `HttpOnly`, `SameSite=Strict`
 * cookie; later loads need only the origin. Never prints, never goes into a route, a log or an
 * exception message.
 */
class BridgeEntry(val origin: PreviewOrigin, val url: String) {
    init {
        require(url.startsWith(origin.url + "/")) { "Entry URL is not on the preview origin" }
    }

    override fun equals(other: Any?): Boolean = other is BridgeEntry && other.origin == origin && other.url == url

    override fun hashCode(): Int = 31 * origin.hashCode() + url.hashCode()

    override fun toString(): String = "BridgeEntry(origin=$origin, url=***)"
}

/**
 * What went wrong on a relayed connection, for the page and its console. Carries no request or
 * response text. A connection refused for a missing or wrong secret is not one of these: the
 * bridge answers it with 403 and reports nothing, so other apps cannot fill the console.
 */
enum class BridgeProblem {
    /** The server refused the tunnel's channel: nothing listens on the port. */
    NothingListens,

    /** The tunnel ended; nothing more reaches the server. */
    TunnelClosed,

    /** A response was larger than the bridge relays. */
    ResponseTooLarge,

    /** A request or response head had more headers than the bridge reads. */
    TooManyHeaders,

    /** The dev server's answer was not HTTP/1.1 the bridge can relay. */
    MalformedResponse,
}

/**
 * A temporary HTTP bridge (P13.10, option (b)): a server socket bound to [PreviewOrigin.HOST] only,
 * on a random port, for as long as the preview screen is open. Every connection must present the
 * per-session secret (at least [MIN_SECRET_BITS] random bits, compared in constant time, never
 * logged) or gets 403 and is closed before any byte reaches the tunnel. Requests are rewritten for
 * the dev server and relayed through the tunnel; responses, the bridge's own answers included,
 * come back with an added `Content-Security-Policy` (P13.21) that keeps the page on [origin]:
 * `default-src` allows only [origin], `data:` and `blob:`, so every fetch directive, redirects
 * included, stays on it; `script-src` and `style-src` add only inline code (and `eval` for scripts);
 * `connect-src` allows [origin] over http and ws; `form-action` allows only [origin]. The page's own
 * policy is passed through unchanged. A `101` upgrade becomes a raw relay, so hot reload works.
 */
interface PreviewBridge : AutoCloseable {
    val origin: PreviewOrigin

    /** What the WebView loads first. */
    val entry: BridgeEntry

    /** Problems on relayed connections, as they happen; completes when the bridge closes. */
    val problems: Flow<BridgeProblem>

    /** Idempotent. Closes the server socket and every relayed connection; the tunnel stays open. */
    override fun close()

    companion object {
        const val MIN_SECRET_BITS = 128
    }
}

/** Opens a [PreviewBridge] onto a tunnel. Each bridge gets a new port and a new secret. */
interface PreviewBridgeFactory {
    /** @throws PreviewException with [PreviewFailure.BridgeFailed] when it cannot listen. */
    fun open(tunnel: PreviewTunnel): PreviewBridge
}
