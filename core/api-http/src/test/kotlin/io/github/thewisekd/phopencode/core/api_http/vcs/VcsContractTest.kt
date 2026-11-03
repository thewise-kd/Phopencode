package io.github.thewisekd.phopencode.core.api_http.vcs

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
import io.github.thewisekd.phopencode.core.testing.contract.vcsApiContract
import java.lang.reflect.Proxy
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs the `core/testing` contracts for [VcsApi] against [HttpVcsApi] over HTTP, with
 * [FakeVcsServer] as the server, plus the client cases that name a VCS call.
 */
class VcsContractTest {
    @TestFactory
    fun vcsApi() = vcsApiContract(::harness).run()

    @TestFactory
    fun opencodeClient() = opencodeClientContract(::harness).filter { it.name.startsWith("vcs.") }
        .also { assertTrue(it.size == 4, "expected the four vcs.* after close cases, got ${it.map(ContractCase::name)}") }
        .run()

    private val open = mutableListOf<VcsHarness>()

    private fun harness(): ApiHarness = VcsHarness().also { synchronized(open) { open += it } }

    private fun List<ContractCase>.run(): List<DynamicTest> {
        assertTrue(isNotEmpty(), "no case selected")
        return map { case ->
            DynamicTest.dynamicTest(case.name) {
                try {
                    case.run()
                } finally {
                    synchronized(open) { open.toList().also { open.clear() } }.forEach(VcsHarness::close)
                }
            }
        }
    }
}

/**
 * An in-memory opencode server for the VCS routes, shaped after the recordings: `{location, data}`
 * replies, `{branch: {}}` and a null base with no repository, branch names as plain strings, and a
 * case-insensitive branch search as the 2.2.5 server does it.
 */
internal class FakeVcsServer : Dispatcher() {
    private val lock = Any()
    private val branches = mutableMapOf<String, List<String>>()

    fun givenBranches(directory: String, names: List<String>) = synchronized(lock) { branches[directory] = names }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) { route(request) }

    private fun route(request: RecordedRequest): MockResponse {
        check(request.method == "GET") { "unexpected method" }
        val url = request.url
        val directory = checkNotNull(url.queryParameter("location[directory]")) { "no location" }
        return when (url.pathSegments) {
            listOf("api", "vcs") -> data(directory, buildJsonObject { put("branch", JsonObject(emptyMap())) })
            listOf("api", "vcs", "base") -> data(directory, JsonNull)
            listOf("api", "vcs", "status") -> data(directory, JsonArray(emptyList()))
            listOf("api", "vcs", "diff") -> data(directory, JsonArray(emptyList()))
            listOf("api", "vcs", "branch") -> {
                val search = url.queryParameter("search")?.trim()?.lowercase()
                val limit = url.queryParameter("limit")?.toInt() ?: Int.MAX_VALUE
                val names = branches[directory].orEmpty()
                    .filter { search.isNullOrEmpty() || it.lowercase().contains(search) }
                    .take(limit)
                data(directory, JsonArray(names.map(::JsonPrimitive)))
            }
            else -> error("unexpected route")
        }
    }

    private fun data(directory: String, value: JsonElement) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body(
            buildJsonObject {
                put("location", buildJsonObject { put("directory", directory) })
                put("data", value)
            }.toString(),
        )
        .build()
}

/** A client whose only working part is [vcs]; the rest belongs to other tasks. */
private class VcsOnlyClient(private val transport: HttpTransport) : OpencodeClient {
    override val sessions: SessionApi = unsupported()
    override val messages: MessageApi = unsupported()
    override val interactions: InteractionApi = unsupported()
    override val catalog: CatalogApi = unsupported()
    override val vcs: VcsApi = HttpVcsApi(transport)
    override val fs: FsApi = unsupported()
    override val events: EventApi = unsupported()

    override suspend fun serverInfo(): ServerInfo = throw UnsupportedOperationException("serverInfo")

    override fun close() = transport.close()

    private inline fun <reified T : Any> unsupported(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("${T::class.simpleName}.${method.name}") } as T
}

private class VcsHarness : ApiHarness, AutoCloseable {
    override val defaultFolder: Location = Location("/srv/default")
    private val fake = FakeVcsServer()
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val transport = HttpTransport(server.url("/"), BasicCredentials(TEST_PASSWORD))
    override val client: OpencodeClient = VcsOnlyClient(transport)

    override suspend fun givenBranches(location: Location, branches: List<String>) =
        fake.givenBranches(location.directory, branches)

    override suspend fun givenFailure(error: ApiError) = unsupported()

    override suspend fun stallCalls() = unsupported()

    override suspend fun awaitRunningCalls(count: Int) = unsupported()

    override fun close() {
        transport.close()
        server.close()
    }

    override suspend fun givenServerVersion(version: String) = unsupported()

    override suspend fun givenSession(session: Session) = unsupported()

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) = unsupported()

    override suspend fun givenRunning(sessionId: SessionId) = unsupported()

    override suspend fun givenForm(form: Form) = unsupported()

    override suspend fun givenPermission(request: PermissionRequest) = unsupported()

    override suspend fun givenCommands(commands: List<Command>, location: Location?) = unsupported()

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) = unsupported()

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) = unsupported()

    override suspend fun serveEvent(event: ServerEvent) = unsupported()

    override suspend fun serveHeartbeat() = unsupported()

    override suspend fun dropEventConnections() = unsupported()

    override suspend fun acceptReconnects() = unsupported()

    override suspend fun refuseEventConnections(error: ApiError) = unsupported()

    override suspend fun awaitEventConnections(count: Int) = unsupported()

    override suspend fun eventConnections(): Int = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not a VCS route")
}
