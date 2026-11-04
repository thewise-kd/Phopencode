package io.github.thewisekd.phopencode.feature.connect.logic.keys

import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.core.storage_api.SshKeyIndex
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.KeysState
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P4.3): shows the initial state and ignores intents. P4.8 fills it in
 * without changing the constructor.
 *
 * @param secrets Holds each key's private half and passphrase.
 * @param profiles A key a saved server logs in with is not deleted.
 * @param newId Makes the IDs of a new key and of its `SecretRef`s.
 * @param worker Runs key generation and parsing, which are CPU work, off the screen's thread.
 */
@Suppress("unused")
class DefaultKeysPresenter(
    private val keyIndex: SshKeyIndex,
    private val secrets: SecretStore,
    private val profiles: ProfileRepository,
    private val scope: CoroutineScope,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) : KeysPresenter {
    private val mutableState = MutableStateFlow(KeysState())

    override val state: StateFlow<KeysState> = mutableState.asStateFlow()

    override val events: Flow<KeysEvent> = emptyFlow()

    override fun onIntent(intent: KeysIntent) = Unit
}
