package io.github.thewisekd.phopencode.feature.notify.logic.contract

import io.github.thewisekd.phopencode.core.api.ApiError
import io.github.thewisekd.phopencode.core.api.ApiException
import io.github.thewisekd.phopencode.core.api.ServerInfo
import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.storage_api.HostEndpoint
import io.github.thewisekd.phopencode.core.testing.api.FakeOpencodeClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

/** [ActiveNotifyConnection] reaches the client of the connection standing now, and only that one. */
class NotifyConnectionTest {
    private val server = ConnectionServer(ServerProfileId("server-1"), HostEndpoint("host.invalid", 22))

    private fun connected(client: FakeOpencodeClient, generation: Long) =
        ConnectionState.Connected(client = client, info = ServerInfo("2.2.5"), generation = generation, server = server)

    @Test
    fun `without a connection a call fails as closed and runs nothing`() = runTest {
        val connection = ActiveNotifyConnection(MutableStateFlow(ConnectionState.Idle))
        var ran = false

        val failure = assertFailsWith<ApiException> { connection.call { _, _ -> ran = true } }

        assertEquals(ApiError.Closed, failure.error)
        assertFalse(ran)
    }

    @Test
    fun `a reconnecting connection is not a connection`() = runTest {
        val connection = ActiveNotifyConnection(MutableStateFlow(ConnectionState.Ssh(reconnectAttempt = 1)))

        assertFailsWith<ApiException> { connection.call { _, _ -> Unit } }
    }

    @Test
    fun `a call runs on the standing client with its generation`() = runTest {
        val client = FakeOpencodeClient()
        val connection = ActiveNotifyConnection(MutableStateFlow(connected(client, generation = 7)))

        val (used, generation) = connection.call { it, generation -> it to generation }

        assertSame(client, used)
        assertEquals(7, generation)
    }

    @Test
    fun `a call takes the client standing when it starts`() = runTest {
        val first = FakeOpencodeClient()
        val second = FakeOpencodeClient()
        val state = MutableStateFlow<ConnectionState>(connected(first, generation = 1))
        val connection = ActiveNotifyConnection(state)

        assertSame(first, connection.call { client, _ -> client })
        state.value = connected(second, generation = 2)
        assertSame(second, connection.call { client, _ -> client })
    }

    @Test
    fun `generations follow the connection, null while there is none`() = runTest {
        val client = FakeOpencodeClient()
        val state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        val connection = ActiveNotifyConnection(state)
        assertEquals(null, connection.generations.first())

        val seen = mutableListOf<Long?>()
        val collecting = launch { connection.generations.take(4).toList(seen) }
        yield()
        state.value = connected(client, generation = 1)
        yield()
        state.value = ConnectionState.Ssh(reconnectAttempt = 1)
        yield()
        state.value = ConnectionState.Server(reconnectAttempt = 1)
        yield()
        state.value = connected(client, generation = 2)
        collecting.join()

        assertEquals(listOf(null, 1L, null, 2L), seen)
    }
}
