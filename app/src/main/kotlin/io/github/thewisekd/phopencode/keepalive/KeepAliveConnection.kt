package io.github.thewisekd.phopencode.keepalive

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import kotlinx.coroutines.flow.StateFlow

/**
 * The part of the app's [ConnectionManager] the keep-alive uses: its [state], and [disconnect] for
 * the notification's action. An interface so the tests can stand in for the manager.
 */
interface KeepAliveConnection {
    val state: StateFlow<ConnectionState>

    suspend fun disconnect()
}

/** The app's one [ConnectionManager], through its public API only. */
class ManagerKeepAliveConnection(private val manager: ConnectionManager) : KeepAliveConnection {
    override val state: StateFlow<ConnectionState> get() = manager.state

    override suspend fun disconnect() = manager.disconnect()
}
