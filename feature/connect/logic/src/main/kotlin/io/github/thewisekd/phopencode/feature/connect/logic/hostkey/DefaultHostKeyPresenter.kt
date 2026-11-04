package io.github.thewisekd.phopencode.feature.connect.logic.hostkey

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P4.3): shows the initial state and ignores intents. P4.10 fills it in
 * without changing the constructor.
 *
 * @param serverId The server being connected to.
 * @param connection Tells the key to decide on (`ConnectionState.HostApproval`, or an `Error` with
 *   `HostKeyChanged`) and takes the decision.
 * @param knownHosts Replaces the approved key on [HostKeyIntent.AcceptChangedKey].
 * @param profiles Gives the server's host and port, the known host's endpoint, and the profile to
 *   connect again with.
 */
@Suppress("unused")
class DefaultHostKeyPresenter(
    private val serverId: ServerProfileId,
    private val connection: ConnectionManager,
    private val knownHosts: KnownHostsStore,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
) : HostKeyPresenter {
    private val mutableState = MutableStateFlow(HostKeyState())

    override val state: StateFlow<HostKeyState> = mutableState.asStateFlow()

    override val events: Flow<HostKeyEvent> = emptyFlow()

    override fun onIntent(intent: HostKeyIntent) = Unit
}
