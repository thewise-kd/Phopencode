package io.github.thewisekd.phopencode.feature.connect.logic.contract

import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint

/**
 * The key a server presented, read from the connection: `ConnectionState.HostApproval` for a key
 * seen for the first time, `ConnectionError.HostKeyChanged` for one that differs from the approved
 * key. Fingerprints print masked; the screen shows [HostKeyFingerprint.sha256].
 *
 * @property keyType The SSH name of the algorithm, for example `ssh-ed25519`.
 */
sealed interface HostKeyRequest {
    val keyType: String
    val fingerprint: HostKeyFingerprint

    /** No key is approved for this server yet. */
    data class FirstSeen(override val keyType: String, override val fingerprint: HostKeyFingerprint) : HostKeyRequest

    /**
     * The server presented a key other than [approved]. This may be an attack: the connection has
     * stopped, and the screen warns in red.
     */
    data class Changed(
        override val keyType: String,
        override val fingerprint: HostKeyFingerprint,
        val approved: HostKeyFingerprint,
    ) : HostKeyRequest
}

enum class HostKeyError {
    /** The connection asks for no decision any more, for example because it ended. */
    NoRequest,

    /** The server's profile is gone, so its known host cannot be found. */
    ServerMissing,

    /** Known hosts could not be read or written. */
    StorageFailed,

    /** The approved key changed while the user decided; nothing was replaced. */
    Superseded,
}

/**
 * Approving the key of the server being connected to (P4.10, P4.11).
 *
 * @property loading Waiting for the connection to tell which key to decide on.
 * @property deciding A decision is being applied.
 */
data class HostKeyState(
    val loading: Boolean = true,
    val request: HostKeyRequest? = null,
    val deciding: Boolean = false,
    val error: HostKeyError? = null,
)

sealed interface HostKeyIntent {
    /** Trusts a [HostKeyRequest.FirstSeen] key; the connection goes on. */
    data object Approve : HostKeyIntent

    /** Refuses the key; the connection stops, or stays stopped for a changed key. */
    data object Reject : HostKeyIntent

    /**
     * The user's explicit decision to replace the approved key with a [HostKeyRequest.Changed] one,
     * through `KnownHostsStore.replace`, then to connect again. Never done without this intent.
     */
    data object AcceptChangedKey : HostKeyIntent

    data object DismissError : HostKeyIntent
}

sealed interface HostKeyEvent {
    /** The decision is made; back to the connection flow. */
    data object Close : HostKeyEvent
}

interface HostKeyPresenter : Presenter<HostKeyState, HostKeyIntent, HostKeyEvent>
