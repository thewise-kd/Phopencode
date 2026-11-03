package io.github.thewisekd.phopencode.core.api_http.session

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.EventApi
import io.github.thewisekd.phopencode.core.api.FsApi
import io.github.thewisekd.phopencode.core.api.InteractionApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.MessageApi
import io.github.thewisekd.phopencode.core.api.OpencodeClient
import io.github.thewisekd.phopencode.core.api.ServerEvent
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.api.SessionApi
import io.github.thewisekd.phopencode.core.api.VcsApi
import io.github.thewisekd.phopencode.core.api_http.base.BasicCredentials
import io.github.thewisekd.phopencode.core.api_http.base.HttpTransport
import io.github.thewisekd.phopencode.core.api_http.base.TEST_PASSWORD
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
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import io.github.thewisekd.phopencode.core.testing.contract.sessionApiContract
import java.lang.reflect.Proxy
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs the `core/testing` contracts for [SessionApi] against [HttpSessionApi] over HTTP, with
 * [FakeSessionServer] as the server. Cases that need another API are left out by name, and the test
 * fails if one of those names disappears, so that a renamed case is not skipped by accident.
 */
class SessionContractTest {
    @TestFactory
    fun sessionApi() = sessionApiContract(::harness).select(exclude = NEEDS_OTHER_APIS)

    @TestFactory
    fun opencodeClient() = opencodeClientContract(::harness).select(include = ::isSessionOnly)

    private val open = mutableListOf<SessionHarness>()

    private fun harness(): ApiHarness = SessionHarness().also { synchronized(open) { open += it } }

    private fun List<ContractCase>.select(
        exclude: Set<String> = emptySet(),
        include: (String) -> Boolean = { true },
    ): List<DynamicTest> {
        val names = map { it.name }.toSet()
        assertTrue(names.containsAll(exclude), "Excluded cases not found: ${exclude - names}")
        val selected = filter { it.name !in exclude && include(it.name) }
        assertTrue(selected.isNotEmpty(), "no case selected")
        return selected.map { case ->
            DynamicTest.dynamicTest(case.name) {
                try {
                    case.run()
                } finally {
                    synchronized(open) { open.toList().also { open.clear() } }.forEach(SessionHarness::close)
                }
            }
        }
    }

    private companion object {
        val NEEDS_OTHER_APIS = setOf(
            // Reads the event stream (EventApi, P2.2).
            "un-archiving sends SessionUnarchived",
            // Reads the copied history through MessageApi (P2.5).
            "fork copies the history, all of it or up to a message",
        )

        val SESSION_ONLY_CLIENT_CASES = setOf(
            "a server failure arrives as ApiException with its typed error",
            "the error does not stick to later calls",
            "cancelling a running call passes through as cancellation",
            "close can be called twice and throws nothing",
            "a closed client answers Closed, whatever the server would have said",
        )

        fun isSessionOnly(name: String): Boolean =
            name in SESSION_ONLY_CLIENT_CASES || (name.startsWith("sessions.") && name.endsWith(" after close fails with Closed"))
    }
}

/** A client whose only working part is [sessions]; the rest belongs to other tasks. */
private class SessionOnlyClient(private val transport: HttpTransport) : OpencodeClient {
    override val sessions: SessionApi = HttpSessionApi(transport)
    override val messages: MessageApi = unsupported()
    override val interactions: InteractionApi = unsupported()
    override val catalog: CatalogApi = unsupported()
    override val vcs: VcsApi = unsupported()
    override val fs: FsApi = unsupported()
    override val events: EventApi = unsupported()

    override suspend fun serverInfo(): ServerInfo = throw UnsupportedOperationException("serverInfo")

    override fun close() = transport.close()

    private inline fun <reified T : Any> unsupported(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("${T::class.simpleName}.${method.name}") } as T
}

private class SessionHarness : ApiHarness, AutoCloseable {
    override val defaultFolder: Location = Location("/srv/default")
    private val fake = FakeSessionServer(defaultFolder.directory)
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val transport = HttpTransport(server.url("/"), BasicCredentials(TEST_PASSWORD))
    override val client: OpencodeClient = SessionOnlyClient(transport)

    override suspend fun givenSession(session: Session) = fake.givenSession(session)

    override suspend fun givenRunning(sessionId: SessionId) = fake.givenRunning(sessionId.value)

    override suspend fun givenFailure(error: ApiError) = fake.givenFailure(error)

    override suspend fun stallCalls() = fake.stall()

    override suspend fun awaitRunningCalls(count: Int) = withContext(Dispatchers.Default) {
        withTimeout(WAIT) {
            while (fake.waitingCalls < count) delay(POLL_MS)
        }
    }

    override fun close() {
        transport.close()
        fake.releaseAll()
        server.close()
    }

    override suspend fun givenServerVersion(version: String) = unsupported()

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) = unsupported()

    override suspend fun givenForm(form: Form) = unsupported()

    override suspend fun givenPermission(request: PermissionRequest) = unsupported()

    override suspend fun givenCommands(commands: List<Command>, location: Location?) = unsupported()

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) = unsupported()

    override suspend fun givenBranches(location: Location, branches: List<String>) = unsupported()

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) = unsupported()

    override suspend fun serveEvent(event: ServerEvent) = unsupported()

    override suspend fun serveHeartbeat() = unsupported()

    override suspend fun dropEventConnections() = unsupported()

    override suspend fun acceptReconnects() = unsupported()

    override suspend fun refuseEventConnections(error: ApiError) = unsupported()

    override suspend fun awaitEventConnections(count: Int) = unsupported()

    override suspend fun eventConnections(): Int = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not a session route")

    private companion object {
        val WAIT = 20.seconds
        const val POLL_MS = 10L
    }
}
