package io.github.thewisekd.phopencode.feature.connect.logic.hostkey

import io.github.thewisekd.phopencode.core.model.ServerProfileId
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionError
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionManager
import io.github.thewisekd.phopencode.core.remote.connection.ConnectionState
import io.github.thewisekd.phopencode.core.remote.connection.HostKeyReplacement
import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint
import io.github.thewisekd.phopencode.core.storage_api.KnownHostsStore
import io.github.thewisekd.phopencode.core.storage_api.ProfileRepository
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyError
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyEvent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyIntent
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyPresenter
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyRequest
import io.github.thewisekd.phopencode.feature.connect.logic.contract.HostKeyState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Approving the host key of the server being connected to. It follows [ConnectionManager.state]
 * from the moment its scope runs, and shows only requests whose
 * [io.github.thewisekd.phopencode.core.remote.connection.ConnectionServer.profileId] is [serverId]:
 *
 * - [ConnectionState.HostApproval] shows [HostKeyRequest.FirstSeen]. Approving answers
 *   [ConnectionManager.approveHostKey] with that very state; the manager stores the key for the
 *   endpoint the connection used and goes on. Rejecting answers [ConnectionManager.rejectHostKey];
 *   the connection ends with [ConnectionError.HostKeyRejected].
 * - [ConnectionState.Error] with [ConnectionError.HostKeyChanged] shows [HostKeyRequest.Changed].
 *   The manager has already stopped. [HostKeyIntent.Approve] does nothing for it; only
 *   [HostKeyIntent.AcceptChangedKey] replaces the approved key, through
 *   [ConnectionManager.acceptChangedHostKey] with that very state, which also connects again.
 *   Rejecting it leaves the connection stopped.
 *
 * Every decision is bound to the exact state object that was shown, so one never applies to a
 * later request, even an equal-looking one. A successful decision sends [HostKeyEvent.Close], after
 * which no intent is taken. A decision that finds the request superseded, or that fails, sends no
 * event and shows a [HostKeyError]; if the connection moved on meanwhile, the obsolete request
 * leaves the screen and whatever the connection asks now is shown. One decision runs at a time.
 *
 * Errors: [HostKeyError.NoRequest] and [HostKeyError.Superseded] only say that there is no request
 * to decide, or that the one decided on became obsolete, so a matching request arriving later
 * clears them and shows without a stale banner. [HostKeyError.StorageFailed] reports a real failure
 * and stays until it is dismissed or a decision succeeds.
 *
 * @param serverId The server this screen decides for.
 * @param connection Tells the key to decide on and takes the decision.
 * @param knownHosts Unused: the manager reads and writes known hosts for the endpoint it connected
 *   to. Kept because the contract fixes the constructor.
 * @param profiles Unused, for the same reason: the endpoint and profile come from the connection.
 */
