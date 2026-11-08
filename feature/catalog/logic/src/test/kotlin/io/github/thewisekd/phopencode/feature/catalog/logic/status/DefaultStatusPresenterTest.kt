package io.github.thewisekd.phopencode.feature.catalog.logic.status

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.api.Location
import io.github.thewisekd.phopencode.core.api.McpServer
import io.github.thewisekd.phopencode.core.api.McpStatus
import io.github.thewisekd.phopencode.core.api.Plugin
import io.github.thewisekd.phopencode.core.api.PluginStatus
import io.github.thewisekd.phopencode.core.api.Provider
import io.github.thewisekd.phopencode.core.model.Agent
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.core.model.Model
import io.github.thewisekd.phopencode.core.model.ModelRef
import io.github.thewisekd.phopencode.core.model.Skill
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.StatusState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** [DefaultStatusPresenter] reads the MCP servers and the plugins fresh, apart, and never changes anything. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultStatusPresenterTest {
    private val secretError = "connect https://user:hunter2@mcp.invalid/sse?token=abc failed"

    private val github = McpServer(name = "github", status = McpStatus.Connected)
    private val docs = McpServer(name = "docs", status = McpStatus.Failed, error = secretError)
    private val auth = McpServer(name = "auth", status = McpStatus.NeedsAuth)
    private val formatter = Plugin(id = "formatter", status = PluginStatus.Active)
    private val broken = Plugin(id = null, status = PluginStatus.Failed, error = "/home/user/.config/plugin.ts: $secretError")

    /**
     * A catalog that answers only the two lists this screen reads; anything else, connecting or
     * changing included, fails the test. Each answer is taken from [mcpAnswers] / [pluginAnswers] in
     * order, or from the plain lists when the queue is empty.
     */
    private class FakeCatalog : CatalogApi {
        var mcp: List<McpServer> = emptyList()
        var plugins: List<Plugin> = emptyList()
        var mcpFailure: Throwable? = null
        var pluginFailure: Throwable? = null
        val mcpAnswers = ArrayDeque<CompletableDeferred<List<McpServer>>>()
        val pluginAnswers = ArrayDeque<CompletableDeferred<List<Plugin>>>()
        val mcpLocations = mutableListOf<Location?>()
        val pluginLocations = mutableListOf<Location?>()

        override suspend fun mcpServers(location: Location?): List<McpServer> {
            mcpLocations += location
            mcpAnswers.removeFirstOrNull()?.let { return it.await() }
            mcpFailure?.let { throw it }
            return mcp
        }

        override suspend fun plugins(location: Location?): List<Plugin> {
            pluginLocations += location
            pluginAnswers.removeFirstOrNull()?.let { return it.await() }
            pluginFailure?.let { throw it }
            return plugins
        }

        override suspend fun skills(location: Location?): List<Skill> = throw AssertionError("view only: skills")

        override suspend fun commands(location: Location?): List<Command> = throw AssertionError("view only: commands")

        override suspend fun agents(location: Location?): List<Agent> = throw AssertionError("view only: agents")

        override suspend fun models(location: Location?): List<Model> = throw AssertionError("view only: models")

        override suspend fun defaultModel(location: Location?): ModelRef? = throw AssertionError("view only: defaultModel")

        override suspend fun providers(location: Location?): List<Provider> = throw AssertionError("view only: providers")
    }

    /**
     * A connection whose catalog and generation can change, or go away (null). [generations] follows
     * them unless [announce] is off, as when a call already runs on a connection the flow has not
     * reported yet. With a [firstReport] gate, nothing is reported until it opens.
     */
    private class FakeConnection(
        catalog: FakeCatalog?,
        generation: Long = 1,
        private val announce: Boolean = true,
        private val firstReport: CompletableDeferred<Unit>? = null,
    ) : CatalogConnection {
        private val reports = MutableStateFlow<Long?>(if (catalog == null) null else generation)

        override val generations: Flow<Long?> = flow {
            firstReport?.await()
            emitAll(reports)
        }

        var catalog: FakeCatalog? = catalog
            set(value) {
                field = value
                if (announce) reports.value = if (value == null) null else generation
            }

        var generation: Long = generation
            set(value) {
                field = value
                if (announce && catalog != null) reports.value = value
            }

        var calls = 0

        /** Reports the connection standing now, as the flow does late when [announce] is off. */
        fun report() {
            reports.value = if (catalog == null) null else generation
        }

        override suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T {
            calls++
            val current = catalog ?: throw ApiException(ApiError.Closed)
            return block(current, generation)
        }
    }

    private fun TestScope.presenter(connection: CatalogConnection) =
        DefaultStatusPresenter(connection = connection, scope = backgroundScope)

    @Test
    fun `nothing is read before the scope runs, then both lists are read in the server's order`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(github, docs, auth)
            plugins = listOf(formatter, broken)
        }
        val connection = FakeConnection(catalog)
        val presenter = presenter(connection)

        assertEquals(StatusState(), presenter.state.value)
        assertEquals(0, connection.calls)

        runCurrent()

        assertEquals(
            StatusState(
                mcp = CatalogList(loading = false, items = listOf(github, docs, auth)),
                plugins = CatalogList(loading = false, items = listOf(formatter, broken)),
            ),
            presenter.state.value,
        )
        assertEquals(listOf<Location?>(null), catalog.mcpLocations)
        assertEquals(listOf<Location?>(null), catalog.pluginLocations)
    }

    @Test
    fun `nothing is read before the connection is first reported, then it is read for that one`() = runTest {
        val report = CompletableDeferred<Unit>()
        val connection = FakeConnection(FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }, generation = 1, firstReport = report)
        val presenter = presenter(connection)
        runCurrent()

        assertEquals(0, connection.calls)
        assertEquals(StatusState(), presenter.state.value)
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        assertEquals(0, connection.calls)

        connection.catalog = FakeCatalog().apply {
            mcp = listOf(auth)
            plugins = listOf(broken)
        }
        connection.generation = 2
        report.complete(Unit)
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(broken)), presenter.state.value.plugins)
    }

    @Test
    fun `errors stay on their rows, in the server's words`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(docs)
            plugins = listOf(broken)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        assertEquals(secretError, presenter.state.value.mcp.items.single().error)
        assertEquals(McpStatus.Failed, presenter.state.value.mcp.items.single().status)
        assertEquals(broken.error, presenter.state.value.plugins.items.single().error)
    }

    @Test
    fun `empty lists are shown as empty`() = runTest {
        val presenter = presenter(FakeConnection(FakeCatalog()))
        runCurrent()

        assertTrue(presenter.state.value.mcp.isEmpty)
        assertTrue(presenter.state.value.plugins.isEmpty)
    }

    @Test
    fun `one list that fails leaves the other`() = runTest {
        val catalog = FakeCatalog().apply {
            mcpFailure = ApiException(ApiError.Server(500, secretError))
            plugins = listOf(formatter)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        assertEquals(CatalogList(loading = false, error = CatalogFailure.ServerFailed), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(formatter)), presenter.state.value.plugins)
    }

    @Test
    fun `the plugin list failing leaves the MCP list`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(github)
            pluginFailure = ApiException(ApiError.Unauthorized)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(github)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, error = CatalogFailure.Unauthorized), presenter.state.value.plugins)
    }

    @Test
    fun `without a connection both lists fail as not connected`() = runTest {
        val presenter = presenter(FakeConnection(catalog = null))
        runCurrent()

        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.plugins)
    }

    @Test
    fun `an unexpected failure is unknown and stops nothing else`() = runTest {
        val catalog = FakeCatalog().apply {
            mcpFailure = IllegalStateException(secretError)
            plugins = listOf(formatter)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        assertEquals(CatalogList(loading = false, error = CatalogFailure.Unknown), presenter.state.value.mcp)
        assertEquals(listOf(formatter), presenter.state.value.plugins.items)
    }

    @Test
    fun `refresh reads both lists again, fresh, keeping the old ones while it loads`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        val mcpAnswer = CompletableDeferred<List<McpServer>>().also(catalog.mcpAnswers::add)
        val pluginAnswer = CompletableDeferred<List<Plugin>>().also(catalog.pluginAnswers::add)
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(CatalogList(loading = true, items = listOf(github)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = true, items = listOf(formatter)), presenter.state.value.plugins)

        mcpAnswer.complete(listOf(github, docs))
        pluginAnswer.complete(emptyList())
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(github, docs)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = emptyList()), presenter.state.value.plugins)
        assertEquals(2, catalog.mcpLocations.size)
        assertEquals(2, catalog.pluginLocations.size)
    }

    @Test
    fun `a retry after a failure clears the error and reads again`() = runTest {
        val catalog = FakeCatalog().apply { mcpFailure = ApiException(ApiError.Network) }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()
        assertEquals(CatalogFailure.Unreachable, presenter.state.value.mcp.error)

        val answer = CompletableDeferred<List<McpServer>>().also(catalog.mcpAnswers::add)
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        assertEquals(CatalogList(loading = true), presenter.state.value.mcp)

        answer.complete(listOf(auth))
        runCurrent()
        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
    }

    @Test
    fun `a failed refresh on the same connection keeps what was read`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        catalog.mcpFailure = ApiException(ApiError.Timeout)
        catalog.pluginFailure = ApiException(ApiError.Timeout)
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(
            CatalogList(loading = false, items = listOf(github), error = CatalogFailure.Unreachable),
            presenter.state.value.mcp,
        )
        assertEquals(
            CatalogList(loading = false, items = listOf(formatter), error = CatalogFailure.Unreachable),
            presenter.state.value.plugins,
        )
    }

    @Test
    fun `a failed refresh without a connection drops what the gone connection showed`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }
        val connection = FakeConnection(catalog)
        val presenter = presenter(connection)
        runCurrent()

        connection.catalog = null
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.plugins)
    }

    @Test
    fun `a failed refresh on another connection drops what the earlier one showed`() = runTest {
        val first = FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }
        val connection = FakeConnection(first, generation = 1)
        val presenter = presenter(connection)
        runCurrent()

        connection.catalog = FakeCatalog().apply {
            mcpFailure = ApiException(ApiError.Network)
            pluginFailure = ApiException(ApiError.Network)
        }
        connection.generation = 2
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(CatalogList(loading = false, error = CatalogFailure.Unreachable), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, error = CatalogFailure.Unreachable), presenter.state.value.plugins)
    }

    @Test
    fun `a refresh on a new connection shows nothing of the old one while it reads`() = runTest {
        val connection = FakeConnection(FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }, generation = 1)
        val presenter = presenter(connection)
        runCurrent()
        assertEquals(listOf(github), presenter.state.value.mcp.items)

        val second = FakeCatalog().apply {
            repeat(2) {
                mcpAnswers.add(CompletableDeferred())
                pluginAnswers.add(CompletableDeferred())
            }
        }
        connection.catalog = second
        connection.generation = 2
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(CatalogList(loading = true), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = true), presenter.state.value.plugins)
    }

    @Test
    fun `a call that runs on a connection not yet reported shows nothing of the old one`() = runTest {
        val connection = FakeConnection(FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }, generation = 1, announce = false)
        val presenter = presenter(connection)
        runCurrent()

        connection.catalog = FakeCatalog().apply {
            mcpAnswers.add(CompletableDeferred())
            pluginAnswers.add(CompletableDeferred())
        }
        connection.generation = 2
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        assertEquals(CatalogList(loading = true), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = true), presenter.state.value.plugins)
    }

    @Test
    fun `a connection change while shown drops the old lists and reads the new ones`() = runTest {
        val connection = FakeConnection(FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }, generation = 1)
        val presenter = presenter(connection)
        runCurrent()

        val mcpAnswer = CompletableDeferred<List<McpServer>>()
        val pluginAnswer = CompletableDeferred<List<Plugin>>()
        connection.catalog = FakeCatalog().apply {
            mcpAnswers.add(mcpAnswer)
            pluginAnswers.add(pluginAnswer)
        }
        connection.generation = 2
        runCurrent()

        assertEquals(CatalogList(loading = true), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = true), presenter.state.value.plugins)

        mcpAnswer.complete(listOf(auth))
        pluginAnswer.complete(listOf(broken))
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(broken)), presenter.state.value.plugins)
    }

    @Test
    fun `a connection going away while shown drops the lists`() = runTest {
        val connection = FakeConnection(FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        })
        val presenter = presenter(connection)
        runCurrent()

        connection.catalog = null
        runCurrent()

        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.plugins)
    }

    @Test
    fun `a first read stalled on a connection that is replaced never shows its answer`() = runTest {
        val oldMcp = CompletableDeferred<List<McpServer>>()
        val oldPlugins = CompletableDeferred<List<Plugin>>()
        val first = FakeCatalog().apply {
            mcpAnswers.add(oldMcp)
            pluginAnswers.add(oldPlugins)
        }
        val connection = FakeConnection(first, generation = 1)
        val presenter = presenter(connection)
        val seen = mutableListOf<StatusState>()
        backgroundScope.launch { presenter.state.collect { seen += it } }
        runCurrent()
        assertEquals(StatusState(), presenter.state.value)

        // The switch is reported while the first reads stall; then the old connection answers.
        connection.catalog = FakeCatalog().apply {
            mcp = listOf(auth)
            plugins = listOf(broken)
        }
        connection.generation = 2
        runCurrent()
        oldMcp.complete(listOf(github))
        oldPlugins.complete(listOf(formatter))
        runCurrent()

        assertTrue(seen.none { github in it.mcp.items || formatter in it.plugins.items }, "$seen")
        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(broken)), presenter.state.value.plugins)
    }

    @Test
    fun `a first read stalled on a connection that goes away never shows its answer`() = runTest {
        val oldMcp = CompletableDeferred<List<McpServer>>()
        val oldPlugins = CompletableDeferred<List<Plugin>>()
        val connection = FakeConnection(FakeCatalog().apply {
            mcpAnswers.add(oldMcp)
            pluginAnswers.add(oldPlugins)
        }, generation = 1)
        val presenter = presenter(connection)
        val seen = mutableListOf<StatusState>()
        backgroundScope.launch { presenter.state.collect { seen += it } }
        runCurrent()

        // The loss is reported while the first reads stall; then the old connection answers.
        connection.catalog = null
        runCurrent()
        oldMcp.complete(listOf(github))
        oldPlugins.complete(listOf(formatter))
        runCurrent()

        assertTrue(seen.none { github in it.mcp.items || formatter in it.plugins.items }, "$seen")
        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, error = CatalogFailure.NotConnected), presenter.state.value.plugins)
    }

    @Test
    fun `a stalled read whose answer comes after the switch is reported is dropped, even when not cancelled`() = runTest {
        // The old connection's call ignores cancellation and answers after the switch.
        val gate = CompletableDeferred<Unit>()
        val connection = object : CatalogConnection {
            val flow = MutableStateFlow<Long?>(1)
            var generation = 1L
            override val generations: Flow<Long?> = flow

            override suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T {
                val mine = generation
                val catalog = FakeCatalog().apply {
                    mcp = listOf(if (mine == 1L) github else auth)
                    plugins = listOf(if (mine == 1L) formatter else broken)
                }
                return block(catalog, mine).also {
                    if (mine == 1L) withContext(NonCancellable) { gate.await() }
                }
            }
        }
        val presenter = presenter(connection)
        runCurrent()

        connection.generation = 2
        connection.flow.value = 2
        runCurrent()
        gate.complete(Unit)
        val seen = mutableListOf<StatusState>()
        backgroundScope.launch { presenter.state.collect { seen += it } }
        runCurrent()

        assertTrue(seen.none { github in it.mcp.items || formatter in it.plugins.items }, "$seen")
        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(broken)), presenter.state.value.plugins)
    }

    @Test
    fun `an answer from a connection not yet reported waits for the report, then is read again`() = runTest {
        val connection = FakeConnection(FakeCatalog().apply {
            mcp = listOf(github)
            plugins = listOf(formatter)
        }, generation = 1, announce = false)
        val presenter = presenter(connection)
        runCurrent()

        connection.catalog = FakeCatalog().apply {
            mcp = listOf(auth)
            plugins = listOf(broken)
        }
        connection.generation = 2
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        assertTrue(presenter.state.value.mcp.items.isEmpty())
        assertTrue(presenter.state.value.mcp.loading)

        connection.report()
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(broken)), presenter.state.value.plugins)
    }

    @Test
    fun `a not-connected failure published after the reconnect is reported leaves the new connection's lists`() = runTest {
        // While [closing], a call waits for [gate] and then fails as closed without running its block,
        // as when the connection ended just before the call; cancellation does not stop it.
        val gate = CompletableDeferred<Unit>()
        val connection = object : CatalogConnection {
            val flow = MutableStateFlow<Long?>(1)
            var generation = 1L
            var closing = false
            override val generations: Flow<Long?> = flow

            override suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T {
                if (closing) {
                    withContext(NonCancellable) { gate.await() }
                    throw ApiException(ApiError.Closed)
                }
                val mine = generation
                val catalog = FakeCatalog().apply {
                    mcp = listOf(if (mine == 1L) github else auth)
                    plugins = listOf(if (mine == 1L) formatter else broken)
                }
                return block(catalog, mine)
            }
        }
        val presenter = presenter(connection)
        runCurrent()
        assertEquals(listOf(github), presenter.state.value.mcp.items)

        connection.closing = true
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()

        // The reconnect is reported while the refresh's calls still wait to fail.
        connection.closing = false
        connection.generation = 2
        connection.flow.value = 2
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
        assertEquals(CatalogList(loading = false, items = listOf(broken)), presenter.state.value.plugins)
    }

    @Test
    fun `a refresh while reading drops the earlier read's late answer`() = runTest {
        val catalog = FakeCatalog()
        val stale = CompletableDeferred<List<McpServer>>().also(catalog.mcpAnswers::add)
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        catalog.mcp = listOf(auth)
        presenter.onIntent(StatusIntent.Refresh)
        runCurrent()
        stale.complete(listOf(github))
        runCurrent()

        assertEquals(CatalogList(loading = false, items = listOf(auth)), presenter.state.value.mcp)
    }

    @Test
    fun `close is sent once`() = runTest {
        val presenter = presenter(FakeConnection(FakeCatalog()))

        presenter.onIntent(StatusIntent.Close)

        assertEquals(StatusEvent.Close, presenter.events.first())
        presenter.onIntent(StatusIntent.Close)
        assertEquals(listOf<StatusEvent>(StatusEvent.Close), presenter.events.take(1).toList())
    }

    @Test
    fun `close reads nothing`() = runTest {
        val connection = FakeConnection(FakeCatalog())
        val presenter = presenter(connection)
        runCurrent()
        val calls = connection.calls

        presenter.onIntent(StatusIntent.Close)
        runCurrent()

        assertEquals(calls, connection.calls)
    }

    @Test
    fun `printing the state never shows an error, a name or a path from the server`() = runTest {
        val catalog = FakeCatalog().apply {
            mcp = listOf(docs)
            plugins = listOf(broken)
        }
        val presenter = presenter(FakeConnection(catalog))
        runCurrent()

        val printed = presenter.state.value.toString()

        assertFalse(printed.contains("hunter2"))
        assertFalse(printed.contains("token"))
        assertFalse(printed.contains("/home/user"))
        assertFalse(printed.contains("docs"))
        assertEquals(
            "StatusState(mcp=CatalogList(loading=false, items=1, error=null), " +
                "plugins=CatalogList(loading=false, items=1, error=null))",
            printed,
        )
    }
}
