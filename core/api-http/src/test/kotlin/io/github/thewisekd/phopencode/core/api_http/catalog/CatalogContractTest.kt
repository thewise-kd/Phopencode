package io.github.thewisekd.phopencode.core.api_http.catalog

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
import io.github.thewisekd.phopencode.core.api_http.base.ApiJson
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
import io.github.thewisekd.phopencode.core.testing.contract.catalogApiContract
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs the `core/testing` contract for [CatalogApi] against [HttpCatalogApi] over HTTP, and the
 * client contract's "after close" cases for the catalog calls.
 */
class CatalogContractTest {
    private val opened: MutableList<AutoCloseable> = Collections.synchronizedList(mutableListOf())

    private fun harness(): ApiHarness = CatalogHarness().also { opened += it }

    private fun List<ContractCase>.asTests(): List<DynamicTest> {
        assertTrue(isNotEmpty(), "no case selected")
        return map { case ->
            DynamicTest.dynamicTest(case.name) {
                try {
                    case.run()
                } finally {
                    opened.toList().forEach { it.close() }
                    opened.clear()
                }
            }
        }
    }

    @TestFactory
    fun catalogApi() = catalogApiContract(::harness).asTests()

    @TestFactory
    fun opencodeClient() = opencodeClientContract(::harness)
        .filter { it.name.startsWith("catalog.") && it.name.endsWith(" after close fails with Closed") }
        .also { assertTrue(it.size == CATALOG_CALLS, "expected $CATALOG_CALLS catalog close cases, got ${it.size}") }
        .asTests()

    private companion object {
        const val CATALOG_CALLS = 8
    }
}

/** A stand-in for the catalog routes, shaped like the recordings: a `{location, data}` envelope. */
private class CatalogServer(private val defaultFolder: String) : Dispatcher() {
    private val commands = Collections.synchronizedMap(HashMap<String, List<Command>>())

    fun givenCommands(list: List<Command>, folder: String?) {
        commands[folder ?: defaultFolder] = list
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val folder = request.url.queryParameter("location[directory]") ?: defaultFolder
        val data = when (request.method to request.url.encodedPath) {
            "GET" to "/api/command" -> JsonArray(
                commands[folder].orEmpty().map { command ->
                    buildJsonObject {
                        put("name", command.name)
                        command.description?.let { put("description", it) }
                    }
                },
            )
            "GET" to "/api/model/default" -> JsonNull
            else -> return MockResponse.Builder().code(404).body("""{"_tag":"NotFound","message":"Route not found"}""").build()
        }
        val body = buildJsonObject {
            putJsonObject("location") { put("directory", folder) }
            put("data", data)
        }
        return MockResponse.Builder().code(200).body(ApiJson.encodeToString(JsonObject.serializer(), body)).build()
    }
}

private inline fun <reified T : Any> unsupported(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> throw UnsupportedOperationException("${T::class.simpleName}.${method.name} is not part of this test") } as T

private class CatalogHarness : ApiHarness, AutoCloseable {
    override val defaultFolder: Location = Location("/srv/default")
    private val backend = CatalogServer(defaultFolder.directory)
    private val server = TestServer().apply { server.dispatcher = backend }

    override val client: OpencodeClient = object : OpencodeClient {
        override val sessions: SessionApi = unsupported()
        override val messages: MessageApi = unsupported()
        override val interactions: InteractionApi = unsupported()
        override val catalog: CatalogApi = HttpCatalogApi(server.transport)
        override val vcs: VcsApi = unsupported()
        override val fs: FsApi = unsupported()
        override val events: EventApi = unsupported()

        override suspend fun serverInfo(): ServerInfo = throw UnsupportedOperationException("serverInfo")

        override fun close() = server.transport.close()
    }

    override suspend fun givenCommands(commands: List<Command>, location: Location?) =
        backend.givenCommands(commands, location?.directory)

    override suspend fun givenServerVersion(version: String) = outOfScope()

    override suspend fun givenFailure(error: ApiError) = outOfScope()

    override suspend fun stallCalls() = outOfScope()

    override suspend fun awaitRunningCalls(count: Int) = outOfScope()

    override suspend fun givenSession(session: Session) = outOfScope()

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) = outOfScope()

    override suspend fun givenRunning(sessionId: SessionId) = outOfScope()

    override suspend fun givenForm(form: Form) = outOfScope()

    override suspend fun givenPermission(request: PermissionRequest) = outOfScope()

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) = outOfScope()

    override suspend fun givenBranches(location: Location, branches: List<String>) = outOfScope()

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) = outOfScope()

    override suspend fun serveEvent(event: ServerEvent) = outOfScope()

    override suspend fun serveHeartbeat() = outOfScope()

    override suspend fun dropEventConnections() = outOfScope()

    override suspend fun acceptReconnects() = outOfScope()

    override suspend fun refuseEventConnections(error: ApiError) = outOfScope()

    override suspend fun awaitEventConnections(count: Int) = outOfScope()

    override suspend fun eventConnections(): Int = outOfScope()

    private fun outOfScope(): Nothing = throw UnsupportedOperationException("The catalog contract does not use this")

    override fun close() = server.close()
}
