package io.github.thewisekd.phopencode.core.remote.connection

import io.github.thewisekd.phopencode.core.storage_api.HostKeyFingerprint

/** What [ConnectionManager.acceptChangedHostKey] did. */
sealed interface HostKeyReplacement {
    /**
     * The presented key replaced the approved one for the connection's endpoint, and the same
     * profile is connecting again; a [ConnectionManager.disconnect], [ConnectionManager.connect] or
     * [ConnectionManager.close] that came during the write still stops that reconnect.
     */
    data object Replaced : HostKeyReplacement

    /**
     * Nothing was stored and nothing reconnects: the shown state was no longer the current one, was
     * not a changed key, or a disconnect, new connect or close stopped the replace before it stored
     * anything.
     */
    data object Stale : HostKeyReplacement

    /**
     * Nothing was stored: the approved key is no longer the one the shown state named; [current] is
     * the approved key found, null if none. The same profile connects again, which checks the
     * presented key against [current] and shows the result as any connect does.
     */
    data class Mismatch(val current: HostKeyFingerprint?) : HostKeyReplacement

    /** Nothing was stored, because of [error]; the state is [ConnectionState.Error] with it. */
    data class Failed(val error: ConnectionError) : HostKeyReplacement
}
