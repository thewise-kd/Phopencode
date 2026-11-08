package io.github.thewisekd.phopencode.feature.catalog.logic.skills

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.CatalogApi
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import io.github.thewisekd.phopencode.feature.catalog.logic.contract.CatalogConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSkillCatalogTest {
    private val server = TestServer()
    private val catalog = DefaultSkillCatalog(server.connection)

    @Test
    fun `the list is read once per connection and kept`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review"), skill("deploy")))

        val first = catalog.skills()
        client.catalog.setSkills(listOf(skill("changed")))
        val second = catalog.skills()

        assertEquals(listOf("review", "deploy"), first.map { it.id })
        assertEquals(first, second)
        assertEquals(1, client.skillReads())
    }

    @Test
    fun `a refresh reads again and keeps the new list`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review")))
        catalog.skills()
        client.catalog.setSkills(listOf(skill("changed")))

        assertEquals(listOf("changed"), catalog.skills(refresh = true).map { it.id })
        assertEquals(listOf("changed"), catalog.skills().map { it.id })
        assertEquals(2, client.skillReads())
    }

    @Test
    fun `a new connection is read again, even to the same client`() = runTest {
        val client = server.connect(generation = 1)
        client.catalog.setSkills(listOf(skill("review")))
        catalog.skills()

        client.catalog.setSkills(listOf(skill("second")))
        server.connect(client, generation = 2)
        assertEquals(listOf("second"), catalog.skills().map { it.id })

        val other = FakeOpencodeClient().apply { catalog.setSkills(listOf(skill("third"))) }
        server.connect(other, generation = 3)
        assertEquals(listOf("third"), catalog.skills().map { it.id })
        assertEquals(2, client.skillReads())
        assertEquals(1, other.skillReads())
    }

    @Test
    fun `callers meanwhile share the request on its way`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review")))
        client.control.stall()

        val first = async { catalog.skills() }
        val second = async { catalog.skills() }
        val refreshing = async { catalog.skills(refresh = true) }
        client.control.awaitRunningCalls(1)
        runCurrent()
        client.control.resume()

        assertEquals(listOf("review"), first.await().map { it.id })
        assertEquals(listOf("review"), second.await().map { it.id })
        assertEquals(listOf("review"), refreshing.await().map { it.id })
        assertEquals(1, client.skillReads())
    }

    @Test
    fun `a caller arriving during a refresh gets the refreshed list, not the kept one`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review")))
        catalog.skills()

        client.catalog.setSkills(listOf(skill("changed")))
        client.control.stall()
        val refreshing = async { catalog.skills(refresh = true) }
        client.control.awaitRunningCalls(1)
        val joining = async { catalog.skills() }
        runCurrent()
        assertFalse(joining.isCompleted, "it waits for the refresh instead of taking the kept list")
        client.control.resume()

        assertEquals(listOf("changed"), refreshing.await().map { it.id })
        assertEquals(listOf("changed"), joining.await().map { it.id })
        assertEquals(2, client.skillReads())
    }

    @Test
    fun `a failed read is not kept and fails every caller sharing it`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review")))
        client.control.stall()
        client.control.failNext(ApiError.Timeout)

        val first = async { runCatching { catalog.skills() } }
        val second = async { runCatching { catalog.skills() } }
        client.control.awaitRunningCalls(1)
        runCurrent()
        client.control.resume()

        assertEquals(ApiError.Timeout, (first.await().exceptionOrNull() as ApiException).error)
        assertEquals(ApiError.Timeout, (second.await().exceptionOrNull() as ApiException).error)
        assertEquals(listOf("review"), catalog.skills().map { it.id })
        assertEquals(2, client.skillReads())
    }

    @Test
    fun `a caller cancelled while reading leaves the others to read for themselves`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review")))
        client.control.stall()

        val owner = async { catalog.skills() }
        client.control.awaitRunningCalls(1)
        val waiter = async { catalog.skills() }
        runCurrent()
        owner.cancel()
        client.control.awaitRunningCalls(1)
        runCurrent()
        client.control.resume()

        assertEquals(listOf("review"), waiter.await().map { it.id })
        assertEquals(2, client.skillReads())
    }

    /**
     * A connection whose generation the test picks per call, so that a caller can still hold an
     * older connection's catalog while a newer one is read, as a call that started just before a
     * reconnect does.
     */
    private class ScriptedConnection : CatalogConnection {
        val clients = mutableMapOf<Long, FakeOpencodeClient>()
        var generation = 1L

        override val generations: Flow<Long?> = flowOf(null)

        override suspend fun <T> call(block: suspend (CatalogApi, Long) -> T): T {
            val current = generation
            return block(clients.getValue(current).catalog, current)
        }
    }

    @Test
    fun `a caller of an older connection neither takes nor drops the newer read on its way`() = runTest {
        val connection = ScriptedConnection()
        val older = FakeOpencodeClient().apply { catalog.setSkills(listOf(skill("old"))) }
        val newer = FakeOpencodeClient().apply { catalog.setSkills(listOf(skill("new"))) }
        connection.clients[1] = older
        connection.clients[2] = newer
        val catalog = DefaultSkillCatalog(connection)

        connection.generation = 2
        newer.control.stall()
        val first = async { catalog.skills() }
        newer.control.awaitRunningCalls(1)

        connection.generation = 1
        assertEquals(listOf("old"), catalog.skills().map { it.id })

        connection.generation = 2
        val second = async { catalog.skills() }
        runCurrent()
        newer.control.resume()

        assertEquals(listOf("new"), first.await().map { it.id })
        assertEquals(listOf("new"), second.await().map { it.id })
        assertEquals(listOf("new"), catalog.skills().map { it.id })
        assertEquals(1, newer.skillReads())
    }

    @Test
    fun `a late answer from an older connection is not kept over the newer list`() = runTest {
        val connection = ScriptedConnection()
        val older = FakeOpencodeClient().apply { catalog.setSkills(listOf(skill("old"))) }
        val newer = FakeOpencodeClient().apply { catalog.setSkills(listOf(skill("new"))) }
        connection.clients[1] = older
        connection.clients[2] = newer
        val catalog = DefaultSkillCatalog(connection)

        connection.generation = 1
        older.control.stall()
        val late = async { catalog.skills() }
        older.control.awaitRunningCalls(1)

        connection.generation = 2
        newer.control.failNext(ApiError.Timeout)
        assertFailsWith<ApiException> { catalog.skills() }
        older.control.resume()
        assertEquals(listOf("old"), late.await().map { it.id })

        assertEquals(listOf("new"), catalog.skills().map { it.id })
        assertEquals(2, newer.skillReads())
    }

    @Test
    fun `without a connection the read fails as closed`() = runTest {
        val failure = assertFailsWith<ApiException> { catalog.skills() }

        assertEquals(ApiError.Closed, failure.error)
    }

    @Test
    fun `a list kept for an ended connection is not shown for the next`() = runTest {
        val client = server.connect(generation = 1)
        client.catalog.setSkills(listOf(skill("review")))
        catalog.skills()
        server.disconnect()

        assertFailsWith<ApiException> { catalog.skills() }
    }

    @Test
    fun `it prints nothing of the skills`() = runTest {
        val client = server.connect()
        client.catalog.setSkills(listOf(skill("review", path = "/home/private/skill/SKILL.md")))
        catalog.skills()

        assertEquals("DefaultSkillCatalog", catalog.toString())
    }
}
