package io.github.thewisekd.phopencode.feature.connect.logic.serverform

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerFormState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P4.3): shows the initial state and ignores intents. P4.6 fills it in
 * without changing the constructor.
 *
 * @param serverId The server to edit, or null for a new one.
 * @param keyIndex The keys to log in with.
 * @param newId Makes the IDs of a new profile and of its `SecretRef`s.
 */
@Suppress("unused")
class DefaultServerFormPresenter(
    private val serverId: ServerProfileId?,
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val keyIndex: SshKeyIndex,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ServerFormPresenter {
    private val mutableState = MutableStateFlow(ServerFormState(isNew = serverId == null))

    override val state: StateFlow<ServerFormState> = mutableState.asStateFlow()

    override val events: Flow<ServerFormEvent> = emptyFlow()

    override fun onIntent(intent: ServerFormIntent) = Unit
}
