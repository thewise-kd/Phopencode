package io.github.thewisekd.phopencode.feature.preview.logic.transport

/**
 * What one connection to the bridge may cost, so that a slow or hostile client, or a dev server
 * that misbehaves, cannot hang the bridge or make it buffer without bound. Nothing is ever
 * buffered whole: bodies are streamed, and these limits cap what is read.
 *
 * @property maxHeadBytes The largest request or response head, start line and headers included.
 * @property maxHeaders The most header fields in a head, and the most trailer fields after a chunked body.
 * @property maxRequestBytes The largest request body relayed to the dev server.
 * @property maxResponseBytes The largest response body relayed to the WebView. A `101` upgrade is not
 *   a response body: a hot reload socket stays open for as long as the preview.
 * @property headTimeoutMillis How long a client has to send a whole request head, counted from when
 *   the bridge starts waiting for it; this also ends an idle kept-alive connection.
 * @property clientIdleMillis The longest wait for the next bytes of a request body.
 * @property connectTimeoutMillis The longest wait for the tunnel's channel to open.
 * @property upstreamIdleMillis The longest wait for the dev server's next bytes of a response; a
 *   first build of a large project can take a while.
 * @property maxConnections The most client connections at once; more are closed as they arrive.
 * @property clientWriteStallMillis How long a write to a client may make no progress before the
 *   connection and its tunnel socket close, so a client that stops reading frees its slot.
 */
internal data class BridgeLimits(
    val maxHeadBytes: Int = 32 * 1024,
    val maxHeaders: Int = 100,
    val maxRequestBytes: Long = 64L * 1024 * 1024,
    val maxResponseBytes: Long = 64L * 1024 * 1024,
    val headTimeoutMillis: Long = 10_000,
    val clientIdleMillis: Int = 30_000,
    val connectTimeoutMillis: Int = 15_000,
    val upstreamIdleMillis: Int = 120_000,
    val maxConnections: Int = 48,
    val clientWriteStallMillis: Long = 30_000,
) {
    init {
        require(maxHeadBytes > 0 && maxHeaders > 0 && maxRequestBytes >= 0 && maxResponseBytes >= 0)
        require(headTimeoutMillis > 0 && clientIdleMillis > 0 && connectTimeoutMillis > 0 && upstreamIdleMillis > 0)
        require(clientWriteStallMillis > 0)
        require(maxConnections > 0)
    }
}
