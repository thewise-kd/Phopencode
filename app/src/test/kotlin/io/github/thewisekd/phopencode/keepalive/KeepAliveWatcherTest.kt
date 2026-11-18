package io.github.thewisekd.phopencode.keepalive

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

/** What the watcher asked the service to do, in order. */
private class RecordingStarter : KeepAliveStarter {
    val calls = mutableListOf<String>()

    override fun start() {
        calls += "start"
    }

    override fun stop() {
        calls += "stop"
    }
}

/** Plain JVM: the watcher turns the connection's states into one start per active stretch. */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAliveWatcherTest {
    private val job = Job()

    /** Unconfined: the watcher sees every state the moment the test sets it. */
    private val scope = CoroutineScope(job + UnconfinedTestDispatcher())
    private val connection = FakeConnection()
    private val starter = RecordingStarter()
    private val watcher = KeepAliveWatcher(connection, scope, starter)

    @After
    fun cancel() {
        job.cancel()
    }

    @Test
    fun `an idle connection at the start neither starts nor stops the service`() {
        watcher.start()

        assertEquals(emptyList(), starter.calls)
    }

    @Test
    fun `an error or a close before any connect stops nothing either`() {
        watcher.start()

        connection.state.value = ENDED_STATES[1]
        connection.state.value = ConnectionState.Closed

        assertEquals(emptyList(), starter.calls)
    }

    @Test
    fun `each active state starts the service, each ended state stops it`() {
        watcher.start()
        ACTIVE_STATES.forEach { active ->
            ENDED_STATES.forEach { ended ->
                starter.calls.clear()
                connection.state.value = active
                connection.state.value = ended
                assertEquals(listOf("start", "stop"), starter.calls, "$active then $ended")
            }
        }
    }

    @Test
    fun `a whole connect, with a drop and a reconnect, starts the service once`() {
        watcher.start()

        listOf(
            ConnectionState.Ssh(),
            HOST_APPROVAL,
            ConnectionState.Server(),
            ConnectionState.Api(),
            CONNECTED,
            RECONNECTING,
            ConnectionState.Ssh(reconnectAttempt = 2),
            CONNECTED,
        ).forEach { connection.state.value = it }

        assertEquals(listOf("start"), starter.calls)
    }

    @Test
    fun `starting the watcher twice follows the connection once`() {
        watcher.start()
        watcher.start()

        connection.state.value = CONNECTED

        assertEquals(listOf("start"), starter.calls)
    }

    @Test
    fun `a starter that the system refuses does not stop the watcher`() {
        var refusals = 0
        val refusing = object : KeepAliveStarter {
            override fun start() {
                refusals++
                throw IllegalStateException("not allowed from the background")
            }

            override fun stop() = Unit
        }
        KeepAliveWatcher(connection, scope, refusing).start()

        connection.state.value = CONNECTED
        connection.state.value = RECONNECTING
        connection.state.value = ConnectionState.Idle
        connection.state.value = CONNECTED

        // Once per active stretch, never in a loop, and the connection is left as it is.
        assertEquals(2, refusals)
        assertEquals(CONNECTED, connection.state.value)
        assertEquals(0, connection.disconnects)
    }

    @Test
    fun `a refused stop does not stop the watcher either`() {
        val calls = mutableListOf<String>()
        val refusingStop = object : KeepAliveStarter {
            override fun start() {
                calls += "start"
            }

            override fun stop() {
                calls += "stop"
                throw IllegalStateException("the service is not running and the app is in the background")
            }
        }
        KeepAliveWatcher(connection, scope, refusingStop).start()

        connection.state.value = CONNECTED
        connection.state.value = ConnectionState.Idle
        connection.state.value = CONNECTED

        assertEquals(listOf("start", "stop", "start"), calls)
    }
}
