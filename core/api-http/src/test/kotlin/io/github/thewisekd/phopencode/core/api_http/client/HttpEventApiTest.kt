package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.model.SessionId
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class HttpEventApiTest {
    private val event = ServerEvent.ExecutionStarted(SessionId("session-a"))

    private class Seen(scope: CoroutineScope, flow: Flow<ServerEvent>) {
        private val items = Channel<ServerEvent>(Channel.UNLIMITED)
        // A failure is kept, not thrown: teardown closes the client, which would hide the real cause.
        @Volatile
        var failure: Throwable? = null

        val job = scope.launch {
            try {
                flow.collect { items.send(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
            }
        }

        suspend fun next(): ServerEvent = withContext(Dispatchers.Default) { withTimeout(10_000) { items.receive() } }

        /** Whatever arrived so far, without waiting. */
        fun drain(): List<ServerEvent> = generateSequence { items.tryReceive().getOrNull() }.toList()
    }

    private fun test(block: suspend TestScope.(FullHarness) -> Unit) = runTest {
        FullHarness().use { block(it) }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(10_000) { while (!condition()) delay(10) }
    }

    @Test
    fun `the server's own server connected event is not passed on, so each connection gives one Connected`() = test { h ->
        val seen = Seen(backgroundScope, h.client.events.events())
        assertEquals<ServerEvent>(ServerEvent.Connected(), seen.next())
        h.awaitEventConnections(1)
        h.serveEvent(event)
        assertEquals<ServerEvent>(event, seen.next())
        seen.job.cancel()
    }

    @Test
    fun `failed attempts after a drop give one Disconnected, then Connected`() = test { h ->
        val seen = Seen(backgroundScope, h.client.events.events())
        seen.next()
        h.awaitEventConnections(1)
        h.dropEventConnections()
        val first = assertIs<ServerEvent.Disconnected>(seen.next())
        assertEquals(ApiError.Network, first.reason)
        val before = h.server.eventRequests.get()
        waitUntil { h.server.eventRequests.get() >= before + 3 }
        assertEquals(emptyList(), seen.drain(), "refused attempts must not each emit Disconnected")
        h.acceptReconnects()
        assertEquals<ServerEvent>(ServerEvent.Connected(), seen.next())
        // A later drop is reported again.
        h.awaitEventConnections(1)
        h.dropEventConnections()
        assertIs<ServerEvent.Disconnected>(seen.next())
        seen.job.cancel()
    }

    @Test
    fun `a flow that starts with failures reports the first one only`() = test { h ->
        h.dropEventConnections()
        val seen = Seen(backgroundScope, h.client.events.events())
        assertEquals(ApiError.Server(503), assertIs<ServerEvent.Disconnected>(seen.next()).reason)
        waitUntil { h.server.eventRequests.get() >= 3 }
        assertEquals(emptyList(), seen.drain())
        h.acceptReconnects()
        assertEquals<ServerEvent>(ServerEvent.Connected(), seen.next())
        seen.job.cancel()
    }

    @Test
    fun `the stream keeps real time when collected under virtual time`() = test { h ->
        // FAST_EVENTS drops a connection after 10 s of silence. Collected on the test dispatcher,
        // its timers would fire at once while the test waits on real threads.
        val seen = Seen(backgroundScope, h.client.events.events())
        seen.next()
        withContext(Dispatchers.Default) { delay(300) }
        assertEquals(emptyList(), seen.drain())
        h.serveEvent(event)
        assertEquals<ServerEvent>(event, seen.next())
        assertEquals(1, h.server.eventRequests.get())
        seen.job.cancel()
    }

    @Test
    fun `the types are read when events is called, sorted, one parameter each`() = test { h ->
        val types = mutableSetOf("session.execution.started", "form.created")
        val flow = h.client.events.events(types)
        types.clear()
        val seen = Seen(backgroundScope, flow)
        seen.next()
        val request = checkNotNull(h.web.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/api/event", request.url.encodedPath)
        assertEquals(listOf("form.created", "session.execution.started"), request.url.queryParameterValues("types"))
        h.serveEvent(event)
        assertEquals<ServerEvent>(event, seen.next())
        seen.job.cancel()
    }

    @Test
    fun `cancelling the collection leaves no connection`() = test { h ->
        val seen = Seen(backgroundScope, h.client.events.events())
        seen.next()
        h.awaitEventConnections(1)
        seen.job.cancel()
        seen.job.join()
        h.awaitEventConnections(0)
        assertTrue(h.transport.allCallsCanceled())
    }
}
