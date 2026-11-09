package io.github.thewisekd.phopencode.feature.notify.logic.setup

import io.github.thewisekd.phopencode.core.storage_api.SecretStore
import io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifyService
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupEvent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupIntent
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupPresenter
import io.github.thewisekd.phopencode.feature.notify.logic.contract.SetupState
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Stub from the contract (P11.7): shows the initial state and ignores intents. P11.9 fills it in
 * without changing the constructor; loading starts in [scope] after a `yield()`.
 *
 * @param secrets Keeps the topic and the server ([io.github.thewisekd.phopencode.feature.notify.logic.contract.NotifySecrets]).
 * @param service The notification service on the server of the active connection.
 * @param random Makes a new topic.
 * @param scope The destination's scope; work started here stops with it.
 */
@Suppress("unused")
class DefaultSetupPresenter(
    private val secrets: SecretStore,
    private val service: NotifyService,
    private val random: SecureRandom,
    private val scope: CoroutineScope,
) : SetupPresenter {
    private val mutableState = MutableStateFlow(SetupState())

    override val state: StateFlow<SetupState> = mutableState.asStateFlow()

    override val events: Flow<SetupEvent> = emptyFlow()

    override fun onIntent(intent: SetupIntent) = Unit
}
