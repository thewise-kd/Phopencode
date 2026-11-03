package io.github.thewisekd.phopencode.core.api_http.event.stream

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okio.BufferedSource

/**
 * Opens `GET /api/event` through [transport], so the stream gets the same auth, logging and
 * [HttpTransport.close] as every other call.
 *
 * Each connection reads its body on [readerDispatcher], one blocking read at a time, and hands
 * items over through a small buffer. Closing a connection cancels its call, which breaks off a read
 * in progress, and waits for the reader to end.
 *
 * @param types The server event types to ask for; empty asks for all. Sent as one `types`
 *   parameter per type, sorted.
 * @param maxEventBytes Largest event accepted, and about the longest line; a larger one counts as
 *   [ApiError.MalformedResponse].
 */
internal class HttpSseConnector(
    private val transport: HttpTransport,
    private val types: Set<String> = emptySet(),
    private val maxEventBytes: Int = SseParser.DEFAULT_MAX_EVENT_BYTES,
    private val readerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SseConnector {
    private val readers = AtomicInteger()

    /** Readers running now; for tests. */
    val activeReaders: Int
        get() = readers.get()

    override val isClosed: Boolean
        get() = transport.isClosed

    override suspend fun open(): SseConnection {
        val request = transport.toOkHttp(
            ApiRequest(HttpMethod.GET, listOf("api", "event"), query = types.sorted().map { TYPES_PARAMETER to it }),
        ).newBuilder()
            .header("Accept", EVENT_STREAM)
            .header("Cache-Control", "no-cache")
            .build()
        val call = transport.newCall(request)
        val response = try {
            call.await()
        } catch (e: IOException) {
            throw ApiException(transport.errorFor(e))
        }
        try {
            if (!response.isSuccessful) throw ApiException(transport.errorFor(response))
            val contentType = response.body.contentType()
            if (contentType?.type != "text" || contentType.subtype != "event-stream") {
                throw ApiException(ApiError.MalformedResponse)
            }
            return Connection(call, response)
        } catch (e: Throwable) {
            response.close()
            call.cancel()
            throw e
        }
    }

    private inner class Connection(private val call: Call, private val response: Response) : SseConnection {
        private val items = Channel<SseItem>(BUFFERED_ITEMS)
        private val reader: Job = CoroutineScope(readerDispatcher).launch { read() }

        private suspend fun read() {
            readers.incrementAndGet()
            try {
                val parser = SseParser(maxEventBytes)
                val source = response.body.source()
                while (true) {
                    val line = source.readSseLine(maxEventBytes.toLong() + LINE_OVERHEAD) {
                        items.trySend(SseLineRead)
                    } ?: break
                    val item = parser.feed(line)
                    if (item != null) {
                        items.send(item)
                    } else {
                        // Only a sign of life: if the buffer is full, what is in it tells the same.
                        items.trySend(SseLineRead)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Errors are not caught by type: a reader must end the stream, never the process.
                items.close(ApiException(transport.errorFor(e)))
            } finally {
                items.close()
                response.close()
                readers.decrementAndGet()
            }
        }

        override suspend fun next(): SseItem? {
            val result = items.receiveCatching()
            result.exceptionOrNull()?.let { throw ApiException((it as? ApiException)?.error ?: ApiError.Unexpected()) }
            return result.getOrNull()
        }

        override suspend fun close() = withContext(NonCancellable) {
            call.cancel()
            reader.cancelAndJoin()
            // The reader may have been cancelled before it ever ran.
            response.close()
            items.cancel()
        }
    }

    private companion object {
        const val TYPES_PARAMETER = "types"
        const val EVENT_STREAM = "text/event-stream"

        /** Room for `data: ` and a `\r`, so a line holding a largest event still fits. */
        const val LINE_OVERHEAD: Long = 8

        /** Enough to ride out a burst while the collector is busy, small enough to cost nothing. */
        const val BUFFERED_ITEMS = 64
    }
}

/** The event stream of this transport's server; see [EventStream] and [HttpSseConnector]. */
internal fun HttpTransport.eventStream(
    types: Set<String> = emptySet(),
    policy: ReconnectPolicy = ReconnectPolicy(),
): EventStream = EventStream(HttpSseConnector(this, types), policy)

/**
 * Reads one line ending in `\n` or `\r\n`, without its ending; null at the end of the body. Bytes
 * after the last line ending are dropped, as the spec drops an event the body cut off.
 *
 * Calls [onProgress] each time more bytes of a line still unfinished arrive, so that a long line on
 * a slow link is not taken for silence.
 */
private fun BufferedSource.readSseLine(limit: Long, onProgress: () -> Unit): String? {
    var scanned = 0L
    var waited = false
    while (true) {
        val end = minOf(buffer.size, limit + 1)
        val newline = buffer.indexOf('\n'.code.toByte(), scanned, end)
        if (newline != -1L) {
            val line = readUtf8(newline)
            skip(1)
            return line.removeSuffix("\r")
        }
        if (buffer.size > limit) throw ApiException(ApiError.MalformedResponse)
        scanned = end
        if (waited) onProgress()
        // Blocks until at least one more byte is here, or the body ends.
        if (!request(buffer.size + 1)) return null
        waited = true
    }
}

/** Sends this call; cancelling the caller cancels it, and a reply that comes too late is closed. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
