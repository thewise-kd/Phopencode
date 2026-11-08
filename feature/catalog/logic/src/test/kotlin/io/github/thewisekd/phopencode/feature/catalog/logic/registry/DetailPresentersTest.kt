package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.AgentDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogFailure
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CommandDetailState
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailEvent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailIntent
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelDetailState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** The command, agent and model details find their entry in [DefaultRegistryCatalog]. */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailPresentersTest {
    private val connection = TestCatalogConnection().also { Fixtures.fill(it.api) }
    private val registry = DefaultRegistryCatalog(connection)

    @Test
    fun `a command is found by name`() = runTest {
        val presenter = DefaultCommandDetailPresenter(name = "review", registry = registry, scope = backgroundScope)
        assertTrue(connection.control.calls.isEmpty())
        runCurrent()

        assertEquals(CommandDetailState(name = "review", loading = false, command = Fixtures.review), presenter.state.value)
    }

    @Test
    fun `a command uses the kept list`() = runTest {
        registry.commands()
        DefaultCommandDetailPresenter(name = "init", registry = registry, scope = backgroundScope)
        runCurrent()

        assertEquals(1, connection.count("catalog.commands"))
    }

    @Test
    fun `a missing command is not found`() = runTest {
        val presenter = DefaultCommandDetailPresenter(name = "gone", registry = registry, scope = backgroundScope)
        runCurrent()

        assertEquals(
            CommandDetailState(name = "gone", loading = false, error = CatalogFailure.NotFound),
            presenter.state.value,
        )
    }

    @Test
    fun `a command retry reads the server again`() = runTest {
        connection.control.failNext(ApiError.Unauthorized)
        val presenter = DefaultCommandDetailPresenter(name = "review", registry = registry, scope = backgroundScope)
        runCurrent()
        assertEquals(CatalogFailure.Unauthorized, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)

        presenter.onIntent(CommandDetailIntent.Retry)
        runCurrent()

        assertEquals(Fixtures.review, presenter.state.value.command)
        assertEquals(null, presenter.state.value.error)
    }

    @Test
    fun `a retry after not found reads the list from the server`() = runTest {
        val presenter = DefaultCommandDetailPresenter(name = "new", registry = registry, scope = backgroundScope)
        runCurrent()
        val added = Fixtures.init.copy(name = "new")
        connection.api.setCommands(listOf(added))

        presenter.onIntent(CommandDetailIntent.Retry)
        runCurrent()

        assertEquals(added, presenter.state.value.command)
    }

    @Test
    fun `a command closes`() = runTest {
        val presenter = DefaultCommandDetailPresenter(name = "review", registry = registry, scope = backgroundScope)
        presenter.onIntent(CommandDetailIntent.Close)

        assertEquals(CommandDetailEvent.Close, presenter.events.first())
    }

    @Test
    fun `printing a command detail hides its description`() = runTest {
        val presenter = DefaultCommandDetailPresenter(name = "review", registry = registry, scope = backgroundScope)
        runCurrent()

        assertFalse("secret" in presenter.state.value.toString())
    }

    @Test
    fun `an agent comes with its permissions and model`() = runTest {
        val presenter = DefaultAgentDetailPresenter(agentId = "build", registry = registry, scope = backgroundScope)
        runCurrent()

        val state = presenter.state.value
        assertEquals(AgentDetailState(agentId = "build", loading = false, agent = Fixtures.build), state)
        assertEquals(listOf(Fixtures.readSecret, Fixtures.editAll), state.agent?.permissions)
        assertFalse("/srv/private" in state.toString())
        assertFalse("private project" in state.toString())
    }

    @Test
    fun `a hidden agent is found too`() = runTest {
        val presenter = DefaultAgentDetailPresenter(agentId = "title", registry = registry, scope = backgroundScope)
        runCurrent()

        assertEquals(Fixtures.title, presenter.state.value.agent)
    }

    @Test
    fun `a missing agent is not found`() = runTest {
        val presenter = DefaultAgentDetailPresenter(agentId = "gone", registry = registry, scope = backgroundScope)
        runCurrent()

        assertEquals(AgentDetailState(agentId = "gone", loading = false, error = CatalogFailure.NotFound), presenter.state.value)
    }

    @Test
    fun `an agent's model opens, the variant left to the model screen`() = runTest {
        val presenter = DefaultAgentDetailPresenter(agentId = "build", registry = registry, scope = backgroundScope)
        runCurrent()

        presenter.onIntent(AgentDetailIntent.OpenModel)
        presenter.onIntent(AgentDetailIntent.Close)

        assertEquals(
            listOf(AgentDetailEvent.OpenModel("anthropic", "claude-large"), AgentDetailEvent.Close),
            presenter.events.take(2).toList(),
        )
    }

    @Test
    fun `an agent without a model opens nothing`() = runTest {
        val presenter = DefaultAgentDetailPresenter(agentId = "plan", registry = registry, scope = backgroundScope)
        runCurrent()

        presenter.onIntent(AgentDetailIntent.OpenModel)
        presenter.onIntent(AgentDetailIntent.Close)

        assertEquals(AgentDetailEvent.Close, presenter.events.first())
    }

    @Test
    fun `an agent retry clears the error`() = runTest {
        connection.control.failNext(ApiError.Server(503))
        val presenter = DefaultAgentDetailPresenter(agentId = "build", registry = registry, scope = backgroundScope)
        runCurrent()
        assertEquals(CatalogFailure.ServerFailed, presenter.state.value.error)

        presenter.onIntent(AgentDetailIntent.Retry)
        runCurrent()

        assertEquals(AgentDetailState(agentId = "build", loading = false, agent = Fixtures.build), presenter.state.value)
    }

    @Test
    fun `a model comes with its limits, prices, provider name and default mark`() = runTest {
        val presenter = DefaultModelDetailPresenter(
            providerId = "anthropic",
            modelId = "claude-large",
            registry = registry,
            scope = backgroundScope,
        )
        runCurrent()

        val state = presenter.state.value
        assertEquals(
            ModelDetailState(
                providerId = "anthropic",
                modelId = "claude-large",
                loading = false,
                model = Fixtures.large,
                providerName = "Anthropic",
                defaultModel = Fixtures.defaultRef,
            ),
            state,
        )
        assertTrue(state.isDefault)
        assertEquals(200_000L, state.model?.limit?.context)
        assertEquals(64_000L, state.model?.limit?.output)
        assertEquals(Fixtures.usd("3"), state.model?.pricing?.base?.input)
        assertEquals(2, state.model?.pricing?.tiers?.size)
    }

    @Test
    fun `a model of an unlisted provider has no provider name and is not the default`() = runTest {
        val presenter = DefaultModelDetailPresenter(providerId = "local", modelId = "small", registry = registry, scope = backgroundScope)
        runCurrent()

        val state = presenter.state.value
        assertEquals(Fixtures.small, state.model)
        assertEquals(null, state.providerName)
        assertFalse(state.isDefault)
    }

    @Test
    fun `a model is matched on provider and ID both`() = runTest {
        val presenter = DefaultModelDetailPresenter(
            providerId = "local",
            modelId = "claude-large",
            registry = registry,
            scope = backgroundScope,
        )
        runCurrent()

        assertEquals(null, presenter.state.value.model)
        assertEquals(CatalogFailure.NotFound, presenter.state.value.error)
        assertFalse(presenter.state.value.loading)
    }

    @Test
    fun `a model that cannot be read shows the error, and a retry reads it`() = runTest {
        connection.generation = null
        val presenter = DefaultModelDetailPresenter(providerId = "local", modelId = "small", registry = registry, scope = backgroundScope)
        runCurrent()
        assertEquals(CatalogFailure.NotConnected, presenter.state.value.error)

        connection.generation = 3
        presenter.onIntent(ModelDetailIntent.Retry)
        runCurrent()

        assertEquals(Fixtures.small, presenter.state.value.model)
        assertEquals(null, presenter.state.value.error)
    }

    @Test
    fun `a model closes`() = runTest {
        val presenter = DefaultModelDetailPresenter(providerId = "local", modelId = "small", registry = registry, scope = backgroundScope)
        presenter.onIntent(ModelDetailIntent.Close)

        assertEquals(ModelDetailEvent.Close, presenter.events.first())
    }
}
