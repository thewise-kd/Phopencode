package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.testing.api.FakeApiControl
import io.github.thewisekd.phopencode.core.testing.api.FakeCatalogApi
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogList
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.RegistryTab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [DefaultRegistryPresenter] serves the three tabs, each list on its own. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRegistryPresenterTest {
    private val connection = TestCatalogConnection().also { Fixtures.fill(it.api) }
    private val registry = DefaultRegistryCatalog(connection)

    private fun TestScope.presenter() = DefaultRegistryPresenter(registry = registry, scope = backgroundScope)

    @Test
    fun `nothing is read before the scope runs`() = runTest {
        val presenter = presenter()

        assertEquals(RegistryState(), presenter.state.value)
        assertTrue(connection.control.calls.isEmpty())
    }

    @Test
    fun `the three lists load, hidden agents left out, in the server's order`() = runTest {
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(RegistryTab.Commands, state.tab)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.review, Fixtures.init)), state.commands)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.build, Fixtures.plan)), state.agents)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.large, Fixtures.small)), state.models)
        assertEquals(listOf(Fixtures.anthropic), state.providers)
        assertEquals(Fixtures.defaultRef, state.defaultModel)
    }

    @Test
    fun `a list that fails shows its error and leaves the others`() = runTest {
        connection.control.failNext(ApiError.Network)
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        val failed = listOf(state.commands, state.agents, state.models).filter { it.error != null }
        assertEquals(1, failed.size)
        assertEquals(CatalogFailure.Unreachable, failed.single().error)
        assertFalse(failed.single().loading)
        assertEquals(2, listOf(state.commands, state.agents, state.models).count { it.error == null && !it.loading })
    }

    @Test
    fun `without a connection every list says so`() = runTest {
        connection.generation = null
        val presenter = presenter()
        runCurrent()

        val state = presenter.state.value
        assertEquals(CatalogFailure.NotConnected, state.commands.error)
        assertEquals(CatalogFailure.NotConnected, state.agents.error)
        assertEquals(CatalogFailure.NotConnected, state.models.error)
    }

    @Test
    fun `a tab is selected`() = runTest {
        val presenter = presenter()

        presenter.onIntent(RegistryIntent.SelectTab(RegistryTab.Models))

        assertEquals(RegistryTab.Models, presenter.state.value.tab)
    }

    @Test
    fun `a reload reads its tab from the server again`() = runTest {
        val presenter = presenter()
        runCurrent()
        connection.api.setCommands(listOf(Fixtures.init))

        presenter.onIntent(RegistryIntent.Reload(RegistryTab.Commands))
        runCurrent()

        assertEquals(listOf(Fixtures.init), presenter.state.value.commands.items)
        assertEquals(2, connection.count("catalog.commands"))
        assertEquals(1, connection.count("catalog.agents"))
        assertEquals(1, connection.count("catalog.models"))
    }

    @Test
    fun `a reload after an error clears it and keeps the items while loading`() = runTest {
        val presenter = presenter()
        runCurrent()
        connection.control.failNext(ApiError.Timeout)
        presenter.onIntent(RegistryIntent.Reload(RegistryTab.Agents))
        runCurrent()
        assertEquals(CatalogFailure.Unreachable, presenter.state.value.agents.error)
        assertEquals(listOf(Fixtures.build, Fixtures.plan), presenter.state.value.agents.items)

        connection.control.stall()
        presenter.onIntent(RegistryIntent.Reload(RegistryTab.Agents))
        runCurrent()
        assertEquals(
            CatalogList(loading = true, items = listOf(Fixtures.build, Fixtures.plan)),
            presenter.state.value.agents,
        )

        connection.control.resume()
        runCurrent()
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.build, Fixtures.plan)), presenter.state.value.agents)
    }

    @Test
    fun `a model reload brings new providers and default`() = runTest {
        val presenter = presenter()
        runCurrent()
        connection.api.setDefaultModel(null)
        connection.api.setProviders(emptyList())

        presenter.onIntent(RegistryIntent.Reload(RegistryTab.Models))
        runCurrent()

        assertEquals(null, presenter.state.value.defaultModel)
        assertEquals(emptyList(), presenter.state.value.providers)
    }

    @Test
    fun `a refresh reads kept lists first`() = runTest {
        val presenter = presenter()
        runCurrent()
        connection.api.setCommands(listOf(Fixtures.init))

        presenter.onIntent(RegistryIntent.Refresh)
        runCurrent()

        assertEquals(listOf(Fixtures.review, Fixtures.init), presenter.state.value.commands.items)
        assertEquals(1, connection.count("catalog.commands"))
    }

    @Test
    fun `a refresh after a new connection reads the new server`() = runTest {
        val presenter = presenter()
        runCurrent()
        connection.api.setCommands(listOf(Fixtures.init))
        connection.generation = 2

        presenter.onIntent(RegistryIntent.Refresh)
        runCurrent()

        assertEquals(listOf(Fixtures.init), presenter.state.value.commands.items)
    }

    @Test
    fun `a refresh after a new connection replaces reads still running on the old one`() = runTest {
        val oldServer = FakeApiControl()
        val newServer = FakeApiControl()
        val apis = mapOf(1L to FakeCatalogApi(oldServer), 2L to FakeCatalogApi(newServer))
        apis.values.forEach { Fixtures.fill(it) }
        apis.getValue(2L).setCommands(listOf(Fixtures.init))
        var generation = 1L
        val switching = object : CatalogConnection {
            override val generations: Flow<Long?> = flow { emit(generation) }

            override suspend fun <T> call(block: suspend (catalog: CatalogApi, generation: Long) -> T): T =
                block(apis.getValue(generation), generation)
        }
        oldServer.stall()
        val presenter = DefaultRegistryPresenter(registry = DefaultRegistryCatalog(switching), scope = backgroundScope)
        runCurrent()

        generation = 2
        presenter.onIntent(RegistryIntent.Refresh)
        runCurrent()
        oldServer.close()
        runCurrent()

        val state = presenter.state.value
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.init)), state.commands)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.build, Fixtures.plan)), state.agents)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.large, Fixtures.small)), state.models)
        assertEquals(Fixtures.defaultRef, state.defaultModel)
    }

    @Test
    fun `a refresh while the lists load does not read them twice`() = runTest {
        connection.control.stall()
        val presenter = presenter()
        runCurrent()

        presenter.onIntent(RegistryIntent.Refresh)
        runCurrent()
        connection.control.resume()
        runCurrent()

        assertEquals(1, connection.count("catalog.commands"))
        assertEquals(1, connection.count("catalog.agents"))
        assertEquals(1, connection.count("catalog.models"))
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.review, Fixtures.init)), presenter.state.value.commands)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.build, Fixtures.plan)), presenter.state.value.agents)
        assertEquals(CatalogList(loading = false, items = listOf(Fixtures.large, Fixtures.small)), presenter.state.value.models)
    }

    @Test
    fun `rows open their details and close leaves`() = runTest {
        val presenter = presenter()

        presenter.onIntent(RegistryIntent.OpenCommand("review"))
        presenter.onIntent(RegistryIntent.OpenAgent("build"))
        presenter.onIntent(RegistryIntent.OpenModel("anthropic", "claude-large"))
        presenter.onIntent(RegistryIntent.Close)

        assertEquals(
            listOf(
                RegistryEvent.OpenCommand("review"),
                RegistryEvent.OpenAgent("build"),
                RegistryEvent.OpenModel("anthropic", "claude-large"),
                RegistryEvent.Close,
            ),
            presenter.events.take(4).toList(),
        )
    }

    @Test
    fun `an event waits for a collector and goes to one`() = runTest {
        val presenter = presenter()
        presenter.onIntent(RegistryIntent.Close)

        assertEquals(RegistryEvent.Close, presenter.events.first())
    }

    @Test
    fun `printing the state shows no description or permission`() = runTest {
        val presenter = presenter()
        runCurrent()

        val printed = presenter.state.value.toString()
        assertFalse("secret" in printed)
        assertFalse("private" in printed)
    }
}
