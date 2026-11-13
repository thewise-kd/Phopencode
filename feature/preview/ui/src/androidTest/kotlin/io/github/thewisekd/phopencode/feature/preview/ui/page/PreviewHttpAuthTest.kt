package io.github.thewisekd.phopencode.feature.preview.ui.page

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.thewisekd.phopencode.feature.preview.logic.contract.LoadError
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PageIntent
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewOrigin
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * HTTP authentication is cancelled, never answered: against a real server on the phone's loopback
 * that asks for Basic credentials, the WebView shows the server's 401 page and no request carries
 * credentials. Skipped where the test app may not speak clear text to `127.0.0.1`.
 */
@RunWith(AndroidJUnit4::class)
class PreviewHttpAuthTest {
    @Test
    fun httpAuthenticationIsCancelled() {
        assumeTrue(
            "the test app may not use clear text to 127.0.0.1",
            NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(PreviewOrigin.HOST),
        )
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 4, loopback).use { server ->
            val heads = CopyOnWriteArrayList<List<String>>()
            thread(isDaemon = true) { answerWithChallenge(server, heads) }
            val host = RecordingHost(PreviewOrigin(server.localPort))
            val view = onMain { PreviewWebViews.create(targetContext, host) }
            try {
                onMain { view.loadUrl(PreviewOrigin(server.localPort).url + "/") }
                waitUntil("the 401 page shows") { view.title == "unauthorised" }

                assertTrue(heads.isNotEmpty())
                assertTrue(heads.none { head -> head.any { it.startsWith("authorization:", ignoreCase = true) } })
                assertTrue(host.intents.contains(PageIntent.LoadFailed(LoadError.HttpStatus(401))))
            } finally {
                onMain { PreviewWebViews.clearAndDestroy(view) }
            }
        }
    }

    /** Answers every request with a Basic challenge and a small page, recording each request head. */
    private fun answerWithChallenge(server: ServerSocket, heads: MutableList<List<String>>) {
        val body = "<title>unauthorised</title>".toByteArray()
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                return
            }
            socket.use {
                val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val head = generateSequence { reader.readLine() }.takeWhile { line -> line.isNotEmpty() }.toList()
                heads += head
                val response = "HTTP/1.1 401 Unauthorized\r\n" +
                    "WWW-Authenticate: Basic realm=\"preview\"\r\n" +
                    "Content-Type: text/html\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                it.getOutputStream().apply {
                    write(response.toByteArray(Charsets.ISO_8859_1))
                    write(body)
                    flush()
                }
            }
        }
    }
}
