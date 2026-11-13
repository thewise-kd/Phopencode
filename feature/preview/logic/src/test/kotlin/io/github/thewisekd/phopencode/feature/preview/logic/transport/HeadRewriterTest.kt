package io.github.thewisekd.phopencode.feature.preview.logic.transport

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals

private const val BRIDGE_PORT = 41234
private const val DEV_PORT = 5173

/**
 * The heads [HeadRewriter] writes for the WebView carry `X-DNS-Prefetch-Control: off`, once, on every
 * response: relayed or the bridge's own, interim or final; whatever the page sent for it is
 * replaced. The relayed cases through a real bridge are in [BridgeRelayTest], next to the
 * `Content-Security-Policy` ones.
 */
class HeadRewriterTest {
    private val rewriter = HeadRewriter(BRIDGE_PORT, DEV_PORT, BridgeSecret.draw(SecureRandom(), BRIDGE_PORT))

    private fun prefetch(head: ByteArray): List<String> =
        headers(String(head, Charsets.ISO_8859_1).substringBefore("\r\n\r\n"))
            .filter { it.first.equals("X-DNS-Prefetch-Control", ignoreCase = true) }
            .map { "${it.first}: ${it.second}" }

    private fun response(status: Int, vararg headers: Header) =
        ResponseHead("HTTP/1.1", status, "Reason", headers.toList())

    @Test
    fun `every relayed response turns DNS prefetching off, interim ones included`() {
        listOf(100, 101, 103, 200, 204, 302, 304, 404, 500).forEach { status ->
            listOf(false, true).forEach { close ->
                assertEquals(
                    listOf("X-DNS-Prefetch-Control: off"),
                    prefetch(rewriter.response(response(status), close)),
                    "status $status, close $close",
                )
            }
        }
    }

    @Test
    fun `the page's own value is replaced, whatever its case`() {
        val head = response(
            200,
            Header("X-DNS-Prefetch-Control", "on"),
            Header("Content-Type", "text/html"),
            Header("x-dns-prefetch-control", "ON"),
            Header("X-Dns-Prefetch-Control", "off"),
        )

        val written = rewriter.response(head, close = false)

        assertEquals(listOf("X-DNS-Prefetch-Control: off"), prefetch(written))
        assertEquals(
            listOf("Content-Type" to "text/html"),
            headers(String(written, Charsets.ISO_8859_1).substringBefore("\r\n\r\n"))
                .filter { it.first == "Content-Type" },
        )
    }

    @Test
    fun `the bridge's own answers turn DNS prefetching off too`() {
        listOf(100 to "Continue", 302 to "Found", 400 to "Bad Request", 403 to "Forbidden", 502 to "Bad Gateway")
            .forEach { (status, reason) ->
                listOf(false, true).forEach { headRequest ->
                    assertEquals(
                        listOf("X-DNS-Prefetch-Control: off"),
                        prefetch(rewriter.own(status, reason, headRequest = headRequest)),
                        "status $status, HEAD $headRequest",
                    )
                }
            }
    }

    @Test
    fun `an extra header naming DNS prefetching does not survive in the bridge's own answer`() {
        val extra = listOf(Header("Location", "/"), Header("x-dns-prefetch-control", "on"))

        assertEquals(listOf("X-DNS-Prefetch-Control: off"), prefetch(rewriter.own(302, "Found", extra)))
    }
}