class DefaultHostKeyPresenter(
    private val serverId: ServerProfileId,
    private val connection: ConnectionManager,
    @Suppress("UNUSED_PARAMETER") knownHosts: KnownHostsStore,
    @Suppress("UNUSED_PARAMETER") profiles: ProfileRepository,
    private val scope: CoroutineScope,
) : HostKeyPresenter {
    private val mutableState = MutableStateFlow(HostKeyState())

    override val state: StateFlow<HostKeyState> = mutableState.asStateFlow()

    // Unlimited, so that a decision can send without suspending and no event is dropped.
    private val eventChannel = Channel<HostKeyEvent>(Channel.UNLIMITED)

    override val events: Flow<HostKeyEvent> = eventChannel.receiveAsFlow()

    /** What is on screen: the request and the connection state object it came from. */
    private class Shown(val request: HostKeyRequest, val source: ConnectionState)

    private val lock = Any()

    /** Guarded by [lock]. */
    private var shown: Shown? = null

    /** Guarded by [lock]: a decision is being applied. */
    private var deciding = false

    /** Guarded by [lock]: a decision was made; nothing more is done. */
    private var finished = false

    init {
        // The screen's scope may be Dispatchers.Main.immediate, which runs a launch at once; the
        // yield hands it back to the dispatcher, so nothing runs inside the constructor.
        scope.launch {
            yield()
            connection.state.collect(::follow)
        }
    }

    override fun onIntent(intent: HostKeyIntent) {
        when (intent) {
            HostKeyIntent.Approve -> claim<HostKeyRequest.FirstSeen>()?.let { target ->
                scope.launch { approve(target) }
            }
            HostKeyIntent.Reject -> claim<HostKeyRequest>()?.let { target ->
                scope.launch { reject(target) }
            }
            HostKeyIntent.AcceptChangedKey -> claim<HostKeyRequest.Changed>()?.let { target ->
                scope.launch { acceptChanged(target) }
            }
            HostKeyIntent.DismissError -> mutableState.update { it.copy(error = null) }
        }
    }

    /**
     * Takes what is shown for one decision, if it is a [R] and no decision runs. Synchronous, so the
     * decision is about the request on screen when the user acted, never a later one.
     */
    private inline fun <reified R : HostKeyRequest> claim(): Shown? = synchronized(lock) {
        val current = shown
        if (finished || deciding || current == null || current.request !is R) return null
        deciding = true
        mutableState.update { it.copy(deciding = true) }
        current
    }

    private fun follow(state: ConnectionState) {
        val request = requestOf(state)
        synchronized(lock) {
            // While a decision runs, the state it causes or races with is its business; it shows
            // the state that holds when it ends.
            if (finished || deciding || (request != null && shown?.source === state)) return
            if (request != null) {
                shown = Shown(request, state)
                // NoRequest and Superseded only said there was nothing to decide; now there is.
                mutableState.update { it.copy(loading = false, request = request, error = it.error?.takeUnless(::aboutAbsence)) }
                return
            }
            val waiting = state is ConnectionState.Ssh || state is ConnectionState.Reconnecting
            if (shown == null && waiting) return
            // Something was shown and the connection moved on, or the connection asks nothing of
            // this server: either way there is nothing to decide.
            val hadRequest = shown != null
            shown = null
            mutableState.update {
                // With nothing shown, an error a decision just showed is the more precise one.
                val error = if (hadRequest) HostKeyError.NoRequest else it.error ?: HostKeyError.NoRequest
                it.copy(loading = false, request = null, error = error)
            }
        }
    }

    /** [error] only described a request that was missing or obsolete, not a failure. */
    private fun aboutAbsence(error: HostKeyError): Boolean =
        error == HostKeyError.NoRequest || error == HostKeyError.Superseded

    /** The request [state] asks of this server, or null. */
    private fun requestOf(state: ConnectionState): HostKeyRequest? = when (state) {
        is ConnectionState.HostApproval -> if (state.server.profileId != serverId) {
            null
        } else {
            HostKeyRequest.FirstSeen(state.key.type, HostKeyFingerprint(state.key.fingerprintSha256))
        }
        is ConnectionState.Error -> when (val error = state.error) {
            is ConnectionError.HostKeyChanged -> if (error.server?.profileId != serverId) {
                null
            } else {
                HostKeyRequest.Changed(
                    keyType = error.key.type,
                    fingerprint = HostKeyFingerprint(error.key.fingerprintSha256),
                    approved = error.approved,
                )
            }
            else -> null
        }
        else -> null
    }

    private suspend fun approve(target: Shown) {
        val source = target.source as ConnectionState.HostApproval
        if (connection.approveHostKey(source)) return finish()
        // The state tells why: a storage failure, or a request that is no longer current.
        val failure = (connection.state.value as? ConnectionState.Error)?.error
        failed(target, if (failure == ConnectionError.StorageUnavailable) HostKeyError.StorageFailed else HostKeyError.NoRequest)
    }

    private fun reject(target: Shown) {
        val rejected = when (val source = target.source) {
            is ConnectionState.HostApproval -> connection.rejectHostKey(source)
            // The connection is already stopped and stays so, unless it moved on to something newer.
            else -> connection.state.value === source
        }
        if (rejected) finish() else failed(target, HostKeyError.NoRequest)
    }

    private suspend fun acceptChanged(target: Shown) {
        val source = target.source as ConnectionState.Error
        when (val result = connection.acceptChangedHostKey(source)) {
            HostKeyReplacement.Replaced -> finish()
            HostKeyReplacement.Stale -> failed(target, HostKeyError.NoRequest)
            is HostKeyReplacement.Mismatch -> failed(target, HostKeyError.Superseded)
            is HostKeyReplacement.Failed -> failed(
                target,
                if (result.error == ConnectionError.StorageUnavailable) HostKeyError.StorageFailed else HostKeyError.NoRequest,
            )
        }
    }

    private fun finish() {
        synchronized(lock) {
            finished = true
            deciding = false
            mutableState.update { it.copy(deciding = false) }
        }
        eventChannel.trySend(HostKeyEvent.Close)
    }

    /**
     * The decision about [target] did not apply, and [error] shows. While the connection still holds
     * the shown state the request stays, to decide again; otherwise it leaves the screen, and what
     * the connection asks now is shown.
     */
    private fun failed(target: Shown, error: HostKeyError) {
        synchronized(lock) {
            deciding = false
            val current = connection.state.value
            if (shown === target && current !== target.source) shown = null
            mutableState.update {
                it.copy(request = shown?.request, deciding = false, error = error, loading = false)
            }
        }
        follow(connection.state.value)
    }
}
