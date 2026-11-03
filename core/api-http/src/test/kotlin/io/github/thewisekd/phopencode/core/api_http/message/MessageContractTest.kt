package io.github.thewisekd.phopencode.core.api_http.message

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
import io.github.thewisekd.phopencode.core.api_http.base.ApiRequest
import io.github.thewisekd.phopencode.core.api_http.base.DataEnvelope
import io.github.thewisekd.phopencode.core.api_http.base.HttpMethod
import io.github.thewisekd.phopencode.core.api_http.base.TestServer
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
import io.github.thewisekd.phopencode.core.testing.contract.messageApiContract
import java.lang.reflect.Proxy
import java.util.Collections
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Runs the shared [MessageApi] contract against [HttpMessageApi] over HTTP. */
class MessageContractTest {
    private val opened: MutableList<AutoCloseable> = Collections.synchronizedList(mutableListOf())

    private fun List<ContractCase>.asTests() = map { case ->
        DynamicTest.dynamicTest(case.name) {
            try {
                case.run()
            } finally {
                opened.toList().forEach { it.close() }
                opened.clear()
            }
        }
    }

    @TestFactory
    fun messageApi() = messageApiContract { HttpHarness().also { opened += it } }.asTests()
}

/** Only what `GET /api/session/{id}` says about a revert; the contract reads nothing else. */
@Serializable
private class SessionRevertOnly(val id: String, val revert: RevertWire? = null)

/** Reads a session's revert straight from the server; every other call is out of scope here. */
private class RevertReadingSessions(
    private val transport: io.github.thewisekd.phopencode.core.api_http.base.HttpTransport,
) : SessionApi by unsupported() {
    override suspend fun get(id: SessionId): Session {
        val wire = transport.receive(
            ApiRequest(HttpMethod.GET, listOf("api", "session", id.value)),
            DataEnvelope.serializer(SessionRevertOnly.serializer()),
        ).data
        return Session(SessionId(wire.id), revert = wire.revert?.toModel())
    }
}

private inline fun <reified T : Any> unsupported(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ ->
    throw UnsupportedOperationException("${T::class.simpleName}.${method.name} is not part of this test")
} as T

private class HttpHarness : ApiHarness, AutoCloseable {
    private val backend = MessageServer()
    private val server = TestServer().apply { server.dispatcher = backend }

    override val client: OpencodeClient = object : OpencodeClient {
        override val sessions: SessionApi = RevertReadingSessions(server.transport)
        override val messages: MessageApi = HttpMessageApi(server.transport)
        override val interactions: InteractionApi = unsupported()
        override val catalog: CatalogApi = unsupported()
        override val vcs: VcsApi = unsupported()
        override val fs: FsApi = unsupported()
        override val events: EventApi = unsupported()

        override suspend fun serverInfo(): ServerInfo = throw UnsupportedOperationException()

        override fun close() = server.transport.close()
    }

    override val defaultFolder: Location = Location("/workspace/project")

    override suspend fun givenSession(session: Session) = backend.givenSession(session.id)

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) =
        backend.givenMessages(sessionId, messages)

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) =
        backend.givenTurnDiff(sessionId, from, diffs)

    override suspend fun givenServerVersion(version: String) = outOfScope()

    override suspend fun givenFailure(error: ApiError) = outOfScope()

    override suspend fun stallCalls() = outOfScope()

    override suspend fun awaitRunningCalls(count: Int) = outOfScope()

    override suspend fun givenRunning(sessionId: SessionId) = outOfScope()

    override suspend fun givenForm(form: Form) = outOfScope()

    override suspend fun givenPermission(request: PermissionRequest) = outOfScope()

    override suspend fun givenCommands(commands: List<Command>, location: Location?) = outOfScope()

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) = outOfScope()

    override suspend fun givenBranches(location: Location, branches: List<String>) = outOfScope()

    override suspend fun serveEvent(event: ServerEvent) = outOfScope()

    override suspend fun serveHeartbeat() = outOfScope()

    override suspend fun dropEventConnections() = outOfScope()

    override suspend fun acceptReconnects() = outOfScope()

    override suspend fun refuseEventConnections(error: ApiError) = outOfScope()

    override suspend fun awaitEventConnections(count: Int) = outOfScope()

    override suspend fun eventConnections(): Int = outOfScope()

    private fun outOfScope(): Nothing = throw UnsupportedOperationException("The message contract does not use this")

    override fun close() = server.close()
}
