package io.github.thewisekd.phopencode.core.api_http.client

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.api_http.base.RecordingLogger
import io.github.thewisekd.phopencode.core.api_http.base.TEST_PASSWORD
import io.github.thewisekd.phopencode.core.api_http.event.stream.ReconnectPolicy
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.FileDiff
import io.github.thewisekd.phopencode.core.model.FileEntry
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.testing.contract.ApiHarness
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import okhttp3.Dispatcher

/** Reconnects quickly, so that drops in tests take milliseconds. */
internal val FAST_EVENTS = ReconnectPolicy(
    initialDelay = 20.milliseconds,
    maxDelay = 100.milliseconds,
    jitter = 0.0,
    silenceTimeout = 10.seconds,
    closedCheckInterval = 20.milliseconds,
)

/**
 * The assembled [HttpOpencodeClient] against [FullServer] over HTTP.
 *
 * The contract's "every running call fails with Closed" case holds every call of the client on the
 * server at once, more than OkHttp lets through to one host by default; [maxCallsPerHost] lifts
 * that limit. `HttpOpencodeClientTest` covers calls that wait behind the default limit.
 */
internal class FullHarness(maxCallsPerHost: Int = 64) : ApiHarness, AutoCloseable {
    override val defaultFolder: Location = Location("/srv/default")
    val server = FullServer(defaultFolder.directory)
    val web = MockWebServer().apply {
        dispatcher = server
        start()
    }
    val logger = RecordingLogger()
    val transport = HttpTransport(web.url("/"), BasicCredentials(TEST_PASSWORD), logger) {
        dispatcher(Dispatcher().apply { maxRequestsPerHost = maxCallsPerHost })
    }
    override val client: HttpOpencodeClient = HttpOpencodeClient(transport, FAST_EVENTS)

    override suspend fun givenServerVersion(version: String) = server.givenServerVersion(version)

    override suspend fun givenFailure(error: ApiError) = server.givenFailure(error)

    override suspend fun stallCalls() = server.stall()

    override suspend fun awaitRunningCalls(count: Int) = waitFor { server.waitingCalls >= count }

    override suspend fun givenSession(session: Session) = server.givenSession(session)

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) =
        server.givenMessages(sessionId, messages)

    override suspend fun givenRunning(sessionId: SessionId) = server.givenRunning(sessionId)

    override suspend fun givenForm(form: Form) = server.givenForm(form)

    override suspend fun givenPermission(request: PermissionRequest) = server.givenPermission(request)

    override suspend fun givenCommands(commands: List<Command>, location: Location?) =
        server.givenCommands(commands, location?.directory)

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) =
        server.givenFiles(location.directory, files)

    override suspend fun givenBranches(location: Location, branches: List<String>) =
        server.givenBranches(location.directory, branches)

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) =
        server.givenTurnDiff(sessionId, from, diffs)

    override suspend fun serveEvent(event: ServerEvent) = server.serveEvent(event)

    override suspend fun serveHeartbeat() = server.serveHeartbeat()

    override suspend fun dropEventConnections() = server.dropEventConnections()

    override suspend fun acceptReconnects() = server.acceptReconnects()

    override suspend fun refuseEventConnections(error: ApiError) = server.refuseEventConnections(error)

    override suspend fun awaitEventConnections(count: Int) = waitFor { eventConnections() == count }

    /**
     * Counts the connections the server still holds. A few rounds of pings come first: a connection
     * the client dropped is only noticed when a write to it fails.
     */
    override suspend fun eventConnections(): Int = withContext(Dispatchers.Default) {
        repeat(PING_ROUNDS) {
            server.pingEventConnections()
            delay(PING_PAUSE_MS)
        }
        server.openEventConnections()
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(WAIT) {
            while (!condition()) delay(POLL_MS)
        }
    }

    override fun close() {
        client.close()
        server.releaseAll()
        web.close()
    }

    private companion object {
        val WAIT = 20.seconds
        const val POLL_MS = 10L
        const val PING_ROUNDS = 4
        const val PING_PAUSE_MS = 30L
    }
}
