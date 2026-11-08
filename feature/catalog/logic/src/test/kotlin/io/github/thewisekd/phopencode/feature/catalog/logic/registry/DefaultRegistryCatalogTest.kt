package io.github.thewisekd.phopencode.feature.catalog.logic.registry

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.model.Command
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.ModelCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [DefaultRegistryCatalog] keeps each list per connection generation and shares reads in flight. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRegistryCatalogTest {
    private val connection = TestCatalogConnection().also { Fixtures.fill(it.api) }
    private val registry = DefaultRegistryCatalog(connection)

    @Test
    fun `each list is read once per connection and kept`() = runTest {
        assertEquals(listOf(Fixtures.review, Fixtures.init), registry.commands())
        assertEquals(listOf(Fixtures.review, Fixtures.init), registry.commands())
        registry.agents()
        registry.agents()
        registry.models()
        registry.models()

        assertEquals(1, connection.count("catalog.commands"))
        assertEquals(1, connection.count("catalog.agents"))
        assertEquals(1, connection.count("catalog.models"))
        assertEquals(1, connection.count("catalog.providers"))
        assertEquals(1, connection.count("catalog.defaultModel"))
    }

    @Test
    fun `agents come back whole, hidden ones included`() = runTest {
        assertEquals(listOf(Fixtures.build, Fixtures.title, Fixtures.plan, Fixtures.compaction), registry.agents())
    }

    @Test
    fun `models come with their providers and the default`() = runTest {
        val catalog = registry.models()

        assertEquals(
            ModelCatalog(
                models = listOf(Fixtures.large, Fixtures.small),
                providers = listOf(Fixtures.anthropic),
                defaultModel = Fixtures.defaultRef,
            ),
            catalog,
        )
        assertEquals("Anthropic", catalog.providerName("anthropic"))
        assertEquals(null, catalog.providerName("local"))
    }

    @Test
    fun `a refresh reads the server again and keeps the new list`() = runTest {
        registry.commands()
        connection.api.setCommands(listOf(Fixtures.init))

        assertEquals(listOf(Fixtures.review, Fixtures.init), registry.commands())
        assertEquals(listOf(Fixtures.init), registry.commands(refresh = true))
        assertEquals(listOf(Fixtures.init), registry.commands())
        assertEquals(2, connection.count("catalog.commands"))
    }

    @Test
    fun `a new connection reads again`() = runTest {
        registry.commands()
        registry.models()
        connection.api.setCommands(listOf(Fixtures.init))
        connection.generation = 2

        assertEquals(listOf(Fixtures.init), registry.commands())
        registry.models()
        assertEquals(2, connection.count("catalog.commands"))
        assertEquals(2, connection.count("catalog.models"))
    }

    @Test
    fun `without a connection nothing kept is returned`() = runTest {
        registry.commands()
        connection.generation = null

        val failure = assertFailsWith<ApiException> { registry.commands() }

        assertEquals(ApiError.Closed, failure.error)
    }

    @Test
    fun `calls made while a read is in flight share it`() = runTest {
        connection.control.stall()
        val first = async { registry.commands() }
        val second = async { registry.commands() }
        val third = async { registry.commands(refresh = true) }
        runCurrent()
        connection.control.resume()

        assertEquals(first.await(), second.await())
        assertEquals(first.await(), third.await())
        assertEquals(1, connection.count("catalog.commands"))
    }

    @Test
    fun `a caller arriving during a refresh waits for it instead of taking the kept list`() = runTest {
        registry.commands()
        connection.api.setCommands(listOf(Fixtures.init))
        connection.control.stall()
        val refreshing = async { registry.commands(refresh = true) }
        runCurrent()
        val plain = async { registry.commands() }
        runCurrent()
        connection.control.resume()

        assertEquals(listOf(Fixtures.init), refreshing.await())
        assertEquals(listOf(Fixtures.init), plain.await())
        assertEquals(2, connection.count("catalog.commands"))
    }

    @Test
    fun `a failure is shared by the callers waiting on it and is not kept`() = runTest {
        connection.control.stall()
        connection.control.failNext(ApiError.Network)
        val first = async { runCatching { registry.commands() } }
        val second = async { runCatching { registry.commands() } }
        runCurrent()
        connection.control.resume()

        assertEquals(ApiError.Network, (first.await().exceptionOrNull() as ApiException).error)
        assertEquals(ApiError.Network, (second.await().exceptionOrNull() as ApiException).error)
        assertEquals(listOf(Fixtures.review, Fixtures.init), registry.commands())
        assertEquals(2, connection.count("catalog.commands"))
    }

    @Test
    fun `a failed refresh leaves the kept list`() = runTest {
        registry.commands()
        connection.control.failNext(ApiError.Timeout)

        assertFailsWith<ApiException> { registry.commands(refresh = true) }
        assertEquals(listOf(Fixtures.review, Fixtures.init), registry.commands())
        assertEquals(2, connection.count("catalog.commands"))
    }

    @Test
    fun `one list failing leaves the others`() = runTest {
        connection.control.failNext(ApiError.Server(500, null))

        assertFailsWith<ApiException> { registry.commands() }
        assertEquals(4, registry.agents().size)
    }

    @Test
    fun `a waiting caller reads on its own when the one reading is cancelled`() = runTest {
        connection.control.stall()
        val owner = async { registry.commands() }
        runCurrent()
        val waiter = async { registry.commands() }
        runCurrent()
        owner.cancel()
        runCurrent()
        connection.control.resume()

        assertEquals(listOf<Command>(Fixtures.review, Fixtures.init), waiter.await())
        assertEquals(2, connection.count("catalog.commands"))
    }

    @Test
    fun `an older connection's waiter that reads again leaves the newer connection's read shared`() = runTest {
        connection.control.stall()
        val owner = async { registry.commands() }
        runCurrent()
        val olderWaiter = async { runCatching { registry.commands() } }
        runCurrent()
        connection.generation = 2
        val newer = async { registry.commands() }
        runCurrent()
        owner.cancel()
        runCurrent()
        val newerWaiter = async { registry.commands() }
        runCurrent()
        connection.control.resume()

        olderWaiter.await()
        assertEquals(newer.await(), newerWaiter.await())
        // The cancelled owner, the older waiter reading again, and one read for the newer connection.
        assertEquals(3, connection.count("catalog.commands"))
        registry.commands()
        assertEquals(3, connection.count("catalog.commands"))
    }

    @Test
    fun `models and agents share a newer connection's read the same way`() = runTest {
        connection.control.stall()
        val agentsOwner = async { registry.agents() }
        val modelsOwner = async { registry.models() }
        runCurrent()
        val agentsOlder = async { runCatching { registry.agents() } }
        val modelsOlder = async { runCatching { registry.models() } }
        runCurrent()
        connection.generation = 2
        val agentsNewer = async { registry.agents() }
        val modelsNewer = async { registry.models() }
        runCurrent()
        agentsOwner.cancel()
        modelsOwner.cancel()
        runCurrent()
        val agentsNewerWaiter = async { registry.agents() }
        val modelsNewerWaiter = async { registry.models() }
        runCurrent()
        connection.control.resume()

        agentsOlder.await()
        modelsOlder.await()
        assertEquals(agentsNewer.await(), agentsNewerWaiter.await())
        assertEquals(modelsNewer.await(), modelsNewerWaiter.await())
        assertEquals(3, connection.count("catalog.agents"))
        assertEquals(3, connection.count("catalog.models"))
    }

    @Test
    fun `a read from an older connection is not kept over a newer one`() = runTest {
        connection.control.stall()
        val old = async { registry.commands() }
        runCurrent()
        connection.control.resume()
        connection.api.setCommands(listOf(Fixtures.init))
        connection.generation = 2
        assertEquals(listOf(Fixtures.init), registry.commands())
        old.await()

        assertEquals(listOf(Fixtures.init), registry.commands())
        assertEquals(2, connection.count("catalog.commands"))
    }

    @Test
    fun `printing it shows nothing of the server`() = runTest {
        registry.commands()

        assertFalse("secret" in registry.toString())
        assertEquals("DefaultRegistryCatalog", registry.toString())
    }
}
