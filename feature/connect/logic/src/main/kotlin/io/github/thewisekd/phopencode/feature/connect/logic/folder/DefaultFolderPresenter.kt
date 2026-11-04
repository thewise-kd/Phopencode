package io.github.thewisekd.phopencode.feature.connect.logic.folder

import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.storage_api.RecentFoldersStore
import io.github.thewisekd.phopencode.feature.connect.logic.contract.ActiveServer
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.FolderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P4.3): shows the initial state and ignores intents. P4.12 fills it in
 * without changing the constructor.
 *
 * @param connection Browses through `ConnectionManager.openSftp`, which fails with
 *   `NotConnectedException` when no connection stands.
 * @param activeServer Whose recent folders to show and add to.
 */
@Suppress("unused")
class DefaultFolderPresenter(
    private val connection: ConnectionManager,
    private val activeServer: ActiveServer,
    private val recentFolders: RecentFoldersStore,
    private val scope: CoroutineScope,
) : FolderPresenter {
    private val mutableState = MutableStateFlow(FolderState())

    override val state: StateFlow<FolderState> = mutableState.asStateFlow()

    override val events: Flow<FolderEvent> = emptyFlow()

    override fun onIntent(intent: FolderIntent) = Unit
}
