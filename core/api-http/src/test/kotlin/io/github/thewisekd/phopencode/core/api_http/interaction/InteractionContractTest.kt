package io.github.thewisekd.phopencode.core.api_http.interaction

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
import io.github.thewisekd.phopencode.core.testing.contract.interactionApiContract
import io.github.thewisekd.phopencode.core.testing.contract.opencodeClientContract
import java.lang.reflect.Proxy
import kotlin.test.assertTrue
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Runs the `core/testing` contract for [InteractionApi] against [HttpInteractionApi] over HTTP, with
 * [InteractionServer] as the server. Cases that need another API are left out by name, and the test
 * fails if one of those names disappears, so that a renamed case is not skipped by accident.
 */
class InteractionContractTest {
    @TestFactory
    fun interactionApi() = interactionApiContract(::harness).select(exclude = NEEDS_OTHER_APIS)

    @TestFactory
    fun opencodeClient() = opencodeClientContract(::harness).select(include = ::isInteractionOnly)

    private val open = mutableListOf<InteractionHarness>()

    private fun harness(): ApiHarness = InteractionHarness().also { synchronized(open) { open += it } }

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
                    synchronized(open) { open.toList().also { open.clear() } }.forEach(InteractionHarness::close)
                }
            }
        }
    }

    private companion object {
        val NEEDS_OTHER_APIS = setOf(
            // Reads the event stream (EventApi) and the active sessions (SessionApi).
            "dismissing a question ends the whole execution",
        )

        val INTERACTION_CALLS = setOf(
            "interactions.listForms",
            "interactions.replyForm",
            "interactions.cancelForm",
            "interactions.listPermissions",
            "interactions.replyPermission",
        )

        fun isInteractionOnly(name: String): Boolean =
            INTERACTION_CALLS.any { name == "$it after close fails with Closed" }
    }

    @TestFactory
    fun everyInteractionCallIsCoveredAfterClose() = listOf(
        DynamicTest.dynamicTest("every interaction call has an after-close case") {
            val names = opencodeClientContract(::harness).map { it.name }.toSet()
            val missing = INTERACTION_CALLS.map { "$it after close fails with Closed" }.filter { it !in names }
            assertTrue(missing.isEmpty(), "Missing cases: $missing")
        },
    )
}

/** A client whose only working part is [interactions]; the rest belongs to other tasks. */
private class InteractionOnlyClient(private val transport: HttpTransport) : OpencodeClient {
    override val sessions: SessionApi = unsupported()
    override val messages: MessageApi = unsupported()
    override val interactions: InteractionApi = HttpInteractionApi(transport)
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

private class InteractionHarness : ApiHarness, AutoCloseable {
    override val defaultFolder: Location = Location("/srv/default")
    private val fake = InteractionServer(defaultFolder.directory)
    private val server = MockWebServer().apply {
        dispatcher = fake
        start()
    }
    private val transport = HttpTransport(server.url("/"), BasicCredentials(TEST_PASSWORD))
    override val client: OpencodeClient = InteractionOnlyClient(transport)

    override suspend fun givenSession(session: Session) = fake.givenSession(session)

    override suspend fun givenForm(form: Form) = fake.givenForm(form)

    override suspend fun givenPermission(request: PermissionRequest) = fake.givenPermission(request)

    override suspend fun givenFailure(error: ApiError) = fake.givenFailure(error)

    override fun close() {
        transport.close()
        server.close()
    }

    override suspend fun stallCalls() = unsupported()

    override suspend fun awaitRunningCalls(count: Int) = unsupported()

    override suspend fun givenServerVersion(version: String) = unsupported()

    override suspend fun givenMessages(sessionId: SessionId, messages: List<Message>) = unsupported()

    override suspend fun givenRunning(sessionId: SessionId) = unsupported()

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

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not an interaction route")
}
