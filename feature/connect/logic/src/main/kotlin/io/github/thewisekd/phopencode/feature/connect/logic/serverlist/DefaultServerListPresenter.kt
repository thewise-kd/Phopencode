package io.github.thewisekd.phopencode.feature.connect.logic.serverlist

import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ServerListState
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P4.3): shows the initial state and ignores intents. P4.4 fills it in
 * without changing the constructor.
 *
 * @param secrets Deleting a server deletes the secrets only it refers to.
 * @param keyIndex A key's secrets belong to the index, so deleting a server leaves them.
 * @param recentFolders Deleting a server forgets its recent folders.
 * @param clock Marks a selected server as used.
 */
@Suppress("unused")
class DefaultServerListPresenter(
    private val profiles: ProfileRepository,
    private val secrets: SecretStore,
    private val keyIndex: SshKeyIndex,
    private val recentFolders: RecentFoldersStore,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : ServerListPresenter {
    private val mutableState = MutableStateFlow(ServerListState())

    override val state: StateFlow<ServerListState> = mutableState.asStateFlow()

    override val events: Flow<ServerListEvent> = emptyFlow()

    override fun onIntent(intent: ServerListIntent) = Unit
}
