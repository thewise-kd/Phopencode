package io.github.thewisekd.phopencode.feature.notify.ui.setup

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyException
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyFailure
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyServiceStatus
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NtfySettings

/**
 * Stub from the contract (P11.7): the adapter from [NotifyService] to the remote installation P11.8
 * adds in `core/remote/…/notify/`, which cannot see this feature. Until it is filled in, every call
 * fails: with [NotifyFailure.NotConnected] when no connection stands, else with
 * [NotifyFailure.Unknown]. The task that wires P11.8 in fills it in on [connection] without
 * changing the constructor, mapping the installer's errors to [NotifyException] as the port's rules
 * say. `NotifyModule` makes one for the app.
 */
class RemoteNotifyService(private val connection: ConnectionManager) : NotifyService {
    override suspend fun status(): NotifyServiceStatus = unavailable()

    override suspend fun install(settings: NtfySettings) = unavailable()

    override suspend fun remove() = unavailable()

    override suspend fun sendTest(settings: NtfySettings) = unavailable()

    private fun unavailable(): Nothing {
        val connected = connection.state.value is ConnectionState.Connected
        throw NotifyException(if (connected) NotifyFailure.Unknown else NotifyFailure.NotConnected)
    }

    override fun toString(): String = "RemoteNotifyService"
}
