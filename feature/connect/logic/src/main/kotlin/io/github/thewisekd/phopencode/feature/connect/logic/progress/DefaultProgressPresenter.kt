package io.github.thewisekd.phopencode.feature.connect.logic.progress

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ProgressState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P4.3): shows the initial state and ignores intents. P4.14 fills it in
 * without changing the constructor.
 *
 * @param serverId The server to connect to.
 * @param connection Connects, and tells each step and error through its state.
 * @param profiles Gives the profile to connect with, and saves the `opencode serve` credential.
 * @param secrets Stores the `opencode serve` password the user enters for a foreign server.
 * @param activeServer Set to [serverId] before connecting, for the folder picker.
 * @param newId Makes the ID of the credential's `SecretRef`.
 */
@Suppress("unused")
class DefaultProgressPresenter(
    private val serverId: ServerProfileId,
    private val connection: ConnectionManager,
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val activeServer: ActiveServer,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProgressPresenter {
    private val mutableState = MutableStateFlow(ProgressState())

    override val state: StateFlow<ProgressState> = mutableState.asStateFlow()

    override val events: Flow<ProgressEvent> = emptyFlow()

    override fun onIntent(intent: ProgressIntent) = Unit
}
