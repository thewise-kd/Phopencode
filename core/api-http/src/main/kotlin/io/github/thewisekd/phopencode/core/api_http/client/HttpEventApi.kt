package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.api_http.event.decode.EventDecoder
import io.github.thewisekd.phopencode.core.api_http.event.stream.ReconnectPolicy
import io.github.thewisekd.phopencode.core.api_http.event.stream.StreamSignal
import io.github.thewisekd.phopencode.core.api_http.event.stream.eventStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * [EventApi] over `GET /api/event`: the raw stream of
 * [io.github.thewisekd.phopencode.core.api_http.event.stream.EventStream], decoded by [EventDecoder].
 *
 * - [ServerEvent.Connected] is emitted when a connection is up, whatever [events]' types. The
 *   server's own `server.connected`, its first event on every connection, is dropped, so that each
 *   connection gives exactly one [ServerEvent.Connected].
 * - [ServerEvent.Disconnected] is emitted once per drop: for the first failure after a connection,
 *   and for the first one if the flow starts with failures. The failed attempts that follow while it
 *   reconnects emit nothing more.
 * - The stream runs on [context], not the collector's, so that its timers keep real time: a
 *   collector on a test dispatcher or a busy main thread does not trip the silence timeout.
 *
 * Closing the transport ends every flow with [io.github.thewisekd.phopencode.core.api.ApiError.Closed].
 */
internal class HttpEventApi(
    private val transport: HttpTransport,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val context: CoroutineContext = Dispatchers.IO,
) : EventApi {
    override fun events(types: Set<String>): Flow<ServerEvent> {
        // A copy: the caller's set may change before or while the flow is collected.
        val wanted = types.toSet()
        return flow {
            var dropReported = false
            transport.eventStream(wanted, policy).signals().collect { signal ->
                when (signal) {
                    StreamSignal.Connected -> {
                        dropReported = false
                        emit(ServerEvent.Connected())
                    }
                    is StreamSignal.Event -> {
                        val event = EventDecoder.decode(signal.event.type, signal.event.data, signal.event.id)
                        if (event !is ServerEvent.Connected) emit(event)
                    }
                    is StreamSignal.Disconnected -> if (!dropReported) {
                        dropReported = true
                        emit(ServerEvent.Disconnected(signal.reason))
                    }
                }
            }
        }
            .flowOn(context)
            // No buffer between the stream and the collector beyond the stream's own.
            .buffer(Channel.RENDEZVOUS)
    }
}
