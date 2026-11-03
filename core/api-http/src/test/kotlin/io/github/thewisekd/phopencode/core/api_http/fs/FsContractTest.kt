package io.github.thewisekd.phopencode.core.api_http.fs

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
import io.github.thewisekd.phopencode.core.model.FileEntryType
import io.github.thewisekd.phopencode.core.model.Form
import io.github.thewisekd.phopencode.core.model.Message
import io.github.thewisekd.phopencode.core.model.MessageId
import io.github.thewisekd.phopencode.core.model.PermissionRequest
import io.github.thewisekd.phopencode.core.model.Session
import io.github.thewisekd.phopencode.core.model.SessionId
import io.github.thewisekd.phopencode.core.testing.contract.ApiHarness
import io.github.thewisekd.phopencode.core.testing.contract.ContractCase
import io.github.thewisekd.phopencode.core.testing.contract.fsApiContract
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs the `core/testing` contracts for [FsApi] against [HttpFsApi] over HTTP, with [FakeFsServer] as
 * the server, plus the client case that names `fs.find`.
 */
class FsContractTest {
    @TestFactory
    fun fsApi() = fsApiContract(::harness).run()

    @TestFactory
    fun opencodeClient() = opencodeClientContract(::harness).filter { it.name.startsWith("fs.") }
        .also { cases -> assertEquals(listOf("fs.find after close fails with Closed"), cases.map(ContractCase::name)) }
        .run()

    private val open = mutableListOf<FsHarness>()

    private fun harness(): ApiHarness = FsHarness().also { synchronized(open) { open += it } }

    private fun List<ContractCase>.run(): List<DynamicTest> {
        assertTrue(isNotEmpty(), "no case selected")
        return map { case ->
            DynamicTest.dynamicTest(case.name) {
                try {
                    case.run()
                } finally {
                    synchronized(open) { open.toList().also { open.clear() } }.forEach(FsHarness::close)
                }
            }
        }
    }
}

/**
 * An in-memory opencode server for `/api/fs/find`, shaped after the recordings: `{location, data}`,
 * entries of `{path, type}` with a directory's path ending in `/`, one folder per location.
 */
internal class FakeFsServer : Dispatcher() {
    private val lock = Any()
    private val files = mutableMapOf<String, List<FileEntry>>()

    fun givenFiles(directory: String, entries: List<FileEntry>) = synchronized(lock) { files[directory] = entries }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(lock) {
        val url = request.url
        check(request.method == "GET" && url.pathSegments == listOf("api", "fs", "find")) { "unexpected route" }
        val directory = checkNotNull(url.queryParameter("location[directory]")) { "no location" }
        val query = checkNotNull(url.queryParameter("query")).lowercase()
        val type = url.queryParameter("type")
        val limit = url.queryParameter("limit")?.toInt() ?: Int.MAX_VALUE
        val found = files[directory].orEmpty()
            .filter { it.path.lowercase().contains(query) && (type == null || it.wireType() == type) }
            .take(limit)
        val body = buildJsonObject {
            put("location", buildJsonObject { put("directory", directory) })
            put(
                "data",
                JsonArray(
                    found.map {
                        buildJsonObject {
                            put("path", if (it.wireType() == "directory") "${it.path}/" else it.path)
                            put("type", it.wireType())
                        }
                    },
                ),
            )
        }
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body.toString()).build()
    }

    private fun FileEntry.wireType(): String = when (type) {
        FileEntryType.Directory -> "directory"
        else -> "file"
    }
}

/** A client whose only working part is [fs]; the rest belongs to other tasks. */
private class FsOnlyClient(private val transport: HttpTransport) : OpencodeClient {
    override val sessions: SessionApi = unsupported()
    override val messages: MessageApi = unsupported()
    override val interactions: InteractionApi = unsupported()
    override val catalog: CatalogApi = unsupported()
    override val vcs: VcsApi = unsupported()
    override val fs: FsApi = HttpFsApi(transport)
    override val events: EventApi = unsupported()

    override suspend fun serverInfo(): ServerInfo = throw UnsupportedOperationException("serverInfo")

    override fun close() = transport.close()

    private inline fun <reified T : Any> unsupported(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> throw UnsupportedOperationException("${T::class.simpleName}.${method.name}") } as T
}

private class FsHarness : ApiHarness, AutoCloseable {
    override val defaultFolder: Location = Location("/srv/default")
    private val fake = FakeFsServer()
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val transport = HttpTransport(server.url("/"), BasicCredentials(TEST_PASSWORD))
    override val client: OpencodeClient = FsOnlyClient(transport)

    override suspend fun givenFiles(location: Location, files: List<FileEntry>) =
        fake.givenFiles(location.directory, files)

    override fun close() {
        transport.close()
        server.close()
    }

    override suspend fun givenServerVersion(version: String) = unsupported()

    override suspend fun givenFailure(error: ApiError) = unsupported()

    override suspend fun stallCalls() = unsupported()

    override suspend fun awaitRunningCalls(count: Int) = unsupported()

    override suspend fun givenSession(session: Session) = unsupported()

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) = unsupported()

    override suspend fun givenRunning(sessionId: SessionId) = unsupported()

    override suspend fun givenForm(form: Form) = unsupported()

    override suspend fun givenPermission(request: PermissionRequest) = unsupported()

    override suspend fun givenCommands(commands: List<Command>, location: Location?) = unsupported()

    override suspend fun givenBranches(location: Location, branches: List<String>) = unsupported()

    override suspend fun givenTurnDiff(sessionId: SessionId, from: MessageId, diffs: List<FileDiff>) = unsupported()

    override suspend fun serveEvent(event: ServerEvent) = unsupported()

    override suspend fun serveHeartbeat() = unsupported()

    override suspend fun dropEventConnections() = unsupported()

    override suspend fun acceptReconnects() = unsupported()

    override suspend fun refuseEventConnections(error: ApiError) = unsupported()

    override suspend fun awaitEventConnections(count: Int) = unsupported()

    override suspend fun eventConnections(): Int = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not an fs route")
}
