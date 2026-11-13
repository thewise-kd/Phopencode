package io.github.thewisekd.phopencode.feature.preview.logic.transport

import io.github.thewisekd.phopencode.feature.preview.logic.contract.BridgeProblem
import io.github.thewisekd.phopencode.feature.preview.logic.contract.PreviewTunnel
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * One client connection to the bridge, served on its own thread. Each request head is read within
 * [BridgeLimits.headTimeoutMillis] and must carry the secret; until it does, nothing is sent to the
 * tunnel and no tunnel socket is made. Allowed requests go to the dev server over one tunnel socket
 * per client connection, kept alive while both sides allow it; a `101` answer turns both into a
 * raw relay.
 *
 * [hold] registers a socket to close with the bridge and refuses once the bridge is closed;
 * [release] forgets it. Nothing here is logged, and no request or response byte reaches a problem.
 */
internal class BridgeConnection(
    private val client: Socket,
    private val tunnel: PreviewTunnel,
    private val secret: BridgeSecret,
    private val rewriter: HeadRewriter,
    private val limits: BridgeLimits,
    private val report: (BridgeProblem) -> Unit,
    private val hold: (Socket) -> Boolean,
    private val release: (Socket) -> Unit,
    private val watchdog: WriteWatchdog,
) {
    private val fromClient = HttpInput(client.getInputStream())

    /** A client that stops reading is cut after [BridgeLimits.clientWriteStallMillis] of a blocked write. */
    private val watchedClient = watchdog.watch(client.getOutputStream(), ::abort)
    private val toClient = BufferedOutputStream(watchedClient, BUFFER)

    @Volatile private var upstream: Upstream? = null

    /** The request body being sent, if any; while it runs, only its thread reads the client. */
    @Volatile private var activeUpload: Upload? = null

    /** Whether the request being answered is a HEAD, whose answers from the bridge carry no body. */
    private var answeringHead = false

    /**
     * One tunnel socket. Its output is watched like the client's: a dev server that stops reading
     * closes both sockets after [BridgeLimits.clientWriteStallMillis] of a blocked write.
     */
    private inner class Upstream(val socket: Socket) {
        val input = HttpInput(socket.getInputStream())
        val watched = watchdog.watch(socket.getOutputStream()) {
            runCatching { socket.close() }
            runCatching { client.close() }
        }
        val output: OutputStream = BufferedOutputStream(watched, BUFFER)
        var used = false
    }

    /** Serves requests until either side ends, a limit is passed or the bridge closes. Never throws. */
    fun serve() {
        try {
            do {
                val more = exchange()
            } while (more)
        } catch (_: Exception) {
            // A broken, timed out or closed connection ends here; it carries nothing to report.
        } finally {
            watchdog.forget(watchedClient)
            upstream?.let(::drop)
            runCatching { client.close() }
        }
    }

    /** From the watchdog: closing both sockets ends the blocked write and every other wait. */
    private fun abort() {
        runCatching { client.close() }
        upstream?.let { runCatching { it.socket.close() } }
    }

    /** One request and its response; false when the connection ends after it. */
    private fun exchange(): Boolean {
        answeringHead = false
        val lines = try {
            fromClient.readHead(limits.maxHeadBytes, limits.maxHeaders, headDeadline())
        } catch (violation: HttpViolation) {
            return refuse(violation)
        } ?: return false
        val request = try {
            parseRequest(lines)
        } catch (violation: HttpViolation) {
            return refuse(violation)
        }
        answeringHead = request.method == "HEAD"
        // Only HTTP/1.1, which the WebView always speaks: responses are relayed with their framing
        // as is, and an HTTP/1.0 client cannot read chunked encoding.
        if (request.version != HTTP_11) return refuse(VERSION_NOT_SUPPORTED, "HTTP Version Not Supported")
        when (val access = secret.check(request)) {
            BridgeSecret.Access.Denied -> return refuse(FORBIDDEN, "Forbidden")
            is BridgeSecret.Access.Granted -> access.withoutSecret?.let { return enter(request, it) }
        }
        when {
            request.method == "CONNECT" -> return refuse(METHOD_NOT_ALLOWED, "Method Not Allowed")
            !request.target.startsWith("/") && !(request.target == "*" && request.method == "OPTIONS") ->
                return refuse(BAD_REQUEST, "Bad Request")
        }
        val expect = request.headers.values("Expect")
        if (expect.any { !it.equals("100-continue", ignoreCase = true) }) return refuse(EXPECTATION_FAILED, "Expectation Failed")
        val framing = try {
            requestFraming(request.headers)
        } catch (violation: HttpViolation) {
            return refuse(violation)
        }
        if (framing is Framing.Length && framing.bytes > limits.maxRequestBytes) return refuse(TOO_LARGE, "Content Too Large")
        return relay(request, framing, continueFirst = expect.isNotEmpty())
    }

    /** The entry URL: the secret moves into the cookie and the WebView goes on without it in the address. */
    private fun enter(request: RequestHead, target: String): Boolean {
        val hasBody = request.headers.values("Content-Length").any { it != "0" } || request.headers.values("Transfer-Encoding").isNotEmpty()
        val close = hasBody || closesAfter(request)
        val extra = listOf(
            Header("Location", target),
            Header("Set-Cookie", secret.setCookie()),
            Header("Referrer-Policy", "no-referrer"),
        )
        toClient.write(rewriter.own(FOUND, "Found", extra, close, headRequest = answeringHead))
        toClient.flush()
        if (close) lingeringClose()
        return !close
    }

    private fun relay(request: RequestHead, framing: Framing, continueFirst: Boolean): Boolean {
        val head = rewriter.request(request)
        val upgrade = request.headers.values("Upgrade").isNotEmpty() && "upgrade" in request.headers.tokens("Connection")
        while (true) {
            val link = upstream ?: connect() ?: return false
            upstream = link
            // A kept-alive dev server connection may have been closed since its last answer, as Node
            // does after five idle seconds. A safe request without a body is then sent again, once,
            // and only when not a byte of an answer came: a POST or DELETE may have been applied.
            val retryable = link.used && framing == Framing.None && request.method in SAFE_METHODS && !link.input.hasBuffered
            val receivedBefore = link.input.received
            try {
                link.output.write(head)
                link.output.flush()
            } catch (failure: IOException) {
                if (retryable) {
                    reconnect(link)
                    continue
                }
                return upstreamFailed(null)
            }
            link.used = true
            var upload: Upload? = null
            if (framing != Framing.None) {
                if (continueFirst) {
                    toClient.write(rewriter.own(CONTINUE, "Continue"))
                    toClient.flush()
                }
                upload = Upload(framing, link).also { activeUpload = it }
            }
            link.socket.soTimeout = limits.upstreamIdleMillis
            val lines = try {
                link.input.readHead(limits.maxHeadBytes, limits.maxHeaders)
            } catch (violation: HttpViolation) {
                return badResponse(violation)
            } catch (timeout: SocketTimeoutException) {
                return refuse(GATEWAY_TIMEOUT, "Gateway Timeout")
            } catch (failure: IOException) {
                if (retryable && link.input.received == receivedBefore) {
                    reconnect(link)
                    continue
                }
                return upstreamFailed(upload)
            }
            if (lines == null) {
                if (retryable) {
                    reconnect(link)
                    continue
                }
                return upstreamFailed(upload)
            }
            return respond(request, link, lines, upgrade, upload)
        }
    }

    /**
     * Sends a request body to the dev server on its own thread, while the connection's thread reads
     * the answer: a dev server that answers early (a 413, say) and stops reading must not block the
     * bridge. A write that blocks for good ends when the answer is relayed, or when reading it times
     * out, because the tunnel socket closes then. A failure is kept in [failure]. A failed write to
     * the dev server only half-closes the tunnel socket, so an answer it already sent is still read
     * and relayed; a failure on the client's side closes the tunnel socket, which ends the wait.
     */
    private inner class Upload(framing: Framing, private val link: Upstream) {
        private val finished = CountDownLatch(1)

        @Volatile var failure: Exception? = null
            private set

        init {
            client.soTimeout = limits.clientIdleMillis
            thread(isDaemon = true, name = UPLOAD_THREAD) {
                try {
                    relayBody(framing, fromClient, UpstreamOutput(link.output), Budget(limits.maxRequestBytes), limits.maxHeaders)
                } catch (error: UpstreamWriteFailed) {
                    // The dev server stopped reading, often because it already answered (a 413,
                    // say). Uploading stops, but the socket stays open so that answer can still be
                    // read; reading it keeps its own timeout.
                    failure = error
                    runCatching { link.socket.shutdownOutput() }
                } catch (error: Exception) {
                    failure = error
                    runCatching { link.socket.close() }
                } finally {
                    finished.countDown()
                }
            }
        }

        /** Whether the whole body went out, waiting at most [millis] for it. */
        fun completed(millis: Long): Boolean = finished.await(millis, TimeUnit.MILLISECONDS) && failure == null
    }

    /** A write to the dev server failed, as opposed to a read from the client. */
    private class UpstreamWriteFailed : IOException("Upstream write failed")

    /** Tells the upload's failed writes to the dev server apart from failures reading the client. */
    private class UpstreamOutput(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = guarded { out.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = guarded { out.write(b, off, len) }

        override fun flush() = guarded { out.flush() }

        private inline fun guarded(block: () -> Unit) {
            try {
                block()
            } catch (_: IOException) {
                throw UpstreamWriteFailed()
            }
        }
    }

    private fun respond(request: RequestHead, link: Upstream, first: List<String>, upgrade: Boolean, upload: Upload?): Boolean {
        var response = try {
            parseResponse(first)
        } catch (violation: HttpViolation) {
            return badResponse(violation)
        }
        while (response.status in INFORMATIONAL && response.status != SWITCHING_PROTOCOLS) {
            toClient.write(rewriter.response(response, close = false))
            toClient.flush()
            val lines = try {
                link.input.readHead(limits.maxHeadBytes, limits.maxHeaders)
            } catch (violation: HttpViolation) {
                return badResponse(violation)
            } catch (timeout: SocketTimeoutException) {
                return refuse(GATEWAY_TIMEOUT, "Gateway Timeout")
            } catch (failure: IOException) {
                return upstreamFailed(upload)
            } ?: return upstreamFailed(upload)
            response = try {
                parseResponse(lines)
            } catch (violation: HttpViolation) {
                return badResponse(violation)
            }
        }
        if (response.status == SWITCHING_PROTOCOLS) {
            // The raw relay reads the client from here on, so an upload must be over by now.
            if (!upgrade || (upload != null && !upload.completed(0))) return badResponse(HttpViolation(HttpViolation.Kind.Malformed))
            toClient.write(rewriter.response(response, close = false))
            toClient.flush()
            rawRelay(link)
            return false
        }
        val framing = try {
            responseFraming(request.method, response)
        } catch (violation: HttpViolation) {
            return badResponse(violation)
        }
        if (framing is Framing.Length && framing.bytes > limits.maxResponseBytes) {
            return badResponse(HttpViolation(HttpViolation.Kind.BodyTooLarge))
        }
        val close = closesAfter(request) || closesAfter(response) || framing == Framing.UntilClose
        // The head goes out at once, before any body byte: an idle event stream must open now.
        // relayBody flushes after every piece of the body as well.
        toClient.write(rewriter.response(response, close))
        toClient.flush()
        try {
            relayBody(framing, link.input, toClient, Budget(limits.maxResponseBytes), limits.maxHeaders)
        } catch (violation: HttpViolation) {
            report(problemOf(violation))
            return false
        } catch (truncated: EOFException) {
            // The dev server ended before the length it declared. Returning false closes the
            // client, so the truncated body cannot pass for a whole one.
            report(if (upload?.failure == null && tunnel.end.value != null) BridgeProblem.TunnelClosed else BridgeProblem.MalformedResponse)
            return false
        } catch (failure: IOException) {
            if (upload?.failure == null && tunnel.end.value != null) report(BridgeProblem.TunnelClosed)
            return false
        }
        // An early answer leaves the rest of the body unread on the client side: the connection
        // cannot carry another request then.
        if (close) return false
        return upload == null || upload.completed(UPLOAD_GRACE_MILLIS)
    }

    /**
     * A tunnel socket to the dev server's port, named `localhost` so the server resolves it to the
     * loopback address the dev server listens on. On failure, answers 502 and reports why.
     */
    private fun connect(): Upstream? {
        val socket = try {
            tunnel.loopback.socketFactory().createSocket()
        } catch (_: Exception) {
            report(BridgeProblem.TunnelClosed)
            refuse(BAD_GATEWAY, "Bad Gateway")
            return null
        }
        if (!hold(socket)) {
            runCatching { socket.close() }
            return null
        }
        try {
            socket.connect(InetSocketAddress.createUnresolved(DEV_HOST, tunnel.port), limits.connectTimeoutMillis)
            return Upstream(socket)
        } catch (failure: Exception) {
            release(socket)
            runCatching { socket.close() }
            report(if (failure is ConnectException) BridgeProblem.NothingListens else BridgeProblem.TunnelClosed)
            refuse(BAD_GATEWAY, "Bad Gateway")
            return null
        }
    }

    private fun reconnect(link: Upstream) {
        drop(link)
        upstream = null
    }

    private fun drop(link: Upstream) {
        watchdog.forget(link.watched)
        release(link.socket)
        runCatching { link.socket.close() }
    }

    /** After a `101`: bytes flow both ways untouched until either side ends, then both close. */
    private fun rawRelay(link: Upstream) {
        client.soTimeout = 0
        link.socket.soTimeout = 0
        val up = thread(isDaemon = true, name = RELAY_THREAD) {
            try {
                pipe(fromClient, link.output)
            } catch (_: Exception) {
                // Either side ended.
            } finally {
                runCatching { link.socket.close() }
                runCatching { client.close() }
            }
        }
        try {
            pipe(link.input, toClient)
        } catch (_: Exception) {
            // Either side ended.
        } finally {
            runCatching { link.socket.close() }
            runCatching { client.close() }
            up.join(JOIN_MILLIS)
        }
    }

    private fun pipe(from: HttpInput, to: OutputStream) {
        val buffer = ByteArray(BUFFER)
        while (true) {
            val read = from.read(buffer, 0, buffer.size)
            if (read < 0) return
            to.write(buffer, 0, read)
            to.flush()
        }
    }

    /**
     * No answer came. When the upload failed first, that is why: a request body the bridge refuses
     * gets its 4xx, a client that went away gets nothing. Otherwise 502, and the tunnel's end
     * reported if that is why.
     */
    private fun upstreamFailed(upload: Upload?): Boolean {
        upload?.completed(UPLOAD_GRACE_MILLIS)
        when (val failure = upload?.failure) {
            null, is UpstreamWriteFailed -> Unit
            is HttpViolation -> return refuse(failure)
            else -> return false
        }
        if (tunnel.end.value != null) report(BridgeProblem.TunnelClosed)
        return refuse(BAD_GATEWAY, "Bad Gateway")
    }

    /** The dev server's answer cannot be relayed: reported, and 502. */
    private fun badResponse(violation: HttpViolation): Boolean {
        report(problemOf(violation))
        return refuse(BAD_GATEWAY, "Bad Gateway")
    }

    private fun problemOf(violation: HttpViolation): BridgeProblem = when (violation.kind) {
        HttpViolation.Kind.BodyTooLarge -> BridgeProblem.ResponseTooLarge
        HttpViolation.Kind.HeadTooLarge, HttpViolation.Kind.TooManyHeaders -> BridgeProblem.TooManyHeaders
        else -> BridgeProblem.MalformedResponse
    }

    /** A request the bridge will not relay; the client's own fault is never reported. */
    private fun refuse(violation: HttpViolation): Boolean = when (violation.kind) {
        HttpViolation.Kind.HeadTooLarge, HttpViolation.Kind.TooManyHeaders ->
            refuse(HEADERS_TOO_LARGE, "Request Header Fields Too Large")
        HttpViolation.Kind.BodyTooLarge -> refuse(TOO_LARGE, "Content Too Large")
        HttpViolation.Kind.NotImplemented -> refuse(NOT_IMPLEMENTED, "Not Implemented")
        HttpViolation.Kind.VersionNotSupported -> refuse(VERSION_NOT_SUPPORTED, "HTTP Version Not Supported")
        HttpViolation.Kind.Malformed -> refuse(BAD_REQUEST, "Bad Request")
    }

    /** Answers [status] and closes the connection; always false. */
    private fun refuse(status: Int, reason: String): Boolean {
        runCatching {
            toClient.write(rewriter.own(status, reason, headRequest = answeringHead))
            toClient.flush()
        }
        lingeringClose()
        return false
    }

    /**
     * Ends the connection so the client still reads the answer: output first, then what the client
     * already sent is read and dropped, so closing does not reset the connection under the answer.
     * The drop has one deadline for all its reads and a byte cap, so a client that trickles bytes
     * cannot hold the connection past [LINGER_MILLIS]. While an upload still reads the client,
     * nothing is drained here: the connection just closes.
     */
    private fun lingeringClose() {
        runCatching {
            client.shutdownOutput()
            if (activeUpload?.completed(0) == false) return@runCatching
            val deadline = System.nanoTime() + LINGER_MILLIS * NANOS_PER_MILLI
            val sink = ByteArray(BUFFER)
            var left = LINGER_BYTES
            while (left > 0) {
                val wait = (deadline - System.nanoTime()) / NANOS_PER_MILLI
                if (wait <= 0) break
                client.soTimeout = wait.toInt()
                val read = fromClient.read(sink, 0, minOf(sink.size, left))
                if (read < 0) break
                left -= read
            }
        }
        runCatching { client.close() }
    }

    /** A kept-alive deadline for the whole head, not for each read. */
    private fun headDeadline(): () -> Unit {
        val started = System.nanoTime()
        return {
            val left = limits.headTimeoutMillis - (System.nanoTime() - started) / NANOS_PER_MILLI
            if (left <= 0) throw SocketTimeoutException("Request head timed out")
            client.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    /** Requests are HTTP/1.1 only, so only `Connection: close` ends the connection. */
    private fun closesAfter(request: RequestHead): Boolean = "close" in request.headers.tokens("Connection")

    /** The dev server may answer in HTTP/1.0, which closes unless it says keep-alive. */
    private fun closesAfter(response: ResponseHead): Boolean {
        val connection = response.headers.tokens("Connection")
        return "close" in connection || (response.version == HTTP_10 && "keep-alive" !in connection)
    }

    private companion object {
        const val DEV_HOST = "localhost"
        const val HTTP_11 = "HTTP/1.1"
        const val HTTP_10 = "HTTP/1.0"
        const val BUFFER = 16 * 1024
        const val RELAY_THREAD = "phopencode-bridge-relay"
        const val JOIN_MILLIS = 1_000L
        const val LINGER_MILLIS = 200L
        const val LINGER_BYTES = 64 * 1024
        const val UPLOAD_GRACE_MILLIS = 1_000L
        const val UPLOAD_THREAD = "phopencode-bridge-upload"
        val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
        const val NANOS_PER_MILLI = 1_000_000L
        val INFORMATIONAL = 100..199
        const val CONTINUE = 100
        const val SWITCHING_PROTOCOLS = 101
        const val FOUND = 302
        const val BAD_REQUEST = 400
        const val FORBIDDEN = 403
        const val METHOD_NOT_ALLOWED = 405
        const val TOO_LARGE = 413
        const val EXPECTATION_FAILED = 417
        const val HEADERS_TOO_LARGE = 431
        const val NOT_IMPLEMENTED = 501
        const val BAD_GATEWAY = 502
        const val GATEWAY_TIMEOUT = 504
        const val VERSION_NOT_SUPPORTED = 505
    }
}
